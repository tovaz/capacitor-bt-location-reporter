package com.paj.btlocationreporter

import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID

/**
 * Manages BLE connections on Android.
 *
 * Strategy:
 *  - For each target device ID (MAC address), we attempt a direct [BluetoothDevice.connectGatt].
 *  - If the connection drops, we schedule a reconnect after [RECONNECT_DELAY_MS].
 *  - [connectedIds] is always the live set of confirmed GATT-connected devices.
 *  - On connection state changes, [onConnectionChanged] is called so the plugin
 *    can fire the JS "bleConnection" event.
 */
class BleConnectionManager(
    private val context: Context,
    initialIds: List<String>,
    private val onConnectionChanged: (deviceId: String, connected: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "BleConnectionManager"
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val GATT_TIMEOUT_MS    = 15_000L
    }

    val targetIds   = CopyOnWriteArrayList(initialIds)
    val connectedIds = CopyOnWriteArrayList<String>()

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gattMap = mutableMapOf<String, BluetoothGatt>() // deviceId → gatt

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun start() {
        targetIds.forEach { connectDevice(it) }
    }

    fun stop() {
        scope.cancel()
        gattMap.values.forEach { it.disconnect(); it.close() }
        gattMap.clear()
    }

    fun addDevices(ids: List<String>) {
        ids.filter { it !in targetIds }.forEach { id ->
            targetIds.add(id)
            connectDevice(id)
        }
    }

    fun removeDevices(ids: List<String>) {
        ids.forEach { id ->
            targetIds.remove(id)
            gattMap[id]?.let { g -> g.disconnect(); g.close() }
            gattMap.remove(id)
            connectedIds.remove(id)
        }
    }

    // ── Connection logic ──────────────────────────────────────────────────

    private fun connectDevice(deviceId: String) {
        scope.launch {
            val adapter = bluetoothAdapter ?: run {
                Log.e(TAG, "Bluetooth adapter not available")
                return@launch
            }

            val device = runCatching { adapter.getRemoteDevice(deviceId) }.getOrNull() ?: run {
                Log.e(TAG, "Invalid device ID: $deviceId")
                return@launch
            }

            Log.i(TAG, "Connecting to $deviceId…")

            withContext(Dispatchers.Main) {
                // autoConnect=true: Android will reconnect when the device comes into range
                device.connectGatt(context, true, buildGattCallback(deviceId))
            }
        }
    }

    private fun scheduleReconnect(deviceId: String) {
        if (deviceId !in targetIds) return
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (deviceId in targetIds) {
                Log.i(TAG, "Reconnecting to $deviceId…")
                connectDevice(deviceId)
            }
        }
    }

    // ── GATT callbacks ────────────────────────────────────────────────────

    private fun buildGattCallback(deviceId: String) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "✅ Connected: $deviceId")
                    gattMap[deviceId] = gatt
                    if (deviceId !in connectedIds) connectedIds.add(deviceId)
                    onConnectionChanged(deviceId, true)

                    // Discover services so GATT is fully operational
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "❌ Disconnected: $deviceId (status=$status)")
                    connectedIds.remove(deviceId)
                    gattMap.remove(deviceId)
                    gatt.close()
                    onConnectionChanged(deviceId, false)
                    scheduleReconnect(deviceId)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for $deviceId")
            }
        }
    }
}
