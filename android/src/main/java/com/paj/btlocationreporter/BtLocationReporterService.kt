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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.google.android.gms.location.*
import com.paj.btlocationreporter.livetracking.LiveTrackingManager
import com.paj.btlocationreporter.livetracking.LiveTrackingServiceBridge
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Android Foreground Service that:
 *  - Keeps a persistent notification so the OS never kills the process.
 *  - Manages BLE connections via [BleConnectionManager].
 *  - Uses location callbacks to trigger HTTP reports.
 *  - POSTs location + connected device IDs to the configured endpoint.
 *  - Integrates [GpsSwitcher] for GPS_OFF/GPS_ON GATT commands.
 *  - Monitors internet connectivity and location state; GPS_OFF is only sent
 *    when internet is available, location permission is granted, and location
 *    service is enabled.
 */
class BtLocationReporterService : Service() {

    init {
        LOG("[BtLocationReporterService] Constructor called")
    }

    // ── Companion / constants ─────────────────────────────────────────────

    companion object {
        const val ACTION_START = "com.paj.btlocationreporter.START"
        const val ACTION_STOP  = "com.paj.btlocationreporter.STOP"

        const val EXTRA_DEVICE_IDS            = "deviceIds"
        const val EXTRA_DEVICES_JSON          = "devicesJson"
        const val EXTRA_ENDPOINT              = "endpoint"
        const val EXTRA_AUTH_TOKEN            = "authToken"
        const val EXTRA_INTERVAL_MS           = "intervalMs"
        const val EXTRA_NOTIF_TITLE           = "notifTitle"
        const val EXTRA_NOTIF_TEXT            = "notifText"
        const val EXTRA_EXTRA_JSON            = "extraJson"
        const val EXTRA_DEBUG                 = "debug"
        const val EXTRA_TEXT_CONNECTED_HEADER = "textConnectedHeader"
        const val EXTRA_TEXT_CONNECTED        = "textConnected"

        const val NOTIF_CHANNEL_ID          = "bt_location_reporter_channel"
        const val BLE_CONNECT_CHANNEL_ID    = "bt_ble_connection_channel"
        const val NOTIF_ID                  = 7411
        const val BLE_CONNECT_NOTIF_ID_BASE = 8000

        @Volatile var isRunning     = false
        @Volatile var pendingCommand: Command? = null

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
        data class AddDevices(
            val entries: Map<String, String>,
            val commands: Map<String, Pair<BleCommand?, BleCommand?>>
        ) : Command()
        data class RemoveDevices(val bleIds: List<String>) : Command()
    }

    // ── State ─────────────────────────────────────────────────────────────

    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var bleManager:  BleConnectionManager
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

    private var textConnectedHeader = "Device connected"
    private var textConnected       = "{device} connected via Bluetooth, power saving activated"

    private var lastReportTime           = 0L
    private var isLocationTracking       = false
    private var locationPermissionRequested = false

    // Bridge between [LiveTrackingManager] and this service. Non-null while the
    // service is alive; used to reconfigure location updates when a live
    // tracking session starts, stops or expires.
    private var liveTrackingBridge: LiveTrackingServiceBridge? = null

    // ── Connectivity & location-state monitoring ──────────────────────────

    /** True when the device has a validated internet connection. */
    private var hasInternet = true

    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var locationStateReceiverRegistered = false

    private val locationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                LOG("[BtLocationReporterService] Location providers changed")
                notifyConditionsChanged()
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        LOG("[BtLocationReporterService] Created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LOG("[BtLocationReporterService] onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            shutdown(); return START_NOT_STICKY
        }

