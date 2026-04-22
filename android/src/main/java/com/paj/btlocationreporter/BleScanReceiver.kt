package com.paj.btlocationreporter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.paj.btlocationreporter.ConfigStore

/**
 * Receives BLE scan matches via PendingIntent to wake up the app when a target device is detected.
 */
class BleScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BtLocationReporter", "[BleScanReceiver] BLE scan match received via PendingIntent")
        val configJson = ConfigStore.getConfig(context)
        if (configJson.isNullOrBlank()) {
            Log.w("BtLocationReporter", "[BleScanReceiver] No config found, aborting")
            return
        }

        val resultsExtra = intent.extras?.get("android.bluetooth.le.extra.LIST_SCAN_RESULT")
        val scanResults: List<android.bluetooth.le.ScanResult>? = when (resultsExtra) {
            is Array<*> -> {
                Log.d("BtLocationReporter", "[BleScanReceiver] LIST_SCAN_RESULT as Array, size=${(resultsExtra as Array<*>).size}")
                (resultsExtra as Array<*>).mapNotNull { it as? android.bluetooth.le.ScanResult }
            }
            is java.util.ArrayList<*> -> {
                Log.d("BtLocationReporter", "[BleScanReceiver] LIST_SCAN_RESULT as ArrayList, size=${(resultsExtra as java.util.ArrayList<*>).size}")
                (resultsExtra as java.util.ArrayList<*>).mapNotNull { it as? android.bluetooth.le.ScanResult }
            }
            else -> {
                Log.w("BtLocationReporter", "[BleScanReceiver] LIST_SCAN_RESULT is null or unknown type: ${resultsExtra?.javaClass}")
                null
            }
        }

        if (scanResults != null) {
            for (scanResult in scanResults) {
                val deviceId = scanResult.device?.address ?: continue
                Log.i("BtLocationReporter", "[BleScanReceiver] Detected deviceId=$deviceId")
                // Validar que el dispositivo esté en la config persistida
                if (configJson.contains(deviceId)) {
                    Log.i("BtLocationReporter", "[BleScanReceiver] Device $deviceId is in config, checking service state...")
                    // Evitar bucle: solo lanzar el servicio si no está corriendo
                    if (!BtLocationReporterService.isRunning) {
                        Log.i("BtLocationReporter", "[BleScanReceiver] BtLocationReporterService NOT running, starting service...")
                        val serviceIntent = Intent(context, BtLocationReporterService::class.java).apply {
                            action = BtLocationReporterService.ACTION_START
                            putExtra("config_json", configJson)
                        }

                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                        } catch (e: Exception) {
                            Log.e("BtLocationReporter", "[BleScanReceiver] Failed to start service: ${e.message}")
                        }
                    } else {
                        Log.i("BtLocationReporter", "[BleScanReceiver] BtLocationReporterService already running, not starting again.")
                    }
                    break
                } else {
                    Log.d("BtLocationReporter", "[BleScanReceiver] Device $deviceId not in config, ignoring.")
                }
            }
        } else {
            Log.w("BtLocationReporter", "[BleScanReceiver] scanResults is null, nothing to process")
        }
    }
}
