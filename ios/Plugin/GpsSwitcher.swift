import Foundation
import CoreBluetooth

/**
 * Manages GPS switch commands for BLE devices.
 *
 * When a device connects → sends GPS_OFF command (disable device GPS, phone takes over)
 * When a device disconnects or location fails → sends GPS_ON command (re-enable device GPS)
 *
 * Uses CoreBluetooth GATT writes to send commands to the device's characteristic.
 */
class GpsSwitcher: NSObject {
    
    // ── State ─────────────────────────────────────────────────────────────
    
    /// Maps bleDeviceId → BleCommand for GPS_OFF (onConnect)
    private var gpsOffCommands: [String: BleCommand] = [:]
    
    /// Maps bleDeviceId → BleCommand for GPS_ON (onDisconnect)
    private var gpsOnCommands: [String: BleCommand] = [:]
    
    /// Maps bleDeviceId → CBPeripheral reference (needed for GATT writes)
    private var peripherals: [String: CBPeripheral] = [:]
    
    /// Maps bleDeviceId → discovered GATT characteristics
    private var characteristics: [String: [CBUUID: CBCharacteristic]] = [:]
    
    /// Pending commands waiting for service discovery
    private var pendingCommands: [String: BleCommand] = [:]
    
    /// Delay before sending command after connection (seconds)
    private let commandDelay: TimeInterval = 3.0
    
    // ── Init ──────────────────────────────────────────────────────────────
    
    override init() {
        super.init()
    }
    
    // ── Public API ────────────────────────────────────────────────────────
    
    /// Register commands for a device
    func registerDevice(bleDeviceId: String, onConnect: BleCommand?, onDisconnect: BleCommand?) {
        if let cmd = onConnect { gpsOffCommands[bleDeviceId] = cmd }
        if let cmd = onDisconnect { gpsOnCommands[bleDeviceId] = cmd }
    }
    
    /// Unregister a device
    func unregisterDevice(bleDeviceId: String) {
        gpsOffCommands.removeValue(forKey: bleDeviceId)
        gpsOnCommands.removeValue(forKey: bleDeviceId)
        peripherals.removeValue(forKey: bleDeviceId)
        characteristics.removeValue(forKey: bleDeviceId)
        pendingCommands.removeValue(forKey: bleDeviceId)
    }
    
    /// Called when a BLE device connects - sends GPS_OFF command after delay
    func onDeviceConnected(bleDeviceId: String, peripheral: CBPeripheral) {
        peripherals[bleDeviceId] = peripheral
        
        guard let command = gpsOffCommands[bleDeviceId] else { return }
        
        DispatchQueue.main.asyncAfter(deadline: .now() + commandDelay) { [weak self] in
            self?.sendCommand(bleDeviceId: bleDeviceId, command: command, isGpsOff: true)
        }
    }
    
    /// Called when a BLE device disconnects - sends GPS_ON command
    func onDeviceDisconnected(bleDeviceId: String, peripheral: CBPeripheral) {
        guard let command = gpsOnCommands[bleDeviceId] else {
            peripherals.removeValue(forKey: bleDeviceId)
            characteristics.removeValue(forKey: bleDeviceId)
            return
        }
        
        DispatchQueue.main.asyncAfter(deadline: .now() + commandDelay) { [weak self] in
            self?.sendCommand(bleDeviceId: bleDeviceId, command: command, isGpsOff: false)
        }
    }
    
    /// Called when location report fails (no permission or no internet) - sends GPS_ON to all connected devices
    func onLocationReportFailed(connectedDeviceIds: [String]) {
        for bleDeviceId in connectedDeviceIds {
            guard let command = gpsOnCommands[bleDeviceId] else { continue }
            sendCommand(bleDeviceId: bleDeviceId, command: command, isGpsOff: false)
        }
    }
    
    // ── Private: GATT Write ───────────────────────────────────────────────
    
    private func sendCommand(bleDeviceId: String, command: BleCommand, isGpsOff: Bool) {
        guard let peripheral = peripherals[bleDeviceId] else { return }
        guard peripheral.state == .connected else { return }
        
        let serviceUUID = CBUUID(string: command.serviceUuid)
        let charUUID = CBUUID(string: command.characteristicUuid)
        
        if let deviceChars = characteristics[bleDeviceId],
           let characteristic = deviceChars[charUUID] {
            writeToCharacteristic(peripheral: peripheral, characteristic: characteristic, command: command)
        } else {
            pendingCommands[bleDeviceId] = command
            peripheral.delegate = self
            peripheral.discoverServices([serviceUUID])
        }
    }
    
    private func writeToCharacteristic(peripheral: CBPeripheral, characteristic: CBCharacteristic, command: BleCommand) {
        guard let data = command.value.data(using: .utf8) else { return }
        
        let writeType: CBCharacteristicWriteType = characteristic.properties.contains(.writeWithoutResponse)
            ? .withoutResponse
            : .withResponse
        
        peripheral.writeValue(data, for: characteristic, type: writeType)
        LOG("[GpsSwitcher] Command Sent: \(command.name)")
    }
    
    // ── Cleanup ───────────────────────────────────────────────────────────
    
    func cleanup() {
        gpsOffCommands.removeAll()
        gpsOnCommands.removeAll()
        peripherals.removeAll()
        characteristics.removeAll()
        pendingCommands.removeAll()
    }
}

// ── CBPeripheralDelegate ──────────────────────────────────────────────────────

extension GpsSwitcher: CBPeripheralDelegate {
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        
        if error != nil {
            pendingCommands.removeValue(forKey: deviceId)
            return
        }
        
        guard let command = pendingCommands[deviceId] else { return }
        
        let targetServiceUUID = CBUUID(string: command.serviceUuid)
        let targetCharUUID = CBUUID(string: command.characteristicUuid)
        
        guard let service = peripheral.services?.first(where: { $0.uuid == targetServiceUUID }) else {
            pendingCommands.removeValue(forKey: deviceId)
            return
        }
        
        peripheral.discoverCharacteristics([targetCharUUID], for: service)
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        let deviceId = peripheral.identifier.uuidString
        
        if error != nil {
            pendingCommands.removeValue(forKey: deviceId)
            return
        }
        
        guard let command = pendingCommands[deviceId] else { return }
        
        let targetCharUUID = CBUUID(string: command.characteristicUuid)
        
        guard let characteristic = service.characteristics?.first(where: { $0.uuid == targetCharUUID }) else {
            pendingCommands.removeValue(forKey: deviceId)
            return
        }
        
        if characteristics[deviceId] == nil {
            characteristics[deviceId] = [:]
        }
        characteristics[deviceId]?[targetCharUUID] = characteristic
        
        pendingCommands.removeValue(forKey: deviceId)
        writeToCharacteristic(peripheral: peripheral, characteristic: characteristic, command: command)
    }
    
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        // Silent - already logged in writeToCharacteristic
    }
}
