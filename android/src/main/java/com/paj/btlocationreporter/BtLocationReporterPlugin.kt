package com.paj.btlocationreporter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

import com.paj.btlocationreporter.LinkedDeviceStore
import com.paj.btlocationreporter.ConfigStore
import com.paj.btlocationreporter.livetracking.LiveTrackingManager
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val LOCATION_PERMISSION_REQUEST_CODE = 12345
private const val BT_PERMISSION_REQUEST_CODE = 12346

// Los permisos NO se declaran aquí en la anotación de Capacitor para evitar
// que el framework los solicite automáticamente al arrancar la app.
// Se piden manualmente solo cuando el usuario llama a start().
@CapacitorPlugin(name = "BtLocationReporter")
class BtLocationReporterPlugin : Plugin() {

    private var pendingStartCall: PluginCall? = null
    private var pendingLocationCall: PluginCall? = null
    private val pluginScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var btPermissionContinuation: CancellableContinuation<Boolean>? = null
    private var locationPermissionContinuation: CancellableContinuation<Boolean>? = null

    companion object {
        const val EVENT_LOCATION_REPORT = "locationReport"
        const val EVENT_BLE_CONNECTION  = "bleConnection"
        const val EVENT_LOCATION_PERMISSION_REQUIRED = "locationPermissionRequired"
        const val EVENT_LIVE_TRACKING_STARTED = "liveTrackingStarted"
        const val EVENT_LIVE_TRACKING_STOPPED = "liveTrackingStopped"

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
        LOG_INFO("[BtLocationReporterPlugin] start() called")

        // Parsear todos los datos del call ANTES de entrar al flujo async,
        // para no depender de call.data en callbacks posteriores.
        val devicesArray = call.getArray("devices") ?: run {
            LOG_ERROR("[BtLocationReporterPlugin] devices array is required")
            call.reject("devices array is required"); return
        }
        val endpoint = call.getString("reportEndpoint") ?: run {
            LOG_ERROR("[BtLocationReporterPlugin] reportEndpoint is required")
            call.reject("reportEndpoint is required"); return
        }

        val configJson = call.data.toString()
        ConfigStore.saveConfig(context, configJson)

        val debug = call.getBoolean("debug") ?: false
        FileLogger.debugEnabled = debug

        val textsObj = call.getObject("texts")
        val textConnectedHeader = textsObj?.getString("connectedHeader") ?: "Device connected"
        val textConnected = textsObj?.getString("connected") ?: "{device} connected via Bluetooth, power saving activated"
        val textTrackerHeader = textsObj?.getString("trackerHeader") ?: "BT Location Reporter"
        val textTracker = textsObj?.getString("tracker") ?: "Tracking location in background\u2026"

        val devicesJsonStr = devicesArray.toString()
        val bleIds = (0 until devicesArray.length()).mapNotNull { i ->
            runCatching { devicesArray.getJSONObject(i).getString("bleDeviceId") }.getOrNull()
        }
        val authToken  = call.getString("authToken")
        val intervalMs = call.getLong("reportIntervalMs") ?: 30_000L
        val extraJson  = call.getObject("extraPayloadFields")?.toString() ?: "{}"

        LOG("[BtLocationReporterPlugin] Config: endpoint=$endpoint, devices=${bleIds.size}, debug=$debug")
        LinkedDeviceStore.saveLinkedDevices(context, bleIds.toSet())

        // Mantener el call vivo durante el flujo async de permisos.
        bridge.saveCall(call)

        pluginScope.launch {
            // 1. Permisos BT (Android 12+) — suspende aquí hasta que el usuario responda.
            if (!checkBluetoothPermissionsGranted()) {
                val granted = suspendCancellableCoroutine { cont ->
                    btPermissionContinuation = cont
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ActivityCompat.requestPermissions(
                            activity,
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                            BT_PERMISSION_REQUEST_CODE
                        )
                    } else {
                        cont.resume(true)
                    }
                }
                if (!granted) {
                    bridge.releaseCall(call)
                    call.reject("Bluetooth permissions were not granted")
                    return@launch
                }
            }

            // 2. Permiso de ubicación — suspende aquí hasta que el usuario responda.
            if (!checkLocationPermissionGranted()) {
                val granted = suspendCancellableCoroutine { cont ->
                    locationPermissionContinuation = cont
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        LOCATION_PERMISSION_REQUEST_CODE
                    )
                }
                if (!granted) {
                    bridge.releaseCall(call)
                    call.reject("Location permission was not granted")
                    return@launch
                }
            }

