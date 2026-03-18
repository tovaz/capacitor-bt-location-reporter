package com.paj.btlocationreporter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "BtLocationReporter",
    permissions = [
        Permission(
            strings = [Manifest.permission.ACCESS_FINE_LOCATION],
            alias = "location"
        ),
        Permission(
            strings = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN],
            alias = "bluetooth"
        )
    ]
)
class BtLocationReporterPlugin : Plugin() {

    // Keeps a pending call while we wait for permission grants
    private var pendingStartCall: PluginCall? = null

    // ── Listener keys ──────────────────────────────────────────────────────

    companion object {
        const val EVENT_LOCATION_REPORT = "locationReport"
        const val EVENT_BLE_CONNECTION   = "bleConnection"

        // Shared reference so the ForegroundService can fire events back to JS
        var instance: BtLocationReporterPlugin? = null
    }

    override fun load() {
        instance = this
    }

    // ── Plugin methods ─────────────────────────────────────────────────────

    @PluginMethod
    fun start(call: PluginCall) {
        // 'devices' is an array of { bleDeviceId, pajDeviceId } objects
        val devicesArray = call.getArray("devices") ?: run {
            call.reject("devices array is required"); return
        }
        val endpoint = call.getString("reportEndpoint") ?: run {
            call.reject("reportEndpoint is required"); return
        }

        // Extract BLE IDs for the connection manager
        val bleIds = (0 until devicesArray.length()).mapNotNull { i ->
            runCatching { devicesArray.getJSONObject(i).getString("bleDeviceId") }.getOrNull()
        }

        if (!hasRequiredPermissions()) {
            pendingStartCall = call
            requestAllPermissions(call, "onPermissionsResult")
            return
        }

        launchService(
            deviceIds   = bleIds,
            devicesJson = devicesArray.toString(),
            endpoint    = endpoint,
            authToken   = call.getString("authToken"),
            intervalMs  = call.getLong("reportIntervalMs") ?: 30_000L,
            notifTitle  = call.getString("notificationTitle") ?: "BT Location Reporter",
            notifText   = call.getString("notificationText") ?: "Tracking location in background…",
            extraJson   = call.getObject("extraPayloadFields")?.toString() ?: "{}"
        )
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        val intent = Intent(context, BtLocationReporterService::class.java).apply {
            action = BtLocationReporterService.ACTION_STOP
        }
        context.stopService(intent)
        call.resolve()
    }

    @PluginMethod
    fun isRunning(call: PluginCall) {
        val result = JSObject()
        result.put("running", BtLocationReporterService.isRunning)
        call.resolve(result)
    }

    @PluginMethod
    fun addDevices(call: PluginCall) {
        val devicesArray = call.getArray("devices") ?: run { call.reject("devices is required"); return }
        val entries = (0 until devicesArray.length()).mapNotNull { i ->
            runCatching {
                val obj = devicesArray.getJSONObject(i)
                obj.getString("bleDeviceId") to obj.getString("pajDeviceId")
            }.getOrNull()
        }.toMap()
        BtLocationReporterService.pendingCommand = BtLocationReporterService.Command.AddDevices(entries)
        call.resolve()
    }

    @PluginMethod
    fun removeDevices(call: PluginCall) {
        val devicesArray = call.getArray("devices") ?: run { call.reject("devices is required"); return }
        val bleIds = (0 until devicesArray.length()).mapNotNull { i ->
            runCatching { devicesArray.getJSONObject(i).getString("bleDeviceId") }.getOrNull()
        }
        BtLocationReporterService.pendingCommand = BtLocationReporterService.Command.RemoveDevices(bleIds)
        call.resolve()
    }

    // ── Permission callback ────────────────────────────────────────────────

    @PermissionCallback
    private fun onPermissionsResult(call: PluginCall) {
        if (hasRequiredPermissions()) {
            // Re-invoke start with the saved call
            pendingStartCall?.let { start(it) }
        } else {
            call.reject("Required permissions were not granted")
        }
        pendingStartCall = null
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private fun hasRequiredPermissions(): Boolean {
        val locationOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val bleOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)    == PackageManager.PERMISSION_GRANTED
        } else true

        return locationOk && bleOk
    }

    private fun launchService(
        deviceIds: List<String>,
        devicesJson: String,
        endpoint: String,
        authToken: String?,
        intervalMs: Long,
        notifTitle: String,
        notifText: String,
        extraJson: String,
    ) {
        val intent = Intent(context, BtLocationReporterService::class.java).apply {
            action = BtLocationReporterService.ACTION_START
            putStringArrayListExtra(BtLocationReporterService.EXTRA_DEVICE_IDS, ArrayList(deviceIds))
            putExtra(BtLocationReporterService.EXTRA_DEVICES_JSON, devicesJson)
            putExtra(BtLocationReporterService.EXTRA_ENDPOINT,    endpoint)
            putExtra(BtLocationReporterService.EXTRA_AUTH_TOKEN,  authToken ?: "")
            putExtra(BtLocationReporterService.EXTRA_INTERVAL_MS, intervalMs)
            putExtra(BtLocationReporterService.EXTRA_NOTIF_TITLE, notifTitle)
            putExtra(BtLocationReporterService.EXTRA_NOTIF_TEXT,  notifText)
            putExtra(BtLocationReporterService.EXTRA_EXTRA_JSON,  extraJson)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // ── Event helpers (called from Service) ───────────────────────────────

    fun notifyLocationReport(payload: JSObject, httpStatus: Int, success: Boolean) {
        val event = JSObject()
        event.put("payload",    payload)
        event.put("httpStatus", httpStatus)
        event.put("success",    success)
        notifyListeners(EVENT_LOCATION_REPORT, event)
    }

    fun notifyBleConnection(deviceId: String, connected: Boolean) {
        val event = JSObject()
        event.put("deviceId",  deviceId)
        event.put("connected", connected)
        notifyListeners(EVENT_BLE_CONNECTION, event)
    }
}
