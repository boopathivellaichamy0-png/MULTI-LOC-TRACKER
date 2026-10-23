package com.antigravity.tracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.*
import com.google.android.gms.location.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * LocationForegroundService — The Immortal GPS Engine
 *
 * Implements:
 *  Strategy 1: Foreground Service         → Android cannot kill this
 *  Strategy 3: WakeLock                   → CPU stays awake screen-off
 *  Strategy 4: WorkManager Watchdog       → self-schedules a reviver
 *  Strategy 6: Fused Location Provider   → GPS+Cell+WiFi+Accelerometer
 *
 *  Transport layers (triple redundancy):
 *   • MQTT Broker 1 (HiveMQ)
 *   • MQTT Broker 2 (EMQX)
 *   • HTTP POST to local server (fallback)
 *   • Offline queue → flushes when back online
 */
class LocationForegroundService : LifecycleService() {

    companion object {
        const val CHANNEL_ID        = "gps_channel"
        const val NOTIF_ID          = 1
        const val TAG               = "QuantumGPS"
        const val ACTION_SOS        = "ACTION_SOS"
        const val ACTION_STOP       = "ACTION_STOP"

        @Volatile var isRunning     = false
        @Volatile var lastLat       = 0.0
        @Volatile var lastLng       = 0.0
    }

    // ── Core Components ──
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var notificationManager: NotificationManager

    // ── Config (from Intent extras) ──
    private var serverUrl  = "http://10.0.2.2:8080"
    private var roomId     = "default-room"
    private var userName   = "QuantumUser"
    private var clientId   = "app_" + Build.SERIAL.takeLast(6)

    // ── State ──
    private var startTimeMs  = 0L
    private var updateCount  = 0
    private var isSOS        = false
    private val offlineQueue = mutableListOf<String>()   // Strategy: Offline Buffer

    // ── MQTT Clients ──
    private var mqtt1: MqttClient? = null
    private var mqtt2: MqttClient? = null
    private val mqttTopic get() = "antigravity_tracker_v3/$roomId/$clientId"

