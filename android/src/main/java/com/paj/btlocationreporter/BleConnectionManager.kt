package com.paj.btlocationreporter

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages BLE connections on Android.
 *
 * Strategy:
 *  - For each target device ID (MAC address), we attempt a direct [BluetoothDevice.connectGatt].
 *  - If the connection drops, we schedule a reconnect after [RECONNECT_DELAY_MS].
 *  - [connectedIds] is always the live set of confirmed GATT-connected devices.
 *  - On connection state changes, callbacks are fired so the service can handle events.
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val context: Context,
    initialIds: List<String>,
    private val onConnected: (deviceId: String, gatt: BluetoothGatt) -> Unit,
    private val onDisconnected: (deviceId: String, gatt: BluetoothGatt) -> Unit,
    private val onServicesDiscovered: ((deviceId: String, gatt: BluetoothGatt, status: Int) -> Unit)? = null,
    private val onBluetoothOff: (() -> Unit)? = null,
) {
    companion object {
        private const val RECONNECT_DELAY_MS = 3_000L
    }

    private val targetIds = CopyOnWriteArrayList(initialIds)
    val connectedIds = CopyOnWriteArrayList<String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gattMap = mutableMapOf<String, BluetoothGatt>()

    private data class PendingWrite(
        val serviceUuid: UUID,
        val charUuid: UUID,
        val data: ByteArray,
        val callback: (Exception?) -> Unit
    )
    private val pendingWrites = ConcurrentHashMap<String, PendingWrite>()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // Bluetooth state receiver
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    LOG("[BleConnectionManager] Bluetooth OFF detected")
                    handleBluetoothOff()
                }
            }
        }
    }

    private fun handleBluetoothOff() {
        // Clear all connections
        connectedIds.clear()
        //gattMap.values.forEach { it.close() }
        gattMap.clear()
        //onBluetoothOff?.invoke()
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun start() {
        LOG("[BleConnectionManager] Init: ${targetIds.size} devices")
        // Register Bluetooth state receiver
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        targetIds.forEach { connectDevice(it) }
    }

    fun stop() {
        scope.cancel()
        runCatching { context.unregisterReceiver(bluetoothStateReceiver) }
        gattMap.values.forEach { it.disconnect(); it.close() }
        gattMap.clear()
        LOG("[BleConnectionManager] Stopped")
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

    /**
     * Writes [data] to [charUuid] on [deviceId] with WRITE_TYPE_NO_RESPONSE.
     * Performs GATT service discovery automatically if the characteristic is not
     * yet cached on the GATT object (slow path).
     */
    @Suppress("DEPRECATION")
    fun writeWithoutResponse(
        deviceId: String,
        serviceUuid: UUID,
        charUuid: UUID,
        data: ByteArray,
        callback: (Exception?) -> Unit
    ) {
        val gatt = gattMap[deviceId]
        if (gatt == null) {
            callback(Exception("Device not connected: $deviceId"))
            return
        }

        // Fast path: services already discovered
        val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
        if (characteristic != null) {
            doWrite(gatt, characteristic, data, callback)
            return
        }

        // Slow path: queue the write and trigger service discovery
        LOG("[BleConnectionManager] writeWithoutResponse: services not cached for $deviceId, discovering...")
        pendingWrites[deviceId] = PendingWrite(serviceUuid, charUuid, data, callback)
        scope.launch(Dispatchers.Main) { gatt.discoverServices() }
    }

    @Suppress("DEPRECATION")
    private fun doWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        callback: (Exception?) -> Unit
    ) {
        characteristic.value = data
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val success = gatt.writeCharacteristic(characteristic)
        if (success) {
            LOG("[BleConnectionManager] writeWithoutResponse: write queued ok")
            callback(null)
        } else {
            LOG_ERROR("[BleConnectionManager] writeWithoutResponse: writeCharacteristic returned false")
            callback(Exception("writeCharacteristic failed for device ${gatt.device?.address}"))
        }
    }

    /**
     * Get the BluetoothGatt for a device (for GATT writes).
     */
    @Suppress("unused")
    fun getGatt(deviceId: String): BluetoothGatt? = gattMap[deviceId]

    /**
     * Current list of target device IDs (to connect/reconnect). 
     */
    fun getTargetIds(): List<String> = targetIds.toList()

    // ── Connection logic ──────────────────────────────────────────────────

    private fun connectDevice(deviceId: String) {
        scope.launch {
            val adapter = bluetoothAdapter ?: run {
                LOG_ERROR("[BleConnectionManager] BT adapter not available")
                return@launch
            }

            val device = runCatching { adapter.getRemoteDevice(deviceId) }.getOrNull() ?: run {
                LOG_ERROR("[BleConnectionManager] Invalid device ID: $deviceId")
                return@launch
            }

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
                connectDevice(deviceId)
            }
        }
    }

    // ── GATT callbacks ────────────────────────────────────────────────────

    private fun buildGattCallback(deviceId: String) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            LOG("[BleConnectionManager] onConnection State Change -> ${newState}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    LOG("[BleConnectionManager] Connected: ${gatt.device?.name ?: deviceId}")
                    gattMap[deviceId] = gatt
                    if (deviceId !in connectedIds) connectedIds.add(deviceId)
                    onConnected(deviceId, gatt)
                    
                    // Discover services for GATT writes
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    LOG("[BleConnectionManager] Disconnected: ${gatt.device?.name ?: deviceId}")
                    connectedIds.remove(deviceId)
                    val cachedGatt = gattMap.remove(deviceId)
                    onDisconnected(deviceId, cachedGatt ?: gatt)
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                    scheduleReconnect(deviceId)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            onServicesDiscovered?.invoke(deviceId, gatt, status)

            // Handle any write queued by writeWithoutResponse() while services were not yet cached
            val pending = pendingWrites.remove(deviceId) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pending.callback(Exception("GATT service discovery failed: $status"))
                return
            }
            val char = gatt.getService(pending.serviceUuid)?.getCharacteristic(pending.charUuid)
            if (char == null) {
                pending.callback(Exception("Characteristic not found: ${pending.charUuid}"))
                return
            }
            doWrite(gatt, char, pending.data, pending.callback)
        }
    }
}
