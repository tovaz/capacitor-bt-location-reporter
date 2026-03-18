package com.paj.btlocationreporter

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.getcapacitor.JSObject
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Android Foreground Service that:
 *  - Keeps a persistent notification so the OS never kills the process.
 *  - Manages BLE connections via [BleConnectionManager].
 *  - Polls GPS via FusedLocationProviderClient.
 *  - POSTs location + connected device IDs to the configured endpoint.
 */
class BtLocationReporterService : Service() {

    // ── Companion / constants ─────────────────────────────────────────────

    companion object {
        const val ACTION_START = "com.paj.btlocationreporter.START"
        const val ACTION_STOP  = "com.paj.btlocationreporter.STOP"

        const val EXTRA_DEVICE_IDS  = "deviceIds"
        const val EXTRA_ENDPOINT    = "endpoint"
        const val EXTRA_AUTH_TOKEN  = "authToken"
        const val EXTRA_INTERVAL_MS = "intervalMs"
        const val EXTRA_NOTIF_TITLE = "notifTitle"
        const val EXTRA_NOTIF_TEXT  = "notifText"
        const val EXTRA_EXTRA_JSON  = "extraJson"

        const val NOTIF_CHANNEL_ID = "bt_location_reporter_channel"
        const val NOTIF_ID         = 7411

        @Volatile var isRunning = false

        /** Commands forwarded from [BtLocationReporterPlugin.addDevices] / removeDevices */
        @Volatile var pendingCommand: Command? = null
    }

    sealed class Command {
        data class AddDevices(val ids: List<String>)    : Command()
        data class RemoveDevices(val ids: List<String>) : Command()
    }

    // ── State ─────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var bleManager: BleConnectionManager
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var lastLocation: Location? = null
    private var endpoint   = ""
    private var authToken  = ""
    private var intervalMs = 30_000L
    private var extraJson  = "{}"

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_START -> handleStart(intent)
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isRunning) return
        isRunning = true

        endpoint   = intent.getStringExtra(EXTRA_ENDPOINT)    ?: ""
        authToken  = intent.getStringExtra(EXTRA_AUTH_TOKEN)  ?: ""
        intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 30_000L)
        extraJson  = intent.getStringExtra(EXTRA_EXTRA_JSON)  ?: "{}"
        val title  = intent.getStringExtra(EXTRA_NOTIF_TITLE) ?: "BT Location Reporter"
        val text   = intent.getStringExtra(EXTRA_NOTIF_TEXT)  ?: "Tracking location in background…"
        val ids    = intent.getStringArrayListExtra(EXTRA_DEVICE_IDS) ?: arrayListOf()

        startForeground(NOTIF_ID, buildNotification(title, text))
        startLocationUpdates()

        bleManager = BleConnectionManager(this, ids) { deviceId, connected ->
            BtLocationReporterPlugin.instance?.notifyBleConnection(deviceId, connected)
        }
        bleManager.start()

        startReportLoop()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ── Shutdown ──────────────────────────────────────────────────────────

    private fun shutdown() {
        isRunning = false
        serviceScope.cancel()
        if (::bleManager.isInitialized) bleManager.stop()
        if (::fusedClient.isInitialized) fusedClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Location ──────────────────────────────────────────────────────────

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastLocation = result.lastLocation
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    // ── Report loop ───────────────────────────────────────────────────────

    private fun startReportLoop() {
        serviceScope.launch {
            while (isActive) {
                // Process any pending add/remove commands from the JS layer
                pendingCommand?.let { cmd ->
                    when (cmd) {
                        is Command.AddDevices    -> bleManager.addDevices(cmd.ids)
                        is Command.RemoveDevices -> bleManager.removeDevices(cmd.ids)
                    }
                    pendingCommand = null
                }

                val loc = lastLocation
                if (loc != null && bleManager.connectedIds.isNotEmpty()) {
                    postLocationReport(loc)
                }

                delay(intervalMs)
            }
        }
    }

    // ── HTTP POST ─────────────────────────────────────────────────────────

    private fun postLocationReport(location: Location) {
        val extra  = runCatching { JSONObject(extraJson) }.getOrDefault(JSONObject())
        val body   = JSONObject(extra.toMap()).apply {
            put("deviceIds",          JSONObject.wrap(bleManager.targetIds.toList()))
            put("connectedDeviceIds", JSONObject.wrap(bleManager.connectedIds.toList()))
            put("lat",       location.latitude)
            put("lng",       location.longitude)
            put("accuracy",  location.accuracy)
            put("timestamp", System.currentTimeMillis())
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (authToken.isNotBlank()) addHeader("Authorization", authToken) }
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("BtLocationReporter", "POST failed: ${e.message}")
                notifyResult(body, 0, false)
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                Log.i("BtLocationReporter", "POST ${response.code} → ${if (ok) "OK" else "ERROR"}")
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

        // Tap notification → open the app
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
}

// Extension to convert JSONObject to Map (needed for putAll in JSONObject constructor)
private fun JSONObject.toMap(): Map<String, Any> = keys().asSequence().associateWith { get(it) }