        try {
            val intentConfigJson = intent?.getStringExtra("config_json")
            if (!intentConfigJson.isNullOrBlank()) {
                LOG("[BtLocationReporterService] config_json in intent → handleStartFromConfig")
                handleStartFromConfig(intentConfigJson)
                return START_STICKY
            }

            if (intent?.action == ACTION_START) {
                LOG("[BtLocationReporterService] ACTION_START → handleStart")
                handleStart(intent)
                return START_STICKY
            }

            val persisted = ConfigStore.getConfig(this)
            if (!persisted.isNullOrBlank()) {
                LOG("[BtLocationReporterService] persisted config → handleStartFromConfig")
                handleStartFromConfig(persisted)
                return START_STICKY
            }

            LOG_ERROR("[BtLocationReporterService] no config found → stopSelf")
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

        val config       = JSONObject(configJson)
        val devicesArray = config.optJSONArray("devices") ?: org.json.JSONArray()
        val textsObj     = config.optJSONObject("texts")

        val ids = ArrayList<String>()
        for (i in 0 until devicesArray.length()) {
            val bleId = devicesArray.getJSONObject(i).optString("bleDeviceId")
            if (bleId.isNotEmpty()) ids.add(bleId)
        }

        val normalizedIntent = Intent(this, BtLocationReporterService::class.java).apply {
            action = ACTION_START
            putStringArrayListExtra(EXTRA_DEVICE_IDS, ids)
            putExtra(EXTRA_DEVICES_JSON, devicesArray.toString())
            putExtra(EXTRA_ENDPOINT,     config.optString("reportEndpoint", ""))
            putExtra(EXTRA_AUTH_TOKEN,   config.optString("authToken", ""))
            putExtra(EXTRA_INTERVAL_MS,  config.optLong("reportIntervalMs", 30_000L))
            putExtra(EXTRA_NOTIF_TITLE,  textsObj?.optString("trackerHeader")  ?: "BT Location Reporter")
            putExtra(EXTRA_NOTIF_TEXT,   textsObj?.optString("tracker")        ?: "Tracking location in background…")
            putExtra(EXTRA_TEXT_CONNECTED_HEADER, textsObj?.optString("connectedHeader") ?: "Device connected")
            putExtra(EXTRA_TEXT_CONNECTED,        textsObj?.optString("connected")       ?: "{device} connected via Bluetooth, power saving activated")
            putExtra(EXTRA_EXTRA_JSON,   config.optString("extraJson", "{}"))
            putExtra(EXTRA_DEBUG,        config.optBoolean("debug", false))
        }
        handleStart(normalizedIntent)
    }

    private fun handleStart(intent: Intent) {
        val ids        = intent.getStringArrayListExtra(EXTRA_DEVICE_IDS) ?: arrayListOf()
        val devicesJson = intent.getStringExtra(EXTRA_DEVICES_JSON) ?: "[]"

        if (isRunning) {
            LOG("[BtLocationReporterService] already running — updating config")
            endpoint   = intent.getStringExtra(EXTRA_ENDPOINT)    ?: endpoint
            authToken  = intent.getStringExtra(EXTRA_AUTH_TOKEN)  ?: authToken
            intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, intervalMs)
            extraJson  = intent.getStringExtra(EXTRA_EXTRA_JSON)  ?: extraJson
            textConnectedHeader = intent.getStringExtra(EXTRA_TEXT_CONNECTED_HEADER) ?: textConnectedHeader
            textConnected       = intent.getStringExtra(EXTRA_TEXT_CONNECTED)        ?: textConnected

            LinkedDeviceStore.saveLinkedDevices(this, ids.toSet())

            if (::bleManager.isInitialized) {
                val existingIds = bleManager.getTargetIds().toSet()
                val toAdd    = ids.filter { it !in existingIds }
                val toRemove = existingIds.filter { it !in ids }
                if (toAdd.isNotEmpty())    bleManager.addDevices(toAdd)
                if (toRemove.isNotEmpty()) bleManager.removeDevices(toRemove)
            }

            parseDevicesAndCommands(devicesJson)
            startBleScanWithPendingIntent()
            return
        }

        isRunning       = true
        serviceInstance = this

        // Wire the LiveTrackingManager to this service so temporary interval
        // overrides can reconfigure the FusedLocation request at runtime.
        liveTrackingBridge = LiveTrackingServiceBridge { reconfigureLocationUpdates() }
        LiveTrackingManager.listener = liveTrackingBridge