    // ─────────────────────────────────────────────────────────
    //  LIFECYCLE
    // ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        setupMqtt()
        scheduleWatchdog()   // Strategy 4
        isRunning = true
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_SOS  -> triggerSOS()
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }

        // Read config from intent
        intent?.let {
            serverUrl = it.getStringExtra("server_url") ?: serverUrl
            roomId    = it.getStringExtra("room_id")    ?: roomId
            userName  = it.getStringExtra("user_name")  ?: userName
        }

        startTimeMs = System.currentTimeMillis()

        // Show persistent foreground notification
        startForeground(NOTIF_ID, buildNotification("📡 GPS Active — Broadcasting live"))

        // Start location updates
        startFusedLocationUpdates()

        // START_STICKY: if killed, Android auto-restarts the service
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false

        // Clean up location
        fusedLocationClient.removeLocationUpdates(locationCallback)

        // Release wake lock
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()

        // Disconnect MQTT
        try { mqtt1?.disconnect() } catch (_: Exception) {}
        try { mqtt2?.disconnect() } catch (_: Exception) {}

        // Notify admin we stopped
        broadcastPayload(buildPayload(lastLat, lastLng, 0f, 0f, "stopped_sharing", "Service Stopped"))
        Log.i(TAG, "Service destroyed")
    }

    // ─────────────────────────────────────────────────────────
    //  STRATEGY 6: FUSED LOCATION — Best-in-Class Accuracy
    // ─────────────────────────────────────────────────────────

    private fun startFusedLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L   // update interval: 3 seconds
        )
            .setMinUpdateIntervalMillis(1000L)             // fastest: 1 second
            .setMinUpdateDistanceMeters(2f)                // only if moved 2+ metres
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)             // don't wait, stream instantly
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    updateNotification("⚠️ GPS signal lost — searching...")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                request, locationCallback, mainLooper
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied: ${e.message}")
            stopSelf()
        }
    }

    // ─────────────────────────────────────────────────────────
    //  ON EACH LOCATION FIX
    // ─────────────────────────────────────────────────────────

    private fun onNewLocation(loc: Location) {
        lastLat = loc.latitude
        lastLng = loc.longitude
        updateCount++

        val speed    = if (loc.hasSpeed()) loc.speed else 0f
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else 5f
        val heading  = if (loc.hasBearing()) loc.bearing else 0f

        val status = if (isSOS) "SOS" else "active"
        val payload = buildPayload(loc.latitude, loc.longitude, speed, accuracy, status, "Active", heading)

        // Send via all channels
        broadcastPayload(payload)

        // Update notification
        val speedKmh = (speed * 3.6f).roundToInt()
        val uptimeSec = (System.currentTimeMillis() - startTimeMs) / 1000
        updateNotification("📡 LIVE · $speedKmh km/h · ±${accuracy.roundToInt()}m · ${formatUptime(uptimeSec)}")

        // Broadcast telemetry to MainActivity UI
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("LOCATION_UPDATE").apply {
                putExtra("lat", loc.latitude)
                putExtra("lng", loc.longitude)
                putExtra("speed", speed)
                putExtra("accuracy", accuracy)
                putExtra("uptime_seconds", uptimeSec)
                putExtra("status", status)
            }
        )
    }

    // ─────────────────────────────────────────────────────────
    //  SOS PANIC TRIGGER
    // ─────────────────────────────────────────────────────────

    private fun triggerSOS() {
        isSOS = true
        val payload = buildPayload(lastLat, lastLng, 0f, 0f, "SOS", "🚨 EMERGENCY SOS")
        broadcastPayload(payload)

        // Vibrate SOS pattern: · · · — — — · · ·
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 200, 100, 200, 100, 200, 300, 600, 300, 600, 300, 600, 300, 200, 100, 200, 100, 200),
                -1
            ))
        }
        updateNotification("🚨 SOS ACTIVE — EMERGENCY BROADCAST")
        Log.w(TAG, "SOS triggered!")
    }

    // ─────────────────────────────────────────────────────────
    //  PAYLOAD BUILDER
    // ─────────────────────────────────────────────────────────

    private fun buildPayload(lat: Double, lng: Double, speed: Float, accuracy: Float,
                             status: String, reason: String, heading: Float = 0f): String {
        return JSONObject().apply {
            put("id",        clientId)
            put("name",      userName)
            put("lat",       lat)
            put("lng",       lng)
            put("accuracy",  accuracy.toInt().coerceAtMost(10))
            put("speed",     (speed * 3.6f).roundToInt())
            put("heading",   heading.roundToInt())
            put("room",      roomId)
            put("status",    status)
            put("reason",    reason)
            put("source",    "android_native")
            put("timestamp", java.text.SimpleDateFormat("hh:mm:ss a",
                java.util.Locale.getDefault()).format(java.util.Date()))
        }.toString()
    }

    // ─────────────────────────────────────────────────────────
    //  TRIPLE-REDUNDANT BROADCAST
    // ─────────────────────────────────────────────────────────

    private fun broadcastPayload(payload: String) {
        // 1. MQTT Broker 1 (HiveMQ)
        publishMqtt(mqtt1, payload)

        // 2. MQTT Broker 2 (EMQX)
        publishMqtt(mqtt2, payload)

        // 3. HTTP POST to local server
        sendHttpAsync(payload)
    }

    private fun publishMqtt(client: MqttClient?, payload: String) {
        try {
            if (client?.isConnected == true) {
                val msg = MqttMessage(payload.toByteArray()).apply {
                    qos      = 1
                    isRetained = true
                }
                client.publish(mqttTopic, msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "MQTT publish failed: ${e.message}")
        }
    }

    private fun sendHttpAsync(payload: String) {
        Thread {
            try {
                val url = URL("$serverUrl/api/update")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod         = "POST"
                conn.doOutput              = true
                conn.connectTimeout        = 4000
                conn.readTimeout           = 4000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.write(payload.toByteArray())
                val code = conn.responseCode
                conn.disconnect()

                if (code == 200) {
                    // Flush offline queue if connected
                    synchronized(offlineQueue) {
                        val iter = offlineQueue.iterator()
                        while (iter.hasNext()) {
                            try {
                                sendHttpSync(iter.next())
                                iter.remove()
                            } catch (_: Exception) { break }
                        }
                    }
                }
            } catch (e: Exception) {
                // Network unavailable — queue for retry
                synchronized(offlineQueue) {
                    if (offlineQueue.size < 100) offlineQueue.add(payload)
                }
                Log.w(TAG, "HTTP failed, queued. Queue size: ${offlineQueue.size}")
            }
        }.start()
    }

    private fun sendHttpSync(payload: String) {
        val url  = URL("$serverUrl/api/update")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput      = true
        conn.connectTimeout = 3000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.write(payload.toByteArray())
        conn.responseCode
        conn.disconnect()
    }

    // ─────────────────────────────────────────────────────────
    //  STRATEGY 1: MQTT SETUP (dual-broker redundancy)
    // ─────────────────────────────────────────────────────────

    private fun setupMqtt() {
        Thread {
            mqtt1 = connectMqtt(
                "ssl://broker.hivemq.com:8883",
                "h1_${clientId}_${(Math.random() * 999).toInt()}"
            )
            mqtt2 = connectMqtt(
                "ssl://broker.emqx.io:8883",
                "e2_${clientId}_${(Math.random() * 999).toInt()}"
            )
        }.start()
    }

    private fun connectMqtt(brokerUrl: String, id: String): MqttClient? {
        return try {
            val client = MqttClient(brokerUrl, id, MemoryPersistence())
            val opts   = MqttConnectOptions().apply {
                isCleanSession  = false
                keepAliveInterval = 30
                connectionTimeout = 10
                isAutomaticReconnect = true
            }
            client.connect(opts)
            Log.i(TAG, "MQTT connected: $brokerUrl")
            client
        } catch (e: Exception) {
            Log.w(TAG, "MQTT connect failed ($brokerUrl): ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────
    //  STRATEGY 3: WAKE LOCK — CPU stays awake screen-off
    // ─────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "QuantumGPS::WakeLock"
        )
        wakeLock.acquire(12 * 60 * 60 * 1000L) // max 12 hours, re-acquired on restart
    }

    // ─────────────────────────────────────────────────────────
    //  STRATEGY 4: WORKMANAGER WATCHDOG
    // ─────────────────────────────────────────────────────────

    private fun scheduleWatchdog() {
        val watchdog = PeriodicWorkRequestBuilder<GPSWatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "gps_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            watchdog
        )
        Log.i(TAG, "Watchdog scheduled (every 15 min)")
    }

    // ─────────────────────────────────────────────────────────
    //  NOTIFICATION
    // ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live GPS Tracking",
                NotificationManager.IMPORTANCE_LOW   // Silent but persistent
            ).apply {
                description = "Shows live GPS broadcast status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        // Tap notification → opens app
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // SOS button in notification
        val sosIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LocationForegroundService::class.java).apply { action = ACTION_SOS },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Stop button in notification
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, LocationForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛰️ QuantumGPS — Live")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)               // Cannot be swiped away
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_delete, "🚨 SOS", sosIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun formatUptime(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
    }
}
