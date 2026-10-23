package com.antigravity.tracker

import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FcmService — Strategy 5: FCM Silent Push (Remote Admin Control)
 *
 * Allows the admin dashboard to remotely:
 *  - Wake up a sleeping device        (command: "start_gps")
 *  - Stop GPS on any device           (command: "stop_gps")
 *  - Trigger SOS check               (command: "ping")
 *
 * Admin sends FCM via Firebase Console or server-side with:
 *   data: { "command": "start_gps", "room_id": "xyz", "user_name": "John" }
 */
class FcmService : FirebaseMessagingService() {

    companion object {
        const val TAG = "QuantumGPS_FCM"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data    = remoteMessage.data
        val command = data["command"] ?: return

        Log.i(TAG, "FCM command received: $command")

        when (command) {
            "start_gps" -> {
                Log.i(TAG, "Remote START command → launching service")
                val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)

                val intent = Intent(this, LocationForegroundService::class.java).apply {
                    putExtra("server_url", data["server_url"]
                        ?: prefs.getString(MainActivity.PREF_SERVER_URL, "http://10.0.2.2:8080"))
                    putExtra("room_id",   data["room_id"]
                        ?: prefs.getString(MainActivity.PREF_ROOM_ID, "default-room"))
                    putExtra("user_name", data["user_name"]
                        ?: prefs.getString(MainActivity.PREF_USER_NAME, "QuantumUser"))
                }
                ContextCompat.startForegroundService(this, intent)
            }

            "stop_gps" -> {
                Log.i(TAG, "Remote STOP command → stopping service")
                stopService(Intent(this, LocationForegroundService::class.java))
            }

            "sos" -> {
                Log.i(TAG, "Remote SOS command → triggering SOS")
                val intent = Intent(this, LocationForegroundService::class.java).apply {
                    action = LocationForegroundService.ACTION_SOS
                }
                ContextCompat.startForegroundService(this, intent)
            }

            "ping" -> {
                Log.i(TAG, "Ping received. Service running: ${LocationForegroundService.isRunning}")
                // Could respond with device status via MQTT here
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM token: $token")
        // TODO: Send token to your server so admin can push to this device
        // POST to $serverUrl/api/register_device with { token, clientId }
    }
}
