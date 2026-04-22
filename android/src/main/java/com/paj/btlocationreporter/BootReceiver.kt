package com.paj.btlocationreporter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.paj.btlocationreporter.ConfigStore

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

        val configJson = ConfigStore.getConfig(context)
        if (configJson.isNullOrBlank()) return
        Log.i("BtLocationReporter", "Device rebooted — restarting background service with persisted config")
        val serviceIntent = Intent(context, BtLocationReporterService::class.java).apply {
            action = BtLocationReporterService.ACTION_START
            putExtra("config_json", configJson)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("BtLocationReporter", "[BootReceiver] Failed to start service: ${e.message}")
        }
    }
}
