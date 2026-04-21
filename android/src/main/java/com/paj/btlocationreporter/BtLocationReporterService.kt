package com.paj.btlocationreporter

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import androidx.core.app.NotificationCompat
import com.getcapacitor.JSObject
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Android Foreground Service that:
 *  - Keeps a persistent notification so the OS never kills the process.
 *  - Manages BLE connections via [BleConnectionManager].
 *  - Uses location callbacks to trigger reports (not polling).
 *  - POSTs location + connected device IDs to the configured endpoint.
 *  - Integrates [GpsSwitcher] for GPS_OFF/GPS_ON GATT commands.
 */
class BtLocationReporterService : Service() {


    init {
        LOG("[BtLocationReporterService] Constructor called")
    }
    
    // ── Companion / constants ─────────────────────────────────────────────

    companion object {
        const val ACTION_START = "com.paj.btlocationreporter.START"
        const val ACTION_STOP  = "com.paj.btlocationreporter.STOP"

        const val EXTRA_DEVICE_IDS   = "deviceIds"
        const val EXTRA_DEVICES_JSON = "devicesJson"
        const val EXTRA_ENDPOINT     = "endpoint"
        const val EXTRA_AUTH_TOKEN   = "authToken"
        const val EXTRA_INTERVAL_MS  = "intervalMs"
        const val EXTRA_NOTIF_TITLE  = "notifTitle"
        const val EXTRA_NOTIF_TEXT   = "notifText"
        const val EXTRA_EXTRA_JSON   = "extraJson"
        const val EXTRA_DEBUG        = "debug"
        const val EXTRA_TEXT_CONNECTED_HEADER = "textConnectedHeader"
        const val EXTRA_TEXT_CONNECTED = "textConnected"

        const val NOTIF_CHANNEL_ID = "bt_location_reporter_channel"
        const val BLE_CONNECT_CHANNEL_ID = "bt_ble_connection_channel"
        const val NOTIF_ID         = 7411
        const val BLE_CONNECT_NOTIF_ID_BASE = 8000  // Use deviceId hashcode as offset

        @Volatile var isRunning = false

        /** Commands forwarded from plugin */
        @Volatile var pendingCommand: Command? = null
        
        /** Called by plugin when location permission is granted */
        private var serviceInstance: BtLocationReporterService? = null
        
        fun onLocationPermissionGranted() {
            serviceInstance?.handleLocationPermissionGranted()
        }

        fun writeWithoutResponse(
            deviceId: String,
            serviceUuid: UUID,
            charUuid: UUID,
            data: ByteArray,
            callback: (Exception?) -> Unit
        ) {
            val svc = serviceInstance
            if (svc == null || !svc::bleManager.isInitialized) {
                callback(Exception("Service not started — call start() first"))
                return
            }
            svc.bleManager.writeWithoutResponse(deviceId, serviceUuid, charUuid, data, callback)
        }
    }

    sealed class Command {
        data class AddDevices(val entries: Map<String, String>, val commands: Map<String, Pair<BleCommand?, BleCommand?>>) : Command()
        data class RemoveDevices(val bleIds: List<String>) : Command()
    }

    // ── State ─────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var bleManager: BleConnectionManager
    private lateinit var gpsSwitcher: GpsSwitcher
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var endpoint   = ""
    private var authToken  = ""
    private var intervalMs = 30_000L
    private var extraJson  = "{}"
    private var pajIdMap   = mutableMapOf<String, String>()
    
    // Notification texts (configurable)
    private var textConnectedHeader = "Device connected"
    private var textConnected = "{device} connected via Bluetooth, power saving activated"
    
    // Location-triggered report throttling
    private var lastReportTime = 0L
    
    // Location tracking state (pause when no BLE connected)
    private var isLocationTracking = false
    
