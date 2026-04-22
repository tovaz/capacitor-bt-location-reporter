package com.paj.btlocationreporter

import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class BleCommand(
    val name: String,
    val serviceUuid: String,
    val characteristicUuid: String,
    val value: String
)

/**
 * Manages GPS_OFF / GPS_ON GATT commands for BLE devices.
 *
 * GPS_OFF (onConnectCommand) is sent only when ALL of the following are true:
 *   1. Phone has internet connectivity
 *   2. Location permission is granted AND location service is enabled
 *   3. The BLE device is connected
 *
 * When any condition fails, GPS_ON is sent to ALL connected devices so they
 * resume their own location tracking.
 *
 * [canSendGpsOff] is a lambda provided by the service that evaluates conditions 1 & 2.
 * [onConditionsChanged] must be called whenever internet or location state changes.
 */
class GpsSwitcher(
    private val canSendGpsOff: () -> Boolean = { true }
) {

    // ── State ─────────────────────────────────────────────────────────────

    private val gpsOffCommands  = ConcurrentHashMap<String, BleCommand>()
    private val gpsOnCommands   = ConcurrentHashMap<String, BleCommand>()
    private val gattMap         = ConcurrentHashMap<String, BluetoothGatt>()
    private val characteristics = ConcurrentHashMap<String, MutableMap<UUID, BluetoothGattCharacteristic>>()
    private val pendingCommands = ConcurrentHashMap<String, BleCommand>()

    /**
     * Devices currently in phone-tracking mode (received GPS_OFF).
     * Cleared when GPS_ON is sent.
     */
    private val gpsOffSentDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val commandDelayMs: Long = 3_000
    private val handler = Handler(Looper.getMainLooper())

    // ── Public API ────────────────────────────────────────────────────────

    fun registerDevice(deviceId: String, onConnect: BleCommand?, onDisconnect: BleCommand?) {
        FileLogger.log("GpsSwitcher", "registerDevice: $deviceId onConnect=${onConnect?.name} onDisconnect=${onDisconnect?.name}")
        onConnect?.let    { gpsOffCommands[deviceId] = it }
        onDisconnect?.let { gpsOnCommands[deviceId]  = it }
    }

    fun unregisterDevice(deviceId: String) {
        FileLogger.log("GpsSwitcher", "unregisterDevice: $deviceId")
        gpsOffCommands.remove(deviceId)
        gpsOnCommands.remove(deviceId)
        gattMap.remove(deviceId)
        characteristics.remove(deviceId)
        pendingCommands.remove(deviceId)
        gpsOffSentDevices.remove(deviceId)
    }

    /**
     * Called when a BLE device connects.
     * After [commandDelayMs], evaluates [canSendGpsOff]:
     *   - true  → GPS_OFF (phone tracks for the device)
     *   - false → GPS_ON  (device keeps its own GPS active)
     */
    fun onDeviceConnected(deviceId: String, gatt: BluetoothGatt) {
        FileLogger.log("GpsSwitcher", "onDeviceConnected: $deviceId")
        gattMap[deviceId] = gatt

        val gpsOffCmd = gpsOffCommands[deviceId]
        val gpsOnCmd  = gpsOnCommands[deviceId]

        if (gpsOffCmd == null && gpsOnCmd == null) {
            FileLogger.log("GpsSwitcher", "onDeviceConnected: no commands registered for $deviceId")
            return
        }

        handler.postDelayed({
            if (!gattMap.containsKey(deviceId)) {
                FileLogger.log("GpsSwitcher", "onDeviceConnected: $deviceId no longer connected, skipping")
                return@postDelayed
            }
            val ok = canSendGpsOff()
            FileLogger.log("GpsSwitcher", "onDeviceConnected: canSendGpsOff=$ok for $deviceId")
            if (ok && gpsOffCmd != null) {
                FileLogger.log("GpsSwitcher", "onDeviceConnected: sending GPS_OFF to $deviceId")
                sendCommand(deviceId, gpsOffCmd)
                gpsOffSentDevices.add(deviceId)
            } else if (gpsOnCmd != null) {
                // Conditions not met — ensure device GPS is ON
                FileLogger.log("GpsSwitcher", "onDeviceConnected: conditions not met, sending GPS_ON to $deviceId")
                sendCommand(deviceId, gpsOnCmd)
                gpsOffSentDevices.remove(deviceId)
            }
        }, commandDelayMs)
    }

    /**
     * Called when a BLE device disconnects. Sends GPS_ON after delay.
     */
    fun onDeviceDisconnected(deviceId: String, gatt: BluetoothGatt) {
        FileLogger.log("GpsSwitcher", "onDeviceDisconnected: $deviceId")
        gpsOffSentDevices.remove(deviceId)

        val command = gpsOnCommands[deviceId]
        if (command == null) {
            FileLogger.log("GpsSwitcher", "onDeviceDisconnected: no GPS_ON command for $deviceId")
            gattMap.remove(deviceId)
            return
        }

        handler.postDelayed({
            FileLogger.log("GpsSwitcher", "onDeviceDisconnected: sending GPS_ON to $deviceId")
            sendCommand(deviceId, command)
        }, commandDelayMs)
    }

    /**
     * Called when location report fails. Sends GPS_ON immediately to all connected devices.
     */
    fun onLocationReportFailed(connectedDeviceIds: List<String>) {
        FileLogger.log("GpsSwitcher", "onLocationReportFailed: ${connectedDeviceIds.joinToString()}")
        for (deviceId in connectedDeviceIds) {
            val command = gpsOnCommands[deviceId] ?: continue
            sendCommand(deviceId, command)
            gpsOffSentDevices.remove(deviceId)
        }
    }

    /**
     * Called when internet or location state changes.
     *
     * - allGood=true  → send GPS_OFF to connected devices not yet in phone-tracking mode.
     * - allGood=false → send GPS_ON  to ALL connected devices (regardless of prior state),
     *                   so devices always have their GPS active when the phone cannot track.
     */
    fun onConditionsChanged(allGood: Boolean, connectedIds: List<String>) {
        FileLogger.log("GpsSwitcher", "onConditionsChanged: allGood=$allGood devices=${connectedIds.joinToString()}")
        for (deviceId in connectedIds) {
            if (allGood) {
                // Conditions restored — send GPS_OFF to devices not yet in phone-tracking mode
                if (deviceId !in gpsOffSentDevices) {
                    val cmd = gpsOffCommands[deviceId] ?: continue
                    FileLogger.log("GpsSwitcher", "onConditionsChanged: scheduling GPS_OFF for $deviceId")
                    handler.postDelayed({
                        if (gattMap.containsKey(deviceId) && deviceId !in gpsOffSentDevices) {
                            sendCommand(deviceId, cmd)
                            gpsOffSentDevices.add(deviceId)
                        }
                    }, commandDelayMs)
                }
            } else {
                // Conditions lost — send GPS_ON to ALL connected devices so they resume their own GPS
                val cmd = gpsOnCommands[deviceId]
                if (cmd != null) {
                    FileLogger.log("GpsSwitcher", "onConditionsChanged: sending GPS_ON to $deviceId (conditions lost)")
                    sendCommand(deviceId, cmd)
                } else {
                    FileLogger.log("GpsSwitcher", "onConditionsChanged: no GPS_ON command for $deviceId")
                }
                gpsOffSentDevices.remove(deviceId)
            }
        }
    }

    fun onServicesDiscovered(deviceId: String, gatt: BluetoothGatt, status: Int) {
        FileLogger.log("GpsSwitcher", "onServicesDiscovered: $deviceId status=$status")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            FileLogger.error("GpsSwitcher", "onServicesDiscovered: GATT error for $deviceId status=$status")
            pendingCommands.remove(deviceId)
            return
        }

        val command = pendingCommands[deviceId] ?: run {
            FileLogger.log("GpsSwitcher", "onServicesDiscovered: no pending command for $deviceId")
            return
        }

        val service = gatt.getService(UUID.fromString(command.serviceUuid))
        if (service == null) {
            FileLogger.error("GpsSwitcher", "onServicesDiscovered: service not found uuid=${command.serviceUuid}")
            pendingCommands.remove(deviceId)
            return
        }

        val charUuid = UUID.fromString(command.characteristicUuid)
        val characteristic = service.getCharacteristic(charUuid)
        if (characteristic == null) {
            FileLogger.error("GpsSwitcher", "onServicesDiscovered: characteristic not found uuid=${command.characteristicUuid}")
            pendingCommands.remove(deviceId)
            return
        }

        characteristics.getOrPut(deviceId) { mutableMapOf() }[charUuid] = characteristic
        pendingCommands.remove(deviceId)
        writeToCharacteristic(gatt, characteristic, command)
    }

    fun cleanup() {
        FileLogger.log("GpsSwitcher", "cleanup()")
        handler.removeCallbacksAndMessages(null)
        gpsOffCommands.clear()
        gpsOnCommands.clear()
        gattMap.clear()
        characteristics.clear()
        pendingCommands.clear()
        gpsOffSentDevices.clear()
    }

    // ── Private GATT write ────────────────────────────────────────────────

    private fun sendCommand(deviceId: String, command: BleCommand) {
        FileLogger.log("GpsSwitcher", "sendCommand: $deviceId command=${command.name}")
        val gatt = gattMap[deviceId] ?: run {
            FileLogger.error("GpsSwitcher", "sendCommand: no gatt for $deviceId")
            return
        }

        val charUuid = UUID.fromString(command.characteristicUuid)
        val cached = characteristics[deviceId]?.get(charUuid)

        if (cached != null) {
            writeToCharacteristic(gatt, cached, command)
        } else {
            FileLogger.log("GpsSwitcher", "sendCommand: discovering services for $deviceId")
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
        characteristic.value = data
        characteristic.writeType = if (characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val success = gatt.writeCharacteristic(characteristic)
        if (success) FileLogger.info("GpsSwitcher", "Command sent: ${command.name}")
        else         FileLogger.error("GpsSwitcher", "Failed to send: ${command.name}")
    }
}
