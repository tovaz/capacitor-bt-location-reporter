package com.paj.btlocationreporter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

import com.paj.btlocationreporter.LinkedDeviceStore
import com.paj.btlocationreporter.ConfigStore
import org.json.JSONObject
import java.util.UUID

private const val LOCATION_PERMISSION_REQUEST_CODE = 12345
private const val BT_PERMISSION_REQUEST_CODE = 12346

// Los permisos NO se declaran aquí en la anotación de Capacitor para evitar
// que el framework los solicite automáticamente al arrancar la app.
// Se piden manualmente solo cuando el usuario llama a start().
@CapacitorPlugin(name = "BtLocationReporter")
class BtLocationReporterPlugin : Plugin() {

    private var pendingStartCall: PluginCall? = null
    private var pendingLocationCall: PluginCall? = null

    companion object {
        const val EVENT_LOCATION_REPORT = "locationReport"
        const val EVENT_BLE_CONNECTION  = "bleConnection"
        const val EVENT_LOCATION_PERMISSION_REQUIRED = "locationPermissionRequired"

        var instance: BtLocationReporterPlugin? = null
    }

    override fun load() {
        instance = this
        FileLogger.init(context)
        LOG("[BtLocationReporterPlugin] Loaded")
        LOG("[BtLocationReporterPlugin] Log file: ${FileLogger.getLogPath()}")
    }

    // ── Plugin methods ─────────────────────────────────────────────────────