    // Track if we've already requested location permission
    private var locationPermissionRequested = false

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        LOG("[BtLocationReporterService] Created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LOG("[BtLocationReporterService] onStartCommand: startId=$startId action=${intent?.action} intentPresent=${intent != null}")

        if (intent?.action == ACTION_STOP) {
            LOG("[BtLocationReporterService] onStartCommand: ACTION_STOP received")
            shutdown(); return START_NOT_STICKY
        }

        try {
            // Prioridad 1: explicit config payload in intent (wake-up via receiver/boot)
            val intentConfigJson = intent?.getStringExtra("config_json")
            if (!intentConfigJson.isNullOrBlank()) {
                LOG("[BtLocationReporterService] onStartCommand: config_json received in intent -> handleStartFromConfig")
                handleStartFromConfig(intentConfigJson)
                return START_STICKY
            }

            // Prioridad 2: explicit start intent from plugin
            if (intent?.action == ACTION_START) {
                LOG("[BtLocationReporterService] onStartCommand: ACTION_START received -> handleStart")
                handleStart(intent)
                return START_STICKY
            }

            // Prioridad 3: persisted config fallback from shared preferences
            val persistedConfigJson = ConfigStore.getConfig(this)
            if (!persistedConfigJson.isNullOrBlank()) {
                LOG("[BtLocationReporterService] onStartCommand: persisted config found -> handleStartFromConfig")
                handleStartFromConfig(persistedConfigJson)
                return START_STICKY
            }

            // Nada válido para iniciar
            LOG_ERROR("[BtLocationReporterService] onStartCommand: no config found and no ACTION_START -> stopping")
            stopSelf()
            return START_NOT_STICKY

        } catch (ex: Exception) {
            LOG_ERROR("[BtLocationReporterService] onStartCommand error: ${ex.message}")
            ex.printStackTrace()
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun handleStartFromConfig(configJson: String) {
        if (configJson.isBlank()) {
            LOG_ERROR("[BtLocationReporterService] handleStartFromConfig: empty config")
            return
        }

        val config = JSONObject(configJson)
        val devicesArray = config.optJSONArray("devices") ?: org.json.JSONArray()
        val endpoint = config.optString("reportEndpoint", "")
        val authToken = config.optString("authToken", "")
        val intervalMs = config.optLong("reportIntervalMs", 30_000L)

        val textsObj = config.optJSONObject("texts")
        val notifTitle = textsObj?.optString("trackerHeader") ?: "BT Location Reporter"
        val notifText = textsObj?.optString("tracker") ?: "Tracking location in background…"
        val textConnectedHeader = textsObj?.optString("connectedHeader") ?: "Device connected"
        val textConnected = textsObj?.optString("connected") ?: "{device} connected via Bluetooth, power saving activated"

        val ids = ArrayList<String>()
        for (i in 0 until devicesArray.length()) {
            val obj = devicesArray.getJSONObject(i)
            val bleId = obj.optString("bleDeviceId")
            if (bleId.isNotEmpty()) ids.add(bleId)
        }

        val normalizedIntent = Intent(this, BtLocationReporterService::class.java).apply {
            action = ACTION_START
            putStringArrayListExtra(EXTRA_DEVICE_IDS, ids)
            putExtra(EXTRA_DEVICES_JSON, devicesArray.toString())
            putExtra(EXTRA_ENDPOINT, endpoint)
            putExtra(EXTRA_AUTH_TOKEN, authToken)
            putExtra(EXTRA_INTERVAL_MS, intervalMs)
            putExtra(EXTRA_NOTIF_TITLE, notifTitle)
            putExtra(EXTRA_NOTIF_TEXT, notifText)
            putExtra(EXTRA_TEXT_CONNECTED_HEADER, textConnectedHeader)
            putExtra(EXTRA_TEXT_CONNECTED, textConnected)
            putExtra(EXTRA_EXTRA_JSON, config.optString("extraJson", "{}"))
            putExtra(EXTRA_DEBUG, config.optBoolean("debug", false))
        }

        handleStart(normalizedIntent)
    }

    private fun handleStart(intent: Intent) {
        val ids    = intent.getStringArrayListExtra(EXTRA_DEVICE_IDS) ?: arrayListOf()
        val devicesJson = intent.getStringExtra(EXTRA_DEVICES_JSON) ?: "[]"

        if (isRunning) {
            LOG("[BtLocationReporterService] handleStart called while running; updating config and device set")

            // Update local config values (in case changed by new start call)
            endpoint   = intent.getStringExtra(EXTRA_ENDPOINT)    ?: endpoint
            authToken  = intent.getStringExtra(EXTRA_AUTH_TOKEN)  ?: authToken
            intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, intervalMs)
            extraJson  = intent.getStringExtra(EXTRA_EXTRA_JSON)  ?: extraJson
            textConnectedHeader = intent.getStringExtra(EXTRA_TEXT_CONNECTED_HEADER) ?: textConnectedHeader
            textConnected = intent.getStringExtra(EXTRA_TEXT_CONNECTED) ?: textConnected

            LinkedDeviceStore.saveLinkedDevices(this, ids.toSet())

            if (::bleManager.isInitialized) {
                val existingIds = bleManager.getTargetIds().toSet()
                val toAdd = ids.filter { it !in existingIds }
                val toRemove = existingIds.filter { it !in ids }
                if (toAdd.isNotEmpty()) bleManager.addDevices(toAdd)
                if (toRemove.isNotEmpty()) bleManager.removeDevices(toRemove)
            }

            parseDevicesAndCommands(devicesJson)
            startBleScanWithPendingIntent()
            return
        }

        isRunning = true
        serviceInstance = this

        // 1. Inicialización de parámetros
        endpoint   = intent.getStringExtra(EXTRA_ENDPOINT)    ?: ""
        authToken  = intent.getStringExtra(EXTRA_AUTH_TOKEN)  ?: ""
        intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 30_000L)
        extraJson  = intent.getStringExtra(EXTRA_EXTRA_JSON)  ?: "{}"
        val title  = intent.getStringExtra(EXTRA_NOTIF_TITLE) ?: "BT Location Reporter"
        val text   = intent.getStringExtra(EXTRA_NOTIF_TEXT)  ?: "Tracking location in background…"
        
        // Debug mode
        val debug = intent.getBooleanExtra(EXTRA_DEBUG, false)
        FileLogger.debugEnabled = debug

        // Notification texts
        textConnectedHeader = intent.getStringExtra(EXTRA_TEXT_CONNECTED_HEADER) ?: "Device connected"
        textConnected = intent.getStringExtra(EXTRA_TEXT_CONNECTED) ?: "{device} connected via Bluetooth, power saving activated"

        LOG("[BtLocationReporterService] Starting: ${ids.size} devices, interval=${intervalMs}ms, debug=$debug")

        // 2. Procesador de comandos SIEMPRE antes de BLE
        LOG("[BtLocationReporterService] Iniciando startCommandProcessor...")
        startCommandProcessor()

        // 3. Inicializar GPS Switcher
        gpsSwitcher = GpsSwitcher()

        // 4. Parsear devices y comandos
        parseDevicesAndCommands(devicesJson)

        // 5. Notificación foreground
        startForeground(NOTIF_ID, buildNotification(title, text))
        
        // 6. Inicializar BLE Manager SOLO después de todo lo anterior
        LOG("[BtLocationReporterService] Inicializando BleConnectionManager...")
        bleManager = BleConnectionManager(
            context = this,
            initialIds = ids,
            onConnected = { deviceId, gatt -> handleBleConnected(deviceId, gatt) },
            onDisconnected = { deviceId, gatt -> handleBleDisconnected(deviceId, gatt) },
            onServicesDiscovered = { deviceId, gatt, status -> gpsSwitcher.onServicesDiscovered(deviceId, gatt, status) },
            onBluetoothOff = { handleBluetoothOff() }
        )
        bleManager.start()

        // 7. Iniciar escaneo BLE
        LOG("[BtLocationReporterService] Iniciando BLE scan con PendingIntent...")
        startBleScanWithPendingIntent()

        LOG("[BtLocationReporterService] Started successfully")
    }