            // 3. Todos los permisos concedidos — lanzar el servicio.
            launchService(
                deviceIds   = bleIds,
                devicesJson = devicesJsonStr,
                endpoint    = endpoint,
                authToken   = authToken,
                intervalMs  = intervalMs,
                notifTitle  = textTrackerHeader,
                notifText   = textTracker,
                extraJson   = extraJson,
                debug       = debug,
                textConnectedHeader = textConnectedHeader,
                textConnected = textConnected,
                configJson  = configJson
            )
            LOG_INFO("[BtLocationReporterPlugin] start() completed")
            bridge.releaseCall(call)
            call.resolve()
        }
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        LOG("[BtLocationReporterPlugin] stop() called")
        // Limpiar calls pendientes para que handleRequestPermissionsResult
        // no relance start() fantasma después de un stop().
        pendingStartCall?.let { bridge.releaseCall(it) }
        pendingStartCall = null
        pendingLocationCall = null
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
                val cont = btPermissionContinuation
                if (cont != null) {
                    // Nuevo flujo: reanudar la coroutine de start() suspendida.
                    btPermissionContinuation = null
                    cont.resume(granted)
                } else {
                    // Flujo legacy (pendingStartCall) — por compatibilidad.
                    val savedCall = pendingStartCall
                    pendingStartCall = null
                    if (savedCall != null) {
                        if (granted) {
                            Handler(Looper.getMainLooper()).post { start(savedCall) }
                        } else {
                            bridge.releaseCall(savedCall)
                            savedCall.reject("Bluetooth permissions were not granted")
                        }
                    }
                }
            }
            LOCATION_PERMISSION_REQUEST_CODE -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                LOG("[BtLocationReporterPlugin] Location permission result: granted=$granted")
                if (granted) {
                    BtLocationReporterService.onLocationPermissionGranted()
                }
                val cont = locationPermissionContinuation
                if (cont != null) {
                    // Nuevo flujo: reanudar la coroutine de start() suspendida.
                    locationPermissionContinuation = null
                    cont.resume(granted)
                } else {
                    // Flujo legacy (pendingStartCall) — por compatibilidad.
                    val savedStartCall = pendingStartCall
                    if (savedStartCall != null) {
                        pendingStartCall = null
                        if (granted) {
                            Handler(Looper.getMainLooper()).post { start(savedStartCall) }
                        } else {
                            bridge.releaseCall(savedStartCall)
                            savedStartCall.reject("Location permission was not granted")
                        }
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

    // ── Live tracking ─────────────────────────────────────────────────────

    /**
     * Starts a temporary live tracking session for a specific pajDeviceId.
     * The session is kept in memory only and auto-expires after the
     * requested duration. Requires the background service to be running.
     */
    @PluginMethod
    fun startLiveTracking(call: PluginCall) {
        try {
            if (!BtLocationReporterService.isRunning) {
                call.reject("Service not running — call start() first")
                return
            }
            val pajId = readPajDeviceId(call)
            if (pajId.isNullOrBlank()) {
                call.reject("pajDeviceId is required")
                return
            }
            val intervalSec = readLongArg(call, "intervalSec") ?: 0L
            val durationSec = readLongArg(call, "durationSec") ?: 0L
            if (intervalSec <= 0L) {
                call.reject("intervalSec must be > 0")
                return
            }
            if (durationSec <= 0L) {
                call.reject("durationSec must be > 0")
                return
            }

            LOG("[BtLocationReporterPlugin] startLiveTracking pajDeviceId=$pajId intervalSec=$intervalSec durationSec=$durationSec")
            val session = LiveTrackingManager.start(pajId, intervalSec, durationSec)
            if (session == null) {
                call.reject("Failed to start live tracking session")
                return
            }
            call.resolve()
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterPlugin] startLiveTracking failed: ${e.message}")
            call.reject(e.message ?: "startLiveTracking failed")
        }
    }

    /**
     * Stops a live tracking session. When the `pajDeviceId` argument is
     * omitted (or null/empty) every active session is stopped.
     */
    @PluginMethod
    fun stopLiveTracking(call: PluginCall) {
        try {
            val pajId = readPajDeviceId(call)
            if (pajId.isNullOrBlank()) {
                val count = LiveTrackingManager.stopAll()
                LOG("[BtLocationReporterPlugin] stopLiveTracking stopped all ($count sessions)")
            } else {
                val ok = LiveTrackingManager.stopForDevice(pajId)
                LOG("[BtLocationReporterPlugin] stopLiveTracking pajDeviceId=$pajId stopped=$ok")
            }
            call.resolve()
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterPlugin] stopLiveTracking failed: ${e.message}")
            call.reject(e.message ?: "stopLiveTracking failed")
        }
    }

    /**
     * Returns all currently active live tracking sessions, including how many
     * seconds each one has left before auto-expiring.
     */
    @PluginMethod
    fun getLiveTrackingDevices(call: PluginCall) {
        try {
            val devices = JSArray()
            for (s in LiveTrackingManager.snapshot()) {
                val obj = JSObject()
                obj.put("pajDeviceId", s.pajDeviceId)
                obj.put("intervalSec", s.intervalSec)
                obj.put("durationSec", s.durationSec)
                obj.put("remainingSec", s.remainingSec)
                obj.put("startedAt", s.startedAtMs)
                obj.put("expiresAt", s.expiresAtMs)
                devices.put(obj)
            }
            val result = JSObject()
            result.put("devices", devices)
            call.resolve(result)
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterPlugin] getLiveTrackingDevices failed: ${e.message}")
            call.reject(e.message ?: "getLiveTrackingDevices failed")
        }
    }

    /**
     * Accepts `pajDeviceId` as either string or number and normalizes it
     * to a non-blank trimmed string. Returns null when the value is absent
     * or cannot be parsed.
     */
    private fun readPajDeviceId(call: PluginCall): String? {
        return try {
            val asString = call.getString("pajDeviceId")
            if (!asString.isNullOrBlank()) return asString.trim()
            val asInt = call.getInt("pajDeviceId")
            if (asInt != null) return asInt.toString()
            val asLong = call.getLong("pajDeviceId")
            if (asLong != null) return asLong.toString()
            val asDouble = call.getDouble("pajDeviceId")
            if (asDouble != null) return asDouble.toLong().toString()
            null
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterPlugin] readPajDeviceId failed: ${e.message}")
            null
        }
    }

    /**
     * Reads a Long value from the Capacitor call, accepting either an Int,
     * a Long or a Double that originated in JS as a Number.
     */
    private fun readLongArg(call: PluginCall, key: String): Long? {
        return try {
            call.getLong(key)
                ?: call.getInt(key)?.toLong()
                ?: call.getDouble(key)?.toLong()
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterPlugin] readLongArg($key) failed: ${e.message}")
            null
        }
    }

    /** Emits a `liveTrackingStarted` event to JS. Called by the manager listener. */
    fun notifyLiveTrackingStarted(session: LiveTrackingManager.Session) {
        try {
            val payload = JSObject().apply {
                put("pajDeviceId", session.pajDeviceId)
                put("intervalSec", session.intervalSec)
                put("durationSec", session.durationSec)
                put("startedAt",   session.startedAtMs)
                put("expiresAt",   session.expiresAtMs)
            }
            notifyListeners(EVENT_LIVE_TRACKING_STARTED, payload)
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterPlugin] notifyLiveTrackingStarted failed: ${e.message}")
        }
    }

    /** Emits a `liveTrackingStopped` event to JS. Called by the manager listener. */
    fun notifyLiveTrackingStopped(pajDeviceId: String?, reason: String) {
        try {
            val payload = JSObject().apply {
                put("pajDeviceId", pajDeviceId ?: JSONObject.NULL)
                put("reason", reason)
            }
            notifyListeners(EVENT_LIVE_TRACKING_STOPPED, payload)
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterPlugin] notifyLiveTrackingStopped failed: ${e.message}")
        }
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