    @PluginMethod
    fun start(call: PluginCall) {
        LOG_INFO("[BtLocationReporterPlugin] start() called — v2026-04-22 (pide BT+ubicacion antes de lanzar servicio)")
        
        val devicesArray = call.getArray("devices") ?: run {
            LOG_ERROR("[BtLocationReporterPlugin] devices array is required")
            call.reject("devices array is required"); return
        }
        val endpoint = call.getString("reportEndpoint") ?: run {
            LOG_ERROR("[BtLocationReporterPlugin] reportEndpoint is required")
            call.reject("reportEndpoint is required"); return
        }

        // Guardar la configuración completa como JSON
        val configJson = call.data.toString()
        ConfigStore.saveConfig(context, configJson)
        // (Opcional: mantener LinkedDeviceStore para compatibilidad, pero ya no es necesario)
        
        // Parse debug mode
        val debug = call.getBoolean("debug") ?: false
        FileLogger.debugEnabled = debug
        
        // Parse notification texts
        val textsObj = call.getObject("texts")
        val textConnectedHeader = textsObj?.getString("connectedHeader") ?: "Device connected"
        val textConnected = textsObj?.getString("connected") ?: "{device} connected via Bluetooth, power saving activated"
        val textTrackerHeader = textsObj?.getString("trackerHeader") ?: "BT Location Reporter"
        val textTracker = textsObj?.getString("tracker") ?: "Tracking location in background\u2026"

        // Obtener lista de bleIds
        val bleIds = (0 until devicesArray.length()).mapNotNull { i ->
            runCatching { devicesArray.getJSONObject(i).getString("bleDeviceId") }.getOrNull()
        }

        LOG("[BtLocationReporterPlugin] Config: endpoint=$endpoint, devices=${bleIds.size}, debug=$debug")

        // Persist linked device IDs for BLE scan + PendingIntent in background
        LinkedDeviceStore.saveLinkedDevices(context, bleIds.toSet())

        // 1. Verificar permisos BT (Android 12+) — nunca al arrancar, solo en start().
        if (!checkBluetoothPermissionsGranted()) {
            pendingStartCall = call
            bridge.saveCall(call)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                    BT_PERMISSION_REQUEST_CODE
                )
            }
            return
        }

        // 2. Verificar permiso de ubicación — obligatorio en Android 14+ para arrancar un
        //    foreground service con foregroundServiceType="location".
        if (!checkLocationPermissionGranted()) {
            pendingStartCall = call
            bridge.saveCall(call)
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        launchService(
            deviceIds   = bleIds,
            devicesJson = devicesArray.toString(),
            endpoint    = endpoint,
            authToken   = call.getString("authToken"),
            intervalMs  = call.getLong("reportIntervalMs") ?: 30_000L,
            notifTitle  = textTrackerHeader,
            notifText   = textTracker,
            extraJson   = call.getObject("extraPayloadFields")?.toString() ?: "{}",
            debug       = debug,
            textConnectedHeader = textConnectedHeader,
            textConnected = textConnected,
            configJson = configJson
        )
        LOG_INFO("[BtLocationReporterPlugin] start() completed")
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        LOG("[BtLocationReporterPlugin] stop() called")
        val intent = Intent(context, BtLocationReporterService::class.java).apply {
            action = BtLocationReporterService.ACTION_STOP
        }
        context.stopService(intent)
        // Borrar configuración persistente
        ConfigStore.clearConfig(context)
        LinkedDeviceStore.clearLinkedDevices(context)
        call.resolve()
    }

    @PluginMethod
    fun isRunning(call: PluginCall) {
        val running = BtLocationReporterService.isRunning
        LOG("[BtLocationReporterPlugin] isRunning() = $running")
        call.resolve(JSObject().put("running", running))
    }

    @PluginMethod
    fun addDevices(call: PluginCall) {
        val devicesArray = call.getArray("devices") ?: run { 
            call.reject("devices is required"); return 
        }
        
        val entries = mutableMapOf<String, String>()
        val commands = mutableMapOf<String, Pair<BleCommand?, BleCommand?>>()
        
        for (i in 0 until devicesArray.length()) {
            runCatching {
                val obj = devicesArray.getJSONObject(i)
                val bleId = obj.getString("bleDeviceId")
                val pajId = obj.getString("pajDeviceId")
                entries[bleId] = pajId
                
                val onConnect = parseCommand(obj.optJSONObject("onConnectCommand"))
                val onDisconnect = parseCommand(obj.optJSONObject("onDisconnectCommand"))
                commands[bleId] = onConnect to onDisconnect
            }
        }
        
        LOG("[BtLocationReporterPlugin] addDevices(): ${entries.size} devices")
        BtLocationReporterService.pendingCommand = BtLocationReporterService.Command.AddDevices(entries, commands)

        // Also update linked-device store for BLE background scanning
        val current = LinkedDeviceStore.getLinkedDevices(context).toMutableSet()
        current.addAll(entries.keys)
        LinkedDeviceStore.saveLinkedDevices(context, current)

        call.resolve()
    }

    @PluginMethod
    fun removeDevices(call: PluginCall) {
        val devicesArray = call.getArray("devices") ?: run { 
            call.reject("devices is required"); return 
        }
        val bleIds = (0 until devicesArray.length()).mapNotNull { i ->
            runCatching { devicesArray.getJSONObject(i).getString("bleDeviceId") }.getOrNull()
        }
        LOG("[BtLocationReporterPlugin] removeDevices(): ${bleIds.size} devices")
        BtLocationReporterService.pendingCommand = BtLocationReporterService.Command.RemoveDevices(bleIds)

        // Also update linked-device store for BLE background scanning
        val current = LinkedDeviceStore.getLinkedDevices(context).toMutableSet()
        current.removeAll(bleIds)
        if (current.isEmpty()) {
            LinkedDeviceStore.clearLinkedDevices(context)
        } else {
            LinkedDeviceStore.saveLinkedDevices(context, current)
        }

        call.resolve()
    }

    @PluginMethod
    fun getLogPath(call: PluginCall) {
        call.resolve(JSObject().put("path", FileLogger.getLogPath()))
    }

    @PluginMethod
    fun getLogs(call: PluginCall) {
        call.resolve(JSObject().put("logs", FileLogger.getLogs()))
    }

    @PluginMethod
    fun requestLocationPermission(call: PluginCall) {
        LOG("[BtLocationReporterPlugin] requestLocationPermission() called")
        if (checkLocationPermissionGranted()) {
            LOG("[BtLocationReporterPlugin] Location permission already granted")
            BtLocationReporterService.onLocationPermissionGranted()
            call.resolve(JSObject().put("granted", true))
            return
        }
        
        // Store the call and request permission manually
        pendingLocationCall = call
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }
    
    override fun handleRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            BT_PERMISSION_REQUEST_CODE -> {
                val granted = checkBluetoothPermissionsGranted()
                LOG("[BtLocationReporterPlugin] BT permission result: granted=$granted")
                val savedCall = pendingStartCall
                pendingStartCall = null
                if (savedCall != null) {
                    bridge.releaseCall(savedCall)
                    if (granted) {
                        start(savedCall)
                    } else {
                        savedCall.reject("Bluetooth permissions were not granted")
                    }
                }
            }
            LOCATION_PERMISSION_REQUEST_CODE -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                LOG("[BtLocationReporterPlugin] Location permission result: granted=$granted")
                if (granted) {
                    BtLocationReporterService.onLocationPermissionGranted()
                }
                // Si start() estaba esperando este permiso para lanzar el servicio, reintentarlo.
                val savedStartCall = pendingStartCall
                if (savedStartCall != null) {
                    pendingStartCall = null
                    bridge.releaseCall(savedStartCall)
                    if (granted) {
                        start(savedStartCall)
                    } else {
                        savedStartCall.reject("Location permission was not granted")
                    }
                }
                // Resolver llamada explícita a requestLocationPermission() si la hubiera.
                pendingLocationCall?.resolve(JSObject().put("granted", granted))
                pendingLocationCall = null
            }
        }
    }

    @PluginMethod
    fun hasLocationPermission(call: PluginCall) {
        val granted = checkLocationPermissionGranted()
        call.resolve(JSObject().put("granted", granted))
    }

    // ── Helper methods ─────────────────────────────────────────────────────

    private fun parseCommand(json: JSONObject?): BleCommand? {
        if (json == null) return null
        return try {
            BleCommand(
                name = json.getString("name"),
                serviceUuid = json.optString("serviceUuid").takeIf { it.isNotEmpty() }
                    ?: json.optString("service_uuid").takeIf { it.isNotEmpty() }
                    ?: return null,
                characteristicUuid = json.optString("characteristicUuid").takeIf { it.isNotEmpty() }
                    ?: json.optString("characteristic_uuid").takeIf { it.isNotEmpty() }
                    ?: return null,
                value = json.getString("value")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun checkBluetoothPermissionsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkLocationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
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
        debug: Boolean,
        textConnectedHeader: String,
        textConnected: String,
        configJson: String,
    ) {
        val intent = Intent(context, BtLocationReporterService::class.java).apply {
            action = BtLocationReporterService.ACTION_START
            putStringArrayListExtra(BtLocationReporterService.EXTRA_DEVICE_IDS, ArrayList(deviceIds))
            putExtra(BtLocationReporterService.EXTRA_DEVICES_JSON, devicesJson)
            putExtra(BtLocationReporterService.EXTRA_ENDPOINT, endpoint)
            putExtra(BtLocationReporterService.EXTRA_AUTH_TOKEN, authToken ?: "")
            putExtra(BtLocationReporterService.EXTRA_INTERVAL_MS, intervalMs)
            putExtra(BtLocationReporterService.EXTRA_NOTIF_TITLE, notifTitle)
            putExtra(BtLocationReporterService.EXTRA_NOTIF_TEXT, notifText)
            putExtra(BtLocationReporterService.EXTRA_EXTRA_JSON, extraJson)
            putExtra(BtLocationReporterService.EXTRA_DEBUG, debug)
            putExtra(BtLocationReporterService.EXTRA_TEXT_CONNECTED_HEADER, textConnectedHeader)
            putExtra(BtLocationReporterService.EXTRA_TEXT_CONNECTED, textConnected)
            putExtra("config_json", configJson)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LOG("[BtLocationReporterPlugin] launchService: startForegroundService")
            context.startForegroundService(intent)
        } else {
            LOG("[BtLocationReporterPlugin] launchService: startService")
            context.startService(intent)
        }
    }

    // ── Event helpers (called from Service) ───────────────────────────────

    fun notifyLocationReport(payload: JSObject, httpStatus: Int, success: Boolean) {
        notifyListeners(EVENT_LOCATION_REPORT, JSObject().apply {
            put("payload", payload)
            put("httpStatus", httpStatus)
            put("success", success)
        })
    }

    fun notifyBleConnection(deviceId: String, connected: Boolean) {
        LOG("[BtLocationReporterPlugin] bleConnection: $deviceId connected=$connected")
        notifyListeners(EVENT_BLE_CONNECTION, JSObject().apply {
            put("deviceId", deviceId)
            put("connected", connected)
        })
    }

    fun notifyLocationPermissionRequired() {
        LOG("[BtLocationReporterPlugin] Emitting locationPermissionRequired event")
        notifyListeners(EVENT_LOCATION_PERMISSION_REQUIRED, JSObject().apply {
            put("reason", "First BLE device connected - location permission needed to start tracking")
        })
    }

    /**
     * Called by the service when first BLE device connects.
     * Automatically requests location permission from the user.
     */
    fun requestLocationPermissionFromService() {
        LOG("[BtLocationReporterPlugin] requestLocationPermissionFromService() - auto-requesting location permission")
        
        if (checkLocationPermissionGranted()) {
            LOG("[BtLocationReporterPlugin] Location permission already granted")
            BtLocationReporterService.onLocationPermissionGranted()
            return
        }
        
        // Request permission - result will be handled by handleRequestPermissionsResult
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    @PluginMethod
    fun writeWithoutResponse(call: PluginCall) {
        val deviceId = call.getString("deviceId")
            ?: run { call.reject("deviceId is required"); return }
        val service = call.getString("service")
            ?: run { call.reject("service is required"); return }
        val characteristic = call.getString("characteristic")
            ?: run { call.reject("characteristic is required"); return }
        val rawValue = call.getArray("value")
            ?: run { call.reject("value (byte array) is required"); return }

        val serviceUuid = try { UUID.fromString(service) }
            catch (e: Exception) { call.reject("Invalid service UUID: $service"); return }
        val charUuid = try { UUID.fromString(characteristic) }
            catch (e: Exception) { call.reject("Invalid characteristic UUID: $characteristic"); return }

        val bytes = ByteArray(rawValue.length()) { i -> rawValue.getInt(i).and(0xFF).toByte() }

        LOG("[BtLocationReporterPlugin] writeWithoutResponse: device=$deviceId service=$service char=$characteristic bytes=${bytes.size}")

        BtLocationReporterService.writeWithoutResponse(deviceId, serviceUuid, charUuid, bytes) { error ->
            if (error != null) {
                LOG_ERROR("[BtLocationReporterPlugin] writeWithoutResponse failed: ${error.message}")
                call.reject(error.message ?: "Write failed")
            } else {
                LOG("[BtLocationReporterPlugin] writeWithoutResponse succeeded")
                call.resolve()
            }
        }
    }
}