        // ── Init params ───────────────────────────────────────────────────
        endpoint   = intent.getStringExtra(EXTRA_ENDPOINT)    ?: ""
        authToken  = intent.getStringExtra(EXTRA_AUTH_TOKEN)  ?: ""
        intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 30_000L)
        extraJson  = intent.getStringExtra(EXTRA_EXTRA_JSON)  ?: "{}"
        val title  = intent.getStringExtra(EXTRA_NOTIF_TITLE) ?: "BT Location Reporter"
        val text   = intent.getStringExtra(EXTRA_NOTIF_TEXT)  ?: "Tracking location in background…"
        val debug  = intent.getBooleanExtra(EXTRA_DEBUG, false)
        FileLogger.debugEnabled = debug

        textConnectedHeader = intent.getStringExtra(EXTRA_TEXT_CONNECTED_HEADER) ?: "Device connected"
        textConnected       = intent.getStringExtra(EXTRA_TEXT_CONNECTED)        ?: "{device} connected via Bluetooth, power saving activated"

        LOG("[BtLocationReporterService] Starting: ${ids.size} devices, interval=${intervalMs}ms, debug=$debug")

        // Si shutdown() canceló el scope (stop+start rápido en el mismo Service),
        // hay que recrearlo antes de lanzar coroutines.
        if (!serviceScope.isActive) {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            LOG("[BtLocationReporterService] serviceScope recreated (was cancelled)")
        }

        // ── Internet & location monitoring ────────────────────────────────
        hasInternet = checkInternetAvailable()
        LOG("[BtLocationReporterService] Initial state: internet=$hasInternet locationEnabled=${isLocationEnabled()} locationPerm=${hasLocationPermission()}")
        startMonitoring()

        // ── Command processor ─────────────────────────────────────────────
        startCommandProcessor()

        // ── GpsSwitcher (condition-aware) ─────────────────────────────────
        gpsSwitcher = GpsSwitcher(canSendGpsOff = { canSendGpsOff() })
        parseDevicesAndCommands(devicesJson)

        // ── Foreground notification (safe for all API levels) ─────────────
        startForegroundSafe(title, text)

        // ── BLE ───────────────────────────────────────────────────────────
        bleManager = BleConnectionManager(
            context      = this,
            initialIds   = ids,
            onConnected         = { deviceId, gatt   -> handleBleConnected(deviceId, gatt) },
            onDisconnected      = { deviceId, gatt   -> handleBleDisconnected(deviceId, gatt) },
            onServicesDiscovered = { deviceId, gatt, status -> gpsSwitcher.onServicesDiscovered(deviceId, gatt, status) },
            onBluetoothOff      = { handleBluetoothOff() }
        )
        bleManager.start()

        startBleScanWithPendingIntent()
        LOG("[BtLocationReporterService] Started successfully")
    }

    // ── Condition monitoring ──────────────────────────────────────────────

    private fun startMonitoring() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LOG("[BtLocationReporterService] Network available")
                hasInternet = true
                notifyConditionsChanged()
            }
            override fun onLost(network: Network) {
                LOG("[BtLocationReporterService] Network lost")
                hasInternet = false
                notifyConditionsChanged()
            }
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(networkCallback)
        } else {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        }

        ContextCompat.registerReceiver(
            this,
            locationStateReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        locationStateReceiverRegistered = true
    }

    private fun stopMonitoring() {
        runCatching {
            if (::networkCallback.isInitialized) {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(networkCallback)
            }
        }
        runCatching {
            if (locationStateReceiverRegistered) {
                unregisterReceiver(locationStateReceiver)
                locationStateReceiverRegistered = false
            }
        }
    }

    private fun checkInternetAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Returns true when all conditions for GPS_OFF are met:
     * internet available + location permission + location service enabled.
     */
    private fun canSendGpsOff(): Boolean =
        hasInternet && hasLocationPermission() && isLocationEnabled()

    /**
     * Evaluates current conditions and notifies [GpsSwitcher] so it can
     * send GPS_OFF or GPS_ON commands to connected devices as appropriate.
     */
    private fun notifyConditionsChanged() {
        if (!isRunning || !::gpsSwitcher.isInitialized || !::bleManager.isInitialized) return
        val connectedIds = bleManager.connectedIds.toList()
        val ok = canSendGpsOff()
        LOG("[BtLocationReporterService] GPS_OFF conditions: internet=$hasInternet locPerm=${hasLocationPermission()} locEnabled=${isLocationEnabled()} → allGood=$ok devices=$connectedIds")
        gpsSwitcher.onConditionsChanged(ok, connectedIds)
    }

    // ── Safe startForeground (crash-proof on API 29+) ─────────────────────

    /**
     * Calls startForeground with the correct foreground service type flags based on
     * currently granted permissions. Uses only CONNECTED_DEVICE when location
     * permission is not available, preventing SecurityException on Android 14+.
     */
    private fun startForegroundSafe(title: String, text: String) {
        val notification = buildNotification(title, text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: use typed startForeground
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (hasLocationPermission()) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                startForeground(NOTIF_ID, notification, type)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterService] startForeground(type) failed: ${e.message}")
            // Fallback: plain 2-param call
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                LOG_ERROR("[BtLocationReporterService] startForeground fallback failed: ${e2.message}")
                stopSelf()
            }
        }
    }

    // ── Parse devices & commands ──────────────────────────────────────────

    private fun parseDevicesAndCommands(devicesJson: String) {
        runCatching {
            val arr = org.json.JSONArray(devicesJson)
            for (i in 0 until arr.length()) {
                val obj   = arr.getJSONObject(i)
                val bleId = obj.getString("bleDeviceId")
                val pajId = obj.getString("pajDeviceId")
                pajIdMap[bleId] = pajId

                val onConnect    = parseCommand(obj.optJSONObject("onConnectCommand"))
                val onDisconnect = parseCommand(obj.optJSONObject("onDisconnectCommand"))

                LOG("[BtLocationReporterService] device $bleId onConnect=${onConnect?.name} onDisconnect=${onDisconnect?.name}")

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
                name               = json.getString("name"),
                serviceUuid        = serviceUuid,
                characteristicUuid = characteristicUuid,
                value              = json.getString("value")
            )
        } catch (e: Exception) { null }
    }

    // ── Service destroy ───────────────────────────────────────────────────

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        LOG("[BtLocationReporterService] Stopping…")
        isRunning                   = false
        isLocationTracking          = false
        locationPermissionRequested = false
        serviceInstance             = null
        // Tear down live tracking state — sessions are intentionally not
        // persisted, so stopping the service also clears them.
        runCatching {
            LiveTrackingManager.listener = null
            LiveTrackingManager.clear()
            liveTrackingBridge = null
        }
        serviceScope.cancel()
        stopMonitoring()
        if (::bleManager.isInitialized)  bleManager.stop()
        if (::gpsSwitcher.isInitialized) gpsSwitcher.cleanup()
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(getBleScanPendingIntent()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LOG("[BtLocationReporterService] Stopped")
    }

    // ── BLE handlers ──────────────────────────────────────────────────────

    private fun handleBleConnected(deviceId: String, gatt: BluetoothGatt) {
        LOG("[BtLocationReporterService] BLE connected: $deviceId")
        gpsSwitcher.onDeviceConnected(deviceId, gatt)
        BtLocationReporterPlugin.instance?.notifyBleConnection(deviceId, true)
        showBleConnectionNotification(deviceId, gatt.device?.name)

        if (bleManager.connectedIds.size == 1) {
            if (hasLocationPermission()) {
                resumeLocationUpdates()
            } else {
                LOG_ERROR("[BtLocationReporterService] BLE connected but no location permission")
                if (!locationPermissionRequested) {
                    locationPermissionRequested = true
                    BtLocationReporterPlugin.instance?.requestLocationPermissionFromService()
                }
            }
        }
    }

    fun handleLocationPermissionGranted() {
        LOG("[BtLocationReporterService] Location permission granted")
        if (::bleManager.isInitialized && bleManager.connectedIds.isNotEmpty() && !isLocationTracking) {
            resumeLocationUpdates()
        }
        // Re-evaluate GPS_OFF conditions now that permission may be granted
        notifyConditionsChanged()
    }

    private fun handleBleDisconnected(deviceId: String, gatt: BluetoothGatt) {
        LOG("[BtLocationReporterService] BLE disconnected: $deviceId")
        gpsSwitcher.onDeviceDisconnected(deviceId, gatt)
        BtLocationReporterPlugin.instance?.notifyBleConnection(deviceId, false)

        if (::bleManager.isInitialized && bleManager.connectedIds.isEmpty()) {
            pauseLocationUpdates()
        }
    }

    private fun handleBluetoothOff() {
        LOG("[BtLocationReporterService] Bluetooth OFF")
        pauseLocationUpdates()
    }

    // ── Location ──────────────────────────────────────────────────────────

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
        // Combine the default reporting interval with any active live tracking
        // session so the location provider uses the shortest requested interval.
        val effective = LiveTrackingManager.getEffectiveIntervalMs(intervalMs)
        val interval = if (effective > 0) effective else 10_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval)
            .setMinUpdateDistanceMeters(0f)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        isLocationTracking = true
        LOG("[BtLocationReporterService] Location updates started (interval=${interval}ms, liveTrackingActive=${LiveTrackingManager.hasActiveSessions()})")
    }

    /**
     * Re-requests location updates with the current effective interval.
     * Called by [LiveTrackingServiceBridge] whenever a live tracking session
     * is started, stopped or expires. No-op when location tracking is paused.
     */
    @SuppressLint("MissingPermission")
    private fun reconfigureLocationUpdates() {
        if (!isLocationTracking) return
        try {
            if (::fusedClient.isInitialized) {
                fusedClient.removeLocationUpdates(locationCallback)
            }
            isLocationTracking = false
            startLocationUpdates()
        } catch (e: Exception) {
            LOG_ERROR("[BtLocationReporterService] reconfigureLocationUpdates failed: ${e.message}")
        }
    }

    private fun pauseLocationUpdates() {
        if (!isLocationTracking) return
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        isLocationTracking = false
        LOG("[BtLocationReporterService] Location updates paused")
    }

    @SuppressLint("MissingPermission")
    private fun resumeLocationUpdates() {
        if (isLocationTracking) return
        startLocationUpdates()
    }

    private fun onNewLocation(location: Location) {
        if (!isRunning) return
        if (!::bleManager.isInitialized) return
        val connectedIds = bleManager.connectedIds.toList()
        if (connectedIds.isEmpty()) return

        val now = System.currentTimeMillis()
        // Throttle HTTP reports against the live-tracking-aware effective interval
        // so active sessions can legitimately post more frequently.
        val effectiveInterval = LiveTrackingManager.getEffectiveIntervalMs(intervalMs)
        if (now - lastReportTime < effectiveInterval) return
        lastReportTime = now

        postLocationReport(location, connectedIds)
    }

    // ── Command processor ─────────────────────────────────────────────────

    private fun startCommandProcessor() {
        serviceScope.launch {
            while (isActive) {
                pendingCommand?.let { cmd ->
                    if (!::bleManager.isInitialized) return@let
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
        val extra          = runCatching { JSONObject(extraJson) }.getOrDefault(JSONObject())
        val connectedPajIds = connectedIds.mapNotNull { pajIdMap[it] }

        val body = JSONObject(extra.toMap()).apply {
            put("devicesId", JSONObject.wrap(connectedPajIds))
            put("lat",       location.latitude)
            put("lng",       location.longitude)
            put("accuracy",  location.accuracy)
            put("timestamp", System.currentTimeMillis())
            if (location.hasBearing()) put("direction", location.bearing)
        }

        LOG("[BtLocationReporterService] Report: devices=$connectedPajIds loc=(${String.format("%.5f", location.latitude)}, ${String.format("%.5f", location.longitude)})")

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (authToken.isNotBlank()) addHeader("Authorization", "Bearer $authToken") }
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                LOG_ERROR("[BtLocationReporterService] HTTP failed: ${e.message}")
                if (::bleManager.isInitialized) gpsSwitcher.onLocationReportFailed(connectedIds)
                notifyResult(body, 0, false)
            }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                if (!ok) {
                    LOG_ERROR("[BtLocationReporterService] HTTP error: ${response.code}")
                    if (::bleManager.isInitialized) gpsSwitcher.onLocationReportFailed(connectedIds)
                } else {
                    LOG("[BtLocationReporterService] HTTP OK: ${response.code}")
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

    // ── Notifications ─────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun showBleConnectionNotification(deviceId: String, deviceName: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(BLE_CONNECT_CHANNEL_ID, "Bluetooth Connections",
                    NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Bluetooth device connection notifications"
                }
            )
        }

        val displayName = deviceName?.takeIf { it.isNotBlank() } ?: deviceId
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, BLE_CONNECT_CHANNEL_ID)
            .setContentTitle(textConnectedHeader)
            .setContentText(textConnected.replace("{device}", displayName))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        manager.notify(BLE_CONNECT_NOTIF_ID_BASE + deviceId.hashCode().and(0xFFFF), notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID, "Location Reporter",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Background BLE and GPS tracking"
                }
            )
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

    // ── BLE background scan ───────────────────────────────────────────────

    private fun startBleScanWithPendingIntent() {
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.bluetoothLeScanner ?: return
        val linked = LinkedDeviceStore.getLinkedDevices(this)
        if (linked.isEmpty()) {
            runCatching { scanner.stopScan(getBleScanPendingIntent()) }
            return
        }
        val filters  = linked.map { ScanFilter.Builder().setDeviceAddress(it).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner.startScan(filters, settings, getBleScanPendingIntent()) }
    }

    private fun getBleScanPendingIntent(): PendingIntent {
        val intent = Intent(this, BleScanReceiver::class.java)
        val flags  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(this, 0, intent, flags)
    }
}

private fun JSONObject.toMap(): Map<String, Any> = keys().asSequence().associateWith { get(it) }
