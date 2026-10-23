package com.antigravity.tracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * MainActivity — QuantumGPS Tracker
 *
 * Strategies implemented:
 *  1. Foreground Service  → immortal GPS engine
 *  2. Boot Auto-Start     → auto-resumes after reboot
 *  3. Doze Mode Bypass    → battery optimization exemption
 *  4. WorkManager Watchdog→ revives if killed (in LocationForegroundService)
 *  5. FCM Silent Push     → admin remote wake/stop
 *  6. Fused Location API  → best accuracy (in service)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val PREF_NAME         = "quantum_gps_prefs"
        const val PREF_SERVER_URL   = "server_url"
        const val PREF_ROOM_ID      = "room_id"
        const val PREF_USER_NAME    = "user_name"
        const val PREF_AUTO_START   = "auto_start"
        const val DEFAULT_SERVER    = "http://10.0.2.2:8080"  // emulator default
        const val REQUEST_PERMS     = 100
    }

    private lateinit var prefs: SharedPreferences

    // UI
    private lateinit var btnToggle:     Button
    private lateinit var btnSettings:   ImageButton
    private lateinit var tvStatus:      TextView
    private lateinit var tvCoords:      TextView
    private lateinit var tvSpeed:       TextView
    private lateinit var tvAccuracy:    TextView
    private lateinit var tvUptime:      TextView
    private lateinit var tvRoom:        TextView
    private lateinit var switchAutoStart: Switch
    private lateinit var statusDot:     View
    private lateinit var cardTelemetry: View

    private var isTracking = false

    // Receiver for live telemetry from service
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val lat      = intent.getDoubleExtra("lat", 0.0)
            val lng      = intent.getDoubleExtra("lng", 0.0)
            val speed    = intent.getFloatExtra("speed", 0f)
            val accuracy = intent.getFloatExtra("accuracy", 0f)
            val uptime   = intent.getLongExtra("uptime_seconds", 0L)
            val status   = intent.getStringExtra("status") ?: "active"

            updateTelemetryUI(lat, lng, speed, accuracy, uptime, status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        bindViews()
        setupListeners()
        loadSettings()
        checkAndRequestPermissions()

        // Auto-resume if was tracking before
        if (prefs.getBoolean(PREF_AUTO_START, false)) {
            if (hasLocationPermission()) startTracking()
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(locationReceiver, IntentFilter("LOCATION_UPDATE"))
        isTracking = LocationForegroundService.isRunning
        updateToggleUI()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationReceiver)
    }

    private fun bindViews() {
        btnToggle      = findViewById(R.id.btnToggle)
        btnSettings    = findViewById(R.id.btnSettings)
        tvStatus       = findViewById(R.id.tvStatus)
        tvCoords       = findViewById(R.id.tvCoords)
        tvSpeed        = findViewById(R.id.tvSpeed)
        tvAccuracy     = findViewById(R.id.tvAccuracy)
        tvUptime       = findViewById(R.id.tvUptime)
        tvRoom         = findViewById(R.id.tvRoom)
        switchAutoStart= findViewById(R.id.switchAutoStart)
        statusDot      = findViewById(R.id.statusDot)
        cardTelemetry  = findViewById(R.id.cardTelemetry)
    }

    private fun setupListeners() {
        btnToggle.setOnClickListener {
            if (isTracking) confirmStop() else startTracking()
        }

        btnSettings.setOnClickListener { showSettingsDialog() }

        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_START, checked).apply()
        }
    }

    private fun loadSettings() {
        switchAutoStart.isChecked = prefs.getBoolean(PREF_AUTO_START, false)
        tvRoom.text = "Room: ${prefs.getString(PREF_ROOM_ID, "default-room")}"
    }

    // ═══════════════════════════════════════════════
    //  TRACKING CONTROL
    // ═══════════════════════════════════════════════

    private fun startTracking() {
        if (!hasLocationPermission()) {
            checkAndRequestPermissions()
            return
        }
        requestBatteryOptimizationExemption()

        val intent = Intent(this, LocationForegroundService::class.java).apply {
            putExtra("server_url", prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER))
            putExtra("room_id",    prefs.getString(PREF_ROOM_ID, "default-room"))
            putExtra("user_name",  prefs.getString(PREF_USER_NAME, "QuantumUser"))
        }
        ContextCompat.startForegroundService(this, intent)

        isTracking = true
        updateToggleUI()
        cardTelemetry.visibility = View.VISIBLE
        Toast.makeText(this, "📡 GPS Foreground Service started!", Toast.LENGTH_SHORT).show()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Stop Sharing?")
            .setMessage("The admin will be notified that you stopped sharing your location.")
            .setPositiveButton("Stop") { _, _ -> stopTracking() }
            .setNegativeButton("Keep Sharing", null)
            .show()
    }

    private fun stopTracking() {
        stopService(Intent(this, LocationForegroundService::class.java))
        isTracking = false
        updateToggleUI()
        tvStatus.text = "🔴 Stopped"
        cardTelemetry.visibility = View.GONE
    }

    private fun updateToggleUI() {
        if (isTracking) {
            btnToggle.text = "🛑 Stop Sharing"
            btnToggle.setBackgroundColor(getColor(R.color.danger))
            statusDot.setBackgroundResource(R.drawable.dot_green)
            tvStatus.text = "🟢 Broadcasting Live"
        } else {
            btnToggle.text = "📡 Start Live GPS"
            btnToggle.setBackgroundColor(getColor(R.color.primary))
            statusDot.setBackgroundResource(R.drawable.dot_grey)
            tvStatus.text = "⚪ Idle — Tap to Start"
        }
    }

    // ═══════════════════════════════════════════════
    //  TELEMETRY UPDATE (from Service broadcast)
    // ═══════════════════════════════════════════════

    private fun updateTelemetryUI(lat: Double, lng: Double, speed: Float,
                                  accuracy: Float, uptime: Long, status: String) {
        tvCoords.text   = "📍 %.6f, %.6f".format(lat, lng)
        tvSpeed.text    = "⚡ Speed: ${(speed * 3.6f).toInt()} km/h"
        tvAccuracy.text = "🎯 Accuracy: ±${accuracy.toInt()}m"

        val h = uptime / 3600
        val m = (uptime % 3600) / 60
        val s = uptime % 60
        tvUptime.text = "⏱️ Uptime: %02d:%02d:%02d".format(h, m, s)

        if (status == "SOS") {
            tvStatus.text = "🚨 SOS ACTIVE"
            tvStatus.setTextColor(getColor(R.color.danger))
        }
    }

    // ═══════════════════════════════════════════════
    //  STRATEGY 3: DOZE MODE BYPASS
    // ═══════════════════════════════════════════════

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Device doesn't support this (some ROMs)
            }
        }
    }

    // ═══════════════════════════════════════════════
    //  SETTINGS DIALOG
    // ═══════════════════════════════════════════════

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etServer   = view.findViewById<EditText>(R.id.etServerUrl)
        val etRoom     = view.findViewById<EditText>(R.id.etRoomId)
        val etName     = view.findViewById<EditText>(R.id.etUserName)

        etServer.setText(prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER))
        etRoom.setText(prefs.getString(PREF_ROOM_ID, "default-room"))
        etName.setText(prefs.getString(PREF_USER_NAME, "QuantumUser"))

        AlertDialog.Builder(this)
            .setTitle("⚙️ Server Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(PREF_SERVER_URL, etServer.text.toString().trim())
                    .putString(PREF_ROOM_ID,    etRoom.text.toString().trim())
                    .putString(PREF_USER_NAME,  etName.text.toString().trim())
                    .apply()
                tvRoom.text = "Room: ${etRoom.text.toString().trim()}"
                Toast.makeText(this, "✅ Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═══════════════════════════════════════════════
    //  PERMISSIONS
    // ═══════════════════════════════════════════════

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        val permsNeeded = mutableListOf<String>()

        if (!hasLocationPermission())
            permsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permsNeeded.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)

        if (permsNeeded.isNotEmpty())
            ActivityCompat.requestPermissions(this, permsNeeded.toTypedArray(), REQUEST_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "✅ Permissions granted!", Toast.LENGTH_SHORT).show()
        }
    }
}
