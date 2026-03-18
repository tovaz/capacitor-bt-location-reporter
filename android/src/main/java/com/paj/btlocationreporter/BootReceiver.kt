package com.paj.btlocationreporter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receives BOOT_COMPLETED so the service can restart automatically
 * after the device reboots — IF it was running before the reboot.
 *
 * The host app must call [BtLocationReporter.start()] at least once
 * for the preferences to be saved. The receiver reads them and relaunches the service.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("bt_location_reporter", Context.MODE_PRIVATE)
        val wasRunning = prefs.getBoolean("was_running", false)
        if (!wasRunning) return

        Log.i("BtLocationReporter", "Device rebooted — restarting background service")

        val serviceIntent = Intent(context, BtLocationReporterService::class.java).apply {
            action = BtLocationReporterService.ACTION_START
            putStringArrayListExtra(
                BtLocationReporterService.EXTRA_DEVICE_IDS,
                ArrayList(prefs.getStringSet("device_ids", emptySet()) ?: emptySet())
            )
            putExtra(BtLocationReporterService.EXTRA_ENDPOINT,    prefs.getString("endpoint", ""))
            putExtra(BtLocationReporterService.EXTRA_AUTH_TOKEN,  prefs.getString("auth_token", ""))
            putExtra(BtLocationReporterService.EXTRA_INTERVAL_MS, prefs.getLong("interval_ms", 30_000L))
            putExtra(BtLocationReporterService.EXTRA_NOTIF_TITLE, prefs.getString("notif_title", "BT Location Reporter"))
            putExtra(BtLocationReporterService.EXTRA_NOTIF_TEXT,  prefs.getString("notif_text", "Tracking location in background…"))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
