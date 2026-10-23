package com.antigravity.tracker

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * GPSWatchdogWorker — Strategy 4: WorkManager Watchdog
 *
 * Runs every 15 minutes via WorkManager.
 * Checks if LocationForegroundService is alive.
 * If dead → automatically resurrects it.
 *
 * This survives:
 *  - Aggressive ROM battery savers (Xiaomi, Huawei, Samsung)
 *  - Low memory kills
 *  - ANR-triggered kills
 */
class GPSWatchdogWorker(
    private val ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    companion object {
        const val TAG = "QuantumGPS_Watchdog"
    }

    override fun doWork(): Result {
        Log.i(TAG, "Watchdog tick — checking service health")

        val prefs = ctx.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val shouldBeRunning = prefs.getBoolean(MainActivity.PREF_AUTO_START, false)

        if (!shouldBeRunning) {
            Log.i(TAG, "Auto-start is off — watchdog idle")
            return Result.success()
        }

        if (!LocationForegroundService.isRunning) {
            Log.w(TAG, "⚠️ Service is DEAD — resurrecting now!")

            val intent = Intent(ctx, LocationForegroundService::class.java).apply {
                putExtra("server_url", prefs.getString(MainActivity.PREF_SERVER_URL, "http://10.0.2.2:8080"))
                putExtra("room_id",   prefs.getString(MainActivity.PREF_ROOM_ID, "default-room"))
                putExtra("user_name", prefs.getString(MainActivity.PREF_USER_NAME, "QuantumUser"))
            }
            ContextCompat.startForegroundService(ctx, intent)

            Log.i(TAG, "✅ Service resurrected successfully")
        } else {
            Log.i(TAG, "✅ Service is alive and healthy")
        }

        return Result.success()
    }
}
