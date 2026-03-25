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
        FileLogger.log("GpsSwitcher", "registerDevice: $deviceId onConnect=${onConnect?.name} onDisconnect=${onDisconnect?.name}")
        onConnect?.let { gpsOffCommands[deviceId] = it }
        onDisconnect?.let { gpsOnCommands[deviceId] = it }
    }

    /**
     * Unregister a device.
     */
    fun unregisterDevice(deviceId: String) {
        FileLogger.log("GpsSwitcher", "unregisterDevice: $deviceId")
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
        FileLogger.log("GpsSwitcher", "onDeviceConnected: $deviceId")
        gattMap[deviceId] = gatt

        val offList = gpsOffCommands.entries.joinToString { "${it.key}=${it.value.name}" }
        val onList = gpsOnCommands.entries.joinToString { "${it.key}=${it.value.name}" }
        // FileLogger.log("GpsSwitcher", "gpsOffCommands: [$offList]")
        // FileLogger.log("GpsSwitcher", "gpsOnCommands: [$onList]")
        // FileLogger.info("GpsSwitcher", "Id: $deviceId")

        val command = gpsOffCommands[deviceId]
        if (command == null) {
            FileLogger.log("GpsSwitcher", "onDeviceConnected: No GPS_OFF command for $deviceId")
            return
        }

        handler.postDelayed({
            FileLogger.log("GpsSwitcher", "onDeviceConnected: Sending GPS_OFF command to $deviceId after delay")
            sendCommand(deviceId, command)
        }, commandDelayMs)
    }

    /**
     * Called when a BLE device disconnects - sends GPS_ON command.
     */
    fun onDeviceDisconnected(deviceId: String, gatt: BluetoothGatt) {
        FileLogger.log("GpsSwitcher", "onDeviceDisconnected: $deviceId")
        val command = gpsOnCommands[deviceId]
        if (command == null) {
            FileLogger.log("GpsSwitcher", "onDeviceDisconnected: No GPS_ON command for $deviceId")
            gattMap.remove(deviceId)
            // characteristics.remove(deviceId)
            return
        }

        // Try to send GPS_ON (may fail if already disconnected)
        handler.postDelayed({
            FileLogger.log("GpsSwitcher", "onDeviceDisconnected: Sending GPS_ON command to $deviceId after delay")
            sendCommand(deviceId, command)
        }, commandDelayMs)
    }

    /**
     * Called when location report fails - sends GPS_ON to all connected devices.
     */
    fun onLocationReportFailed(connectedDeviceIds: List<String>) {
        FileLogger.log("GpsSwitcher", "onLocationReportFailed: ${connectedDeviceIds.joinToString()}")
        for (deviceId in connectedDeviceIds) {
            val command = gpsOnCommands[deviceId]
            if (command == null) {
                FileLogger.log("GpsSwitcher", "onLocationReportFailed: No GPS_ON command for $deviceId")
                continue
            }
            sendCommand(deviceId, command)
        }
    }

    /**
     * Handle services discovered callback from BleConnectionManager.
     */
    fun onServicesDiscovered(deviceId: String, gatt: BluetoothGatt, status: Int) {
        FileLogger.log("GpsSwitcher", "onServicesDiscovered: $deviceId status=$status")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            FileLogger.error("GpsSwitcher", "onServicesDiscovered: GATT error for $deviceId status=$status")
            pendingCommands.remove(deviceId)
            return
        }

        val command = pendingCommands[deviceId]
        if (command == null) {
            FileLogger.log("GpsSwitcher", "onServicesDiscovered: No pending command for $deviceId")
            return
        }

        val targetServiceUuid = UUID.fromString(command.serviceUuid)
        val targetCharUuid = UUID.fromString(command.characteristicUuid)

        val service = gatt.getService(targetServiceUuid)
        if (service == null) {
            FileLogger.error("GpsSwitcher", "onServicesDiscovered: Service not found for $deviceId serviceUuid=${command.serviceUuid}")
            pendingCommands.remove(deviceId)
            return
        }

        val characteristic = service.getCharacteristic(targetCharUuid)
        if (characteristic == null) {
            FileLogger.error("GpsSwitcher", "onServicesDiscovered: Characteristic not found for $deviceId charUuid=${command.characteristicUuid}")
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
        FileLogger.log("GpsSwitcher", "cleanup() called")
        gpsOffCommands.clear()
        gpsOnCommands.clear() // Not necesary to clear this list.
        gattMap.clear()
        characteristics.clear()
        pendingCommands.clear()
    }

    // ── Private: GATT Write ───────────────────────────────────────────────

    private fun sendCommand(deviceId: String, command: BleCommand) {
        FileLogger.log("GpsSwitcher", "sendCommand: $deviceId command=${command.name}")
        // FileLogger.info("GpsSwitcher", "Command: $command")
        val gatt = gattMap[deviceId]
        if (gatt == null) {
            FileLogger.error("GpsSwitcher", "sendCommand: No gatt for $deviceId")
            return
        }

        val charUuid = UUID.fromString(command.characteristicUuid)

        // Check if we've already discovered the characteristic
        val deviceChars = characteristics[deviceId]
        val cachedChar = deviceChars?.get(charUuid)

        if (cachedChar != null) {
            FileLogger.log("GpsSwitcher", "sendCommand: Using cached characteristic for $deviceId")
            writeToCharacteristic(gatt, cachedChar, command)
        } else {
            FileLogger.log("GpsSwitcher", "sendCommand: Discovering services for $deviceId")
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
            FileLogger.info("GpsSwitcher", "Command Sent: ${command.name}")
        } else {
            FileLogger.error("GpsSwitcher", "Failed to send: ${command.name}")
        }
    }
}