    private fun parseDevicesAndCommands(devicesJson: String) {
        runCatching {
            val arr = org.json.JSONArray(devicesJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val bleId = obj.getString("bleDeviceId")
                val pajId = obj.getString("pajDeviceId")
                pajIdMap[bleId] = pajId

                // Parse optional commands
                val onConnect = parseCommand(obj.optJSONObject("onConnectCommand"))
                val onDisconnect = parseCommand(obj.optJSONObject("onDisconnectCommand"))

                LOG("[BtLocationReporterService] parseDevicesAndCommands: bleId=$bleId onConnect=${onConnect?.name} onDisconnect=${onDisconnect?.name}")

                if (::gpsSwitcher.isInitialized) {
                    gpsSwitcher.registerDevice(bleId, onConnect, onDisconnect)
                }
            }
        }.onFailure {
            LOG_ERROR("[BtLocationReporterService] Failed to parse devices: ${it.message}")
        }
    }

    private fun parseCommand(json: JSONObject?): BleCommand? {
        if (json == null) return null
        
        return try {
            val serviceUuid = json.optString("serviceUuid").takeIf { it.isNotBlank() }
                ?: json.optString("service_uuid").takeIf { it.isNotBlank() }
                ?: return null
            val characteristicUuid = json.optString("characteristicUuid").takeIf { it.isNotBlank() }
                ?: json.optString("characteristic_uuid").takeIf { it.isNotBlank() }
                ?: return null
            BleCommand(
                name = json.getString("name"),
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                value = json.getString("value")
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ── Shutdown ──────────────────────────────────────────────────────────

    private fun shutdown() {
        LOG("[BtLocationReporterService] Stopping...")
        isRunning = false
        isLocationTracking = false
        locationPermissionRequested = false
        serviceInstance = null
        serviceScope.cancel()
        if (::bleManager.isInitialized) bleManager.stop()
        if (::gpsSwitcher.isInitialized) gpsSwitcher.cleanup()
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        // Detener escaneo BLE con PendingIntent
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.bluetoothLeScanner
        scanner?.stopScan(getBleScanPendingIntent())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LOG("[BtLocationReporterService] Stopped")
    }

    // ── BLE handlers ──────────────────────────────────────────────────────

    private fun handleBleConnected(deviceId: String, gatt: BluetoothGatt) {
        LOG("[BtLocationReporterService] BLE connected: $deviceId")
        gpsSwitcher.onDeviceConnected(deviceId, gatt)
        BtLocationReporterPlugin.instance?.notifyBleConnection(deviceId, true)
        
        // Show local notification about BLE connection
        showBleConnectionNotification(deviceId, gatt.device?.name)
        
        // Resume location updates when first device connects (if we have permission)
        if (bleManager.connectedIds.size == 1) {
            if (hasLocationPermission()) {
                resumeLocationUpdates()
            } else if (!locationPermissionRequested) {
                // First BLE connected but no location permission - request it now
                locationPermissionRequested = true
                LOG("[BtLocationReporterService] First BLE connected - requesting location permission")
                BtLocationReporterPlugin.instance?.requestLocationPermissionFromService()
            }
        }
    }
    
    private fun hasLocationPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    
    fun handleLocationPermissionGranted() {
        LOG("[BtLocationReporterService] Location permission granted callback")
        if (bleManager.connectedIds.isNotEmpty() && !isLocationTracking) {
            resumeLocationUpdates()
        }
    }

    private fun handleBleDisconnected(deviceId: String, gatt: BluetoothGatt) {
        LOG("[BtLocationReporterService] BLE disconnected: $deviceId")
        gpsSwitcher.onDeviceDisconnected(deviceId, gatt)
        BtLocationReporterPlugin.instance?.notifyBleConnection(deviceId, false)
        
        // Pause location updates when last device disconnects
        if (bleManager.connectedIds.isEmpty()) {
            pauseLocationUpdates()
        }
    }

    private fun handleBluetoothOff() {
        LOG("[BtLocationReporterService] Bluetooth OFF - pausing location")
        pauseLocationUpdates()
    }

    // ── Location (triggers reports) ───────────────────────────────────────

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onNewLocation(location)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (isLocationTracking) return
        if (!::fusedClient.isInitialized) {
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
        }
        // Si intervalMs > 0, usarlo como intervalo de LocationRequest, si no, usar valores por defecto
        val interval = if (intervalMs > 0) intervalMs else 10_000L
        val minUpdate = if (intervalMs > 0) intervalMs / 2 else 5_000L
        
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setMinUpdateDistanceMeters(10f)  // 10 metros
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        isLocationTracking = true
        LOG("[BtLocationReporterService] Location updates started (interval=${interval}ms, minUpdate=${minUpdate}ms)")
    }

    private fun pauseLocationUpdates() {
        if (!isLocationTracking) return
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
        isLocationTracking = false
        LOG("[BtLocationReporterService] Location updates paused (no BLE devices)")
    }

    @SuppressLint("MissingPermission")
    private fun resumeLocationUpdates() {
        if (isLocationTracking) return
        startLocationUpdates()
        LOG("[BtLocationReporterService] Location updates resumed (BLE connected)")
    }

    private fun onNewLocation(location: Location) {
        if (!isRunning) return
        
        val connectedIds = bleManager.connectedIds.toList()
        if (connectedIds.isEmpty()) return  // No BLE devices connected
        
        // Throttle based on interval
        val now = System.currentTimeMillis()
        if (now - lastReportTime < intervalMs) return
        lastReportTime = now

        postLocationReport(location, connectedIds)
    }

    // ── Command processor ─────────────────────────────────────────────────

    private fun startCommandProcessor() {
        serviceScope.launch {
            while (isActive) {
                pendingCommand?.let { cmd ->
                    // Esperar a que bleManager esté inicializado antes de processar comandos
                    if (!::bleManager.isInitialized) {
                        return@let
                    }
                    when (cmd) {
                        is Command.AddDevices -> {
                            bleManager.addDevices(cmd.entries.keys.toList())
                            pajIdMap.putAll(cmd.entries)
                            cmd.commands.forEach { (deviceId, pair) ->
                                gpsSwitcher.registerDevice(deviceId, pair.first, pair.second)
                            }
                        }
                        is Command.RemoveDevices -> {
                            bleManager.removeDevices(cmd.bleIds)
                            cmd.bleIds.forEach { 
                                pajIdMap.remove(it)
                                gpsSwitcher.unregisterDevice(it)
                            }
                        }
                    }
                    pendingCommand = null
                }
                delay(500)
            }
        }
    }

    // ── HTTP POST ─────────────────────────────────────────────────────────

    private fun postLocationReport(location: Location, connectedIds: List<String>) {
        val extra = runCatching { JSONObject(extraJson) }.getOrDefault(JSONObject())
        val connectedPajIds = connectedIds.mapNotNull { pajIdMap[it] }

        val body = JSONObject(extra.toMap()).apply {
            put("devicesId", JSONObject.wrap(connectedPajIds))
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy)
            put("timestamp", System.currentTimeMillis())
            // Add bearing if available
            if (location.hasBearing()) {
                put("direction", location.bearing)
            }
        }

        LOG("[BtLocationReporterService] Report: devices=$connectedPajIds, loc=(${String.format("%.5f", location.latitude)}, ${String.format("%.5f", location.longitude)})")

        // DEBUG: Skip HTTP, emit success
        // notifyResult(body, 200, true)

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (authToken.isNotBlank()) addHeader("Authorization", "Bearer $authToken") }
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                LOG_ERROR("[BtLocationReporterService] HTTP failed: ${e.message}")
                gpsSwitcher.onLocationReportFailed(connectedIds)
                notifyResult(body, 0, false)
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                if (ok) {
                    LOG("[BtLocationReporterService] HTTP OK: ${response.code}")
                } else {
                    LOG_ERROR("[BtLocationReporterService] HTTP error: ${response.code}")
                    gpsSwitcher.onLocationReportFailed(connectedIds)
                }
                notifyResult(body, response.code, ok)
                response.close()
            }
        })
    }

    private fun notifyResult(body: JSONObject, httpStatus: Int, success: Boolean) {
        val payload = JSObject.fromJSONObject(body)
        BtLocationReporterPlugin.instance?.notifyLocationReport(payload, httpStatus, success)
    }

    // ── Notification ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun showBleConnectionNotification(deviceId: String, deviceName: String?) {
        // Check if we have notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                LOG("[BtLocationReporterService] No notification permission, skipping BLE connection notification")
                return
            }
        }
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create channel for BLE connection notifications (Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BLE_CONNECT_CHANNEL_ID,
                "Bluetooth Connections",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Bluetooth device connection notifications"
            }
            manager.createNotificationChannel(channel)
        }
        
        val displayName = deviceName?.takeIf { it.isNotBlank() } ?: deviceId
        val title = textConnectedHeader
        val text = textConnected.replace("{device}", displayName)
        
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)
        
        val notification = NotificationCompat.Builder(this, BLE_CONNECT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        
        // Use unique notification ID based on device
        val notifId = BLE_CONNECT_NOTIF_ID_BASE + deviceId.hashCode().and(0xFFFF)
        manager.notify(notifId, notification)
        
        LOG("[BtLocationReporterService] Showed BLE connection notification for: $displayName")
    }

    private fun buildNotification(title: String, text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Location Reporter",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background BLE and GPS tracking" }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startBleScanWithPendingIntent() {
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter?.bluetoothLeScanner ?: return
        val linked = LinkedDeviceStore.getLinkedDevices(this)
        if (linked.isEmpty()) {
            scanner.stopScan(getBleScanPendingIntent())
            return
        }
        val filters = linked.map {
            ScanFilter.Builder().setDeviceAddress(it).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        scanner.startScan(filters, settings, getBleScanPendingIntent())
    }

    private fun getBleScanPendingIntent(): PendingIntent {
        val intent = Intent(this, BleScanReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(this, 0, intent, flags)
    }
}

private fun JSONObject.toMap(): Map<String, Any> = keys().asSequence().associateWith { get(it) }
