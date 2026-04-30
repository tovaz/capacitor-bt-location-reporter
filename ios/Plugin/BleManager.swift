import Foundation
import CoreBluetooth

/**
 * Manages BLE connections on iOS using CBCentralManager.
 *
 * Strategy:
 *  - [deviceIds] are UUID strings as required by CBCentralManager.retrievePeripherals(withIdentifiers:).
 *  - On start(), we call retrievePeripherals and connect immediately to any known peripherals.
 *  - If a connection drops, CBCentralManager fires centralManager(_:didDisconnectPeripheral:error:)
 *    and we schedule a reconnect.
 *  - [connectedIds] is the live set of confirmed connected peripheral UUIDs.
 *
 * The host app's Info.plist MUST include:
 *   UIBackgroundModes → bluetooth-central
 *
 * CBCentralManagerOptionRestoreIdentifierKey enables state restoration:
 * if the OS kills the app, iOS will relaunch it in background and restore
 * the CBCentralManager state when the peripheral comes into range.
 */
class BleManager: NSObject {

    // ── State ─────────────────────────────────────────────────────────────

    private var central: CBCentralManager!
    private var targetUUIDs     = [UUID]()
    private var peripheralMap   = [UUID: CBPeripheral]()   // uuid → peripheral ref
    private(set) var connectedIds = [String]()             // UUID strings

    private let onConnected:    (String, CBPeripheral) -> Void
    private let onDisconnected: (String, CBPeripheral) -> Void
    private var onBluetoothOff: (() -> Void)?

    private let reconnectDelay: TimeInterval = 3.0

    /// Retained write sessions (CBPeripheral holds only a weak delegate reference)
    private var activeSessions: [UUID: BleWriteSession] = [:]

    // ── Init ──────────────────────────────────────────────────────────────

    init(deviceIds: [String],
         onConnected:    @escaping (String, CBPeripheral) -> Void,
         onDisconnected: @escaping (String, CBPeripheral) -> Void,
         onBluetoothOff: (() -> Void)? = nil) {
        self.onConnected    = onConnected
        self.onDisconnected = onDisconnected
        self.onBluetoothOff = onBluetoothOff
        super.init()
        
        self.targetUUIDs = deviceIds.compactMap { UUID(uuidString: $0) }
        LOG("[BleManager] Init: \(self.targetUUIDs.count) devices")

        central = CBCentralManager(
            delegate: self,
            queue: DispatchQueue.global(qos: .background),
            options: [CBCentralManagerOptionRestoreIdentifierKey: "com.paj.btlocationreporter.central"]
        )
    }

    // ── Public API ────────────────────────────────────────────────────────

    /// Returns true when the Bluetooth radio is currently powered on.
    var isBluetoothEnabled: Bool { central.state == .poweredOn }

    func start() {
        if central.state == .poweredOn {
            connectAllKnown()
        }
    }

    func stop() {
        peripheralMap.values.forEach { central.cancelPeripheralConnection($0) }
        peripheralMap.removeAll()
        connectedIds.removeAll()
        LOG("[BleManager] Stopped")
    }

    func addDevices(_ ids: [String]) {
        let newUUIDs = ids.compactMap { UUID(uuidString: $0) }.filter { !targetUUIDs.contains($0) }
        targetUUIDs.append(contentsOf: newUUIDs)
        if central.state == .poweredOn {
            connectUUIDs(newUUIDs)
        }
    }

    func removeDevices(_ ids: [String]) {
        let toRemove = Set(ids.compactMap { UUID(uuidString: $0) })
        targetUUIDs.removeAll { toRemove.contains($0) }
        toRemove.forEach { uuid in
            if let p = peripheralMap[uuid] { central.cancelPeripheralConnection(p) }
            peripheralMap.removeValue(forKey: uuid)
            connectedIds.removeAll { $0 == uuid.uuidString }
        }
    }

    /// Writes `data` to `characteristicUUID` on `deviceId` without expecting a response.
    /// Performs GATT service/characteristic discovery automatically if needed.
    func writeWithoutResponse(deviceId: String,
                              serviceUUID: CBUUID,
                              characteristicUUID: CBUUID,
                              data: Data,
                              completion: @escaping (Error?) -> Void) {
        guard let peripheral = peripheralMap.values.first(where: { $0.identifier.uuidString == deviceId }),
              peripheral.state == .connected else {
            completion(NSError(domain: "BleManager", code: 0,
                               userInfo: [NSLocalizedDescriptionKey: "Device not connected: \(deviceId)"]))
            return
        }

        let sessionId = UUID()
        let session = BleWriteSession(
            peripheral: peripheral,
            service: serviceUUID,
            characteristic: characteristicUUID,
            data: data,
            completion: { [weak self] error in
                self?.activeSessions.removeValue(forKey: sessionId)
                completion(error)
            }
        )
        activeSessions[sessionId] = session
        session.execute()
        LOG("[BleManager] writeWithoutResponse queued: device=\(deviceId) service=\(serviceUUID) char=\(characteristicUUID) bytes=\(data.count)")
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private func connectAllKnown() {
        connectUUIDs(targetUUIDs)
    }

    private func connectUUIDs(_ uuids: [UUID]) {
        let known = central.retrievePeripherals(withIdentifiers: uuids)
        for peripheral in known {
            peripheralMap[peripheral.identifier] = peripheral
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }

        let unknownUUIDs = Set(uuids).subtracting(Set(known.map { $0.identifier }))
        if !unknownUUIDs.isEmpty {
            central.scanForPeripherals(withServices: nil, options: nil)
            DispatchQueue.global().asyncAfter(deadline: .now() + 10) { [weak self] in
                self?.central.stopScan()
            }
        }
    }

    private func scheduleReconnect(_ peripheral: CBPeripheral) {
        guard targetUUIDs.contains(peripheral.identifier) else { return }
        DispatchQueue.global(qos: .background).asyncAfter(deadline: .now() + reconnectDelay) { [weak self] in
            guard let self, self.targetUUIDs.contains(peripheral.identifier) else { return }
            self.central.connect(peripheral, options: nil)
        }
    }
}

// ── BleWriteSession ───────────────────────────────────────────────────────────
//
// One-shot helper: discovers service/characteristic (if needed) on a connected
// peripheral, writes data with .withoutResponse, then calls the completion.
// BleManager holds a strong reference until the session finishes (CBPeripheral
// keeps only a weak delegate pointer so we must retain the session ourselves).

private class BleWriteSession: NSObject, CBPeripheralDelegate {

