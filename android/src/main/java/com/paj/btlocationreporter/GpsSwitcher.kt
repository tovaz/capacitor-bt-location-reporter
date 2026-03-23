package com.paj.btlocationreporter

import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE GATT command to write to a characteristic.
 */
data class BleCommand(
    val name: String,
    val serviceUuid: String,
    val characteristicUuid: String,
    val value: String
)

/**
 * Manages GPS switch commands for BLE devices.
 *
 * When a device connects → sends GPS_OFF command (disable device GPS, phone takes over)
 * When a device disconnects or location fails → sends GPS_ON command (re-enable device GPS)
 *
 * Uses Android BluetoothGatt writes to send commands to the device's characteristic.
 */
class GpsSwitcher {

    // ── State ─────────────────────────────────────────────────────────────

    /** Maps deviceId → BleCommand for GPS_OFF (onConnect) */
    private val gpsOffCommands = ConcurrentHashMap<String, BleCommand>()

    /** Maps deviceId → BleCommand for GPS_ON (onDisconnect) */
    private val gpsOnCommands = ConcurrentHashMap<String, BleCommand>()

    /** Maps deviceId → BluetoothGatt reference (needed for GATT writes) */
    private val gattMap = ConcurrentHashMap<String, BluetoothGatt>()

    /** Maps deviceId → discovered GATT characteristics */
    private val characteristics = ConcurrentHashMap<String, MutableMap<UUID, BluetoothGattCharacteristic>>()

    /** Pending commands waiting for service discovery */
    private val pendingCommands = ConcurrentHashMap<String, BleCommand>()

    /** Delay before sending command after connection (milliseconds) */
    private val commandDelayMs: Long = 3_000

    private val handler = Handler(Looper.getMainLooper())

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Register commands for a device.
     */
    fun registerDevice(deviceId: String, onConnect: BleCommand?, onDisconnect: BleCommand?) {
        onConnect?.let { gpsOffCommands[deviceId] = it }
        onDisconnect?.let { gpsOnCommands[deviceId] = it }
    }

    /**
     * Unregister a device.
     */
    fun unregisterDevice(deviceId: String) {
        gpsOffCommands.remove(deviceId)
        gpsOnCommands.remove(deviceId)
        gattMap.remove(deviceId)
        characteristics.remove(deviceId)
        pendingCommands.remove(deviceId)
    }

    /**
     * Called when a BLE device connects - sends GPS_OFF command after delay.
     */
    fun onDeviceConnected(deviceId: String, gatt: BluetoothGatt) {
        gattMap[deviceId] = gatt

        val command = gpsOffCommands[deviceId] ?: return

        handler.postDelayed({
            sendCommand(deviceId, command)
        }, commandDelayMs)
    }

    /**
     * Called when a BLE device disconnects - sends GPS_ON command.
     */
    fun onDeviceDisconnected(deviceId: String, gatt: BluetoothGatt) {
        val command = gpsOnCommands[deviceId]
        if (command == null) {
            gattMap.remove(deviceId)
            characteristics.remove(deviceId)
            return
        }

        // Try to send GPS_ON (may fail if already disconnected)
        handler.postDelayed({
            sendCommand(deviceId, command)
        }, commandDelayMs)
    }

    /**
     * Called when location report fails - sends GPS_ON to all connected devices.
     */
    fun onLocationReportFailed(connectedDeviceIds: List<String>) {
        for (deviceId in connectedDeviceIds) {
            val command = gpsOnCommands[deviceId] ?: continue
            sendCommand(deviceId, command)
        }
    }

    /**
     * Handle services discovered callback from BleConnectionManager.
     */
    fun onServicesDiscovered(deviceId: String, gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            pendingCommands.remove(deviceId)
            return
        }

        val command = pendingCommands[deviceId] ?: return

        val targetServiceUuid = UUID.fromString(command.serviceUuid)
        val targetCharUuid = UUID.fromString(command.characteristicUuid)

        val service = gatt.getService(targetServiceUuid)
        if (service == null) {
            pendingCommands.remove(deviceId)
            return
        }

        val characteristic = service.getCharacteristic(targetCharUuid)
        if (characteristic == null) {
            pendingCommands.remove(deviceId)
            return
        }

        // Cache the characteristic
        characteristics.getOrPut(deviceId) { mutableMapOf() }[targetCharUuid] = characteristic

        pendingCommands.remove(deviceId)
        writeToCharacteristic(gatt, characteristic, command)
    }

    /**
     * Cleanup all state.
     */
    fun cleanup() {
        gpsOffCommands.clear()
        gpsOnCommands.clear()
        gattMap.clear()
        characteristics.clear()
        pendingCommands.clear()
    }

    // ── Private: GATT Write ───────────────────────────────────────────────

    private fun sendCommand(deviceId: String, command: BleCommand) {
        val gatt = gattMap[deviceId] ?: return

        val charUuid = UUID.fromString(command.characteristicUuid)

        // Check if we've already discovered the characteristic
        val deviceChars = characteristics[deviceId]
        val cachedChar = deviceChars?.get(charUuid)

        if (cachedChar != null) {
            writeToCharacteristic(gatt, cachedChar, command)
        } else {
            // Need to discover services first
            pendingCommands[deviceId] = command
            gatt.discoverServices()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeToCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        command: BleCommand
    ) {
        val data = command.value.toByteArray(Charsets.UTF_8)

        // Use deprecated API for broader compatibility (API < 33)
        characteristic.value = data
        characteristic.writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val success = gatt.writeCharacteristic(characteristic)
        if (success) {
            FileLogger.log("GpsSwitcher", "Command Sent: ${command.name}")
        } else {
            FileLogger.error("GpsSwitcher", "Failed to send: ${command.name}")
        }
    }
}
