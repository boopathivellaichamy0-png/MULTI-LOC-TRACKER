package com.antigravity.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BootReceiver — Strategy 2: Boot Auto-Start
 *
 * Listens for BOOT_COMPLETED broadcast.
 * If GPS was active before shutdown, auto-restarts the foreground service.
 * Works for: normal boot, quick boot (HTC), and MIUI power-on.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "QuantumGPS_Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (intent.action !in validActions) return

        Log.i(TAG, "Boot detected — checking auto-start preference")

        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val shouldAutoStart = prefs.getBoolean(MainActivity.PREF_AUTO_START, false)

        if (shouldAutoStart) {
            Log.i(TAG, "Auto-start enabled → launching LocationForegroundService")

            val serviceIntent = Intent(context, LocationForegroundService::class.java).apply {
                putExtra("server_url", prefs.getString(MainActivity.PREF_SERVER_URL, "http://10.0.2.2:8080"))
                putExtra("room_id",   prefs.getString(MainActivity.PREF_ROOM_ID, "default-room"))
                putExtra("user_name", prefs.getString(MainActivity.PREF_USER_NAME, "QuantumUser"))
            }

            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            Log.i(TAG, "Auto-start disabled — skipping service start")
        }
    }
}