    private let peripheral:    CBPeripheral
    private let serviceUUID:   CBUUID
    private let charUUID:      CBUUID
    private let data:          Data
    private let completion:    (Error?) -> Void
    private weak var previousDelegate: CBPeripheralDelegate?

    init(peripheral: CBPeripheral,
         service: CBUUID,
         characteristic: CBUUID,
         data: Data,
         completion: @escaping (Error?) -> Void) {
        self.peripheral  = peripheral
        self.serviceUUID = service
        self.charUUID    = characteristic
        self.data        = data
        self.completion  = completion
        super.init()
    }

    func execute() {
        // Fast path: characteristic already discovered
        if let char = peripheral.services?
            .first(where: { $0.uuid == serviceUUID })?
            .characteristics?
            .first(where: { $0.uuid == charUUID }) {
            writeAndFinish(char)
            return
        }
        // Slow path: need GATT discovery
        previousDelegate   = peripheral.delegate
        peripheral.delegate = self
        peripheral.discoverServices([serviceUUID])
    }

    private func writeAndFinish(_ characteristic: CBCharacteristic) {
        peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
        peripheral.delegate = previousDelegate
        completion(nil)
    }

    // MARK: – CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error {
            peripheral.delegate = previousDelegate
            completion(error); return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            peripheral.delegate = previousDelegate
            completion(NSError(domain: "BleManager", code: 1,
                               userInfo: [NSLocalizedDescriptionKey: "Service not found: \(serviceUUID)"]))
            return
        }
        peripheral.discoverCharacteristics([charUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        if let error = error {
            peripheral.delegate = previousDelegate
            completion(error); return
        }
        guard let char = service.characteristics?.first(where: { $0.uuid == charUUID }) else {
            peripheral.delegate = previousDelegate
            completion(NSError(domain: "BleManager", code: 2,
                               userInfo: [NSLocalizedDescriptionKey: "Characteristic not found: \(charUUID)"]))
            return
        }
        writeAndFinish(char)
    }
}

// ── CBCentralManagerDelegate ──────────────────────────────────────────────────

extension BleManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            LOG("[BleManager] BT ON")
            connectAllKnown()
        } else if central.state == .unauthorized {
            LOG_ERROR("[BleManager] BT unauthorized")
        } else if central.state == .poweredOff {
            LOG_ERROR("[BleManager] BT OFF")
            // Snapshot before clearing so we can notify each device
            let snapshot = connectedIds
            connectedIds.removeAll()
            for deviceId in snapshot {
                if let uuid = UUID(uuidString: deviceId),
                   let peripheral = peripheralMap[uuid] {
                    onDisconnected(deviceId, peripheral)
                }
            }
            if !snapshot.isEmpty {
                onBluetoothOff?()
            }
        }
    }

    // State restoration — iOS relaunches app in background and passes back the saved state
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        LOG("[BleManager] State restored")
        // Guardar flag temporal para restauración automática
        UserDefaults.standard.set(true, forKey: "BtLocationReporterPlugin.pendingRestore")
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            for p in peripherals {
                p.delegate = self
                peripheralMap[p.identifier] = p
                if p.state != .connected { central.connect(p, options: nil) }
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let id = peripheral.identifier.uuidString
        LOG("[BleManager] Connected: \(peripheral.name ?? id)")
        if !connectedIds.contains(id) { connectedIds.append(id) }
        onConnected(id, peripheral)
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        let id = peripheral.identifier.uuidString
        LOG("[BleManager] Disconnected: \(peripheral.name ?? id)")
        // wasConnected = false means centralManagerDidUpdateState(.poweredOff) already emitted the event
        let wasConnected = connectedIds.contains(id)
        connectedIds.removeAll { $0 == id }
        if wasConnected {
            onDisconnected(id, peripheral)
        }
        scheduleReconnect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard targetUUIDs.contains(peripheral.identifier),
              peripheralMap[peripheral.identifier] == nil else { return }
        LOG("[BleManager] Discovered: \(peripheral.name ?? peripheral.identifier.uuidString)")
        central.stopScan()
        peripheral.delegate = self
        peripheralMap[peripheral.identifier] = peripheral
        central.connect(peripheral, options: nil)
    }
}

// ── CBPeripheralDelegate ──────────────────────────────────────────────────────

extension BleManager: CBPeripheralDelegate {
    // Minimal implementation — extend here to subscribe to GATT notifications
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {}
}
