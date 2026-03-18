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

    private let onConnected:    (String) -> Void
    private let onDisconnected: (String) -> Void

    private let reconnectDelay: TimeInterval = 3.0

    // ── Init ──────────────────────────────────────────────────────────────

    init(deviceIds: [String],
         onConnected:    @escaping (String) -> Void,
         onDisconnected: @escaping (String) -> Void) {
        self.onConnected    = onConnected
        self.onDisconnected = onDisconnected
        super.init()
        
        LOG("BleManager init with \(deviceIds.count) device IDs")
        deviceIds.forEach { LOG("  Target device: \($0)") }

        self.targetUUIDs = deviceIds.compactMap { UUID(uuidString: $0) }
        LOG("  Parsed \(self.targetUUIDs.count) valid UUIDs")

        // CBCentralManagerOptionRestoreIdentifierKey enables background state restoration
        LOG("Creating CBCentralManager...")
        central = CBCentralManager(
            delegate: self,
            queue: DispatchQueue.global(qos: .background),
            options: [CBCentralManagerOptionRestoreIdentifierKey: "com.paj.btlocationreporter.central"]
        )
        LOG("CBCentralManager created")
    }

    // ── Public API ────────────────────────────────────────────────────────

    func start() {
        LOG("BleManager.start() - central.state = \(central.state.rawValue)")
        // Central may already be powered on (restored state)
        if central.state == .poweredOn {
            LOG("  Central already powered on, connecting to known devices")
            connectAllKnown()
        } else {
            LOG("  Central not ready yet, waiting for centralManagerDidUpdateState")
        }
        // Otherwise wait for centralManagerDidUpdateState
    }

    func stop() {
        LOG("BleManager.stop() - disconnecting all peripherals")
        peripheralMap.values.forEach { central.cancelPeripheralConnection($0) }
        peripheralMap.removeAll()
        connectedIds.removeAll()
        LOG("  All peripherals disconnected")
    }

    func addDevices(_ ids: [String]) {
        LOG("BleManager.addDevices() with \(ids.count) IDs")
        let newUUIDs = ids.compactMap { UUID(uuidString: $0) }.filter { !targetUUIDs.contains($0) }
        targetUUIDs.append(contentsOf: newUUIDs)
        LOG("  Added \(newUUIDs.count) new UUIDs to targets")
        if central.state == .poweredOn {
            connectUUIDs(newUUIDs)
        }
    }

    func removeDevices(_ ids: [String]) {
        LOG("BleManager.removeDevices() with \(ids.count) IDs")
        let toRemove = Set(ids.compactMap { UUID(uuidString: $0) })
        targetUUIDs.removeAll { toRemove.contains($0) }
        toRemove.forEach { uuid in
            if let p = peripheralMap[uuid] {
                LOG("  Disconnecting peripheral: \(uuid.uuidString)")
                central.cancelPeripheralConnection(p)
            }
            peripheralMap.removeValue(forKey: uuid)
            connectedIds.removeAll { $0 == uuid.uuidString }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private func connectAllKnown() {
        LOG("connectAllKnown() - \(targetUUIDs.count) targets")
        connectUUIDs(targetUUIDs)
    }

    private func connectUUIDs(_ uuids: [UUID]) {
        LOG("connectUUIDs() - attempting to connect \(uuids.count) UUIDs")
        // retrievePeripherals registers the UUIDs so iOS can wake the app when they're near
        let known = central.retrievePeripherals(withIdentifiers: uuids)
        LOG("  Retrieved \(known.count) known peripherals from system")
        for peripheral in known {
            LOG("  Connecting to known peripheral: \(peripheral.identifier.uuidString), name=\(peripheral.name ?? "unknown")")
            peripheralMap[peripheral.identifier] = peripheral
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }

        // For UUIDs not yet seen, scan briefly (will work in foreground)
        let unknownUUIDs = Set(uuids).subtracting(Set(known.map { $0.identifier }))
        if !unknownUUIDs.isEmpty {
            LOG("  \(unknownUUIDs.count) UUIDs not found, starting scan...")
            // Scan for CBUU IDs of all services — we just want to find the peripheral by UUID
            central.scanForPeripherals(withServices: nil, options: nil)
            DispatchQueue.global().asyncAfter(deadline: .now() + 10) { [weak self] in
                LOG("  Stopping scan after 10 seconds")
                self?.central.stopScan()
            }
        }
    }

    private func scheduleReconnect(_ peripheral: CBPeripheral) {
        guard targetUUIDs.contains(peripheral.identifier) else {
            LOG("scheduleReconnect() - peripheral \(peripheral.identifier.uuidString) not in targets, skipping")
            return
        }
        LOG("scheduleReconnect() - will retry in \(reconnectDelay)s for \(peripheral.identifier.uuidString)")
        DispatchQueue.global(qos: .background).asyncAfter(deadline: .now() + reconnectDelay) { [weak self] in
            guard let self, self.targetUUIDs.contains(peripheral.identifier) else { return }
            LOG("scheduleReconnect() - retrying connection to \(peripheral.identifier.uuidString)")
            self.central.connect(peripheral, options: nil)
        }
    }
}

// ── CBCentralManagerDelegate ──────────────────────────────────────────────────

extension BleManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let stateNames = ["unknown", "resetting", "unsupported", "unauthorized", "poweredOff", "poweredOn"]
        let stateName = central.state.rawValue < stateNames.count ? stateNames[central.state.rawValue] : "invalid"
        LOG("centralManagerDidUpdateState: \(stateName) (rawValue=\(central.state.rawValue))")
        
        if central.state == .poweredOn {
            LOG("  Bluetooth is ON, connecting to known devices")
            connectAllKnown()
        } else if central.state == .unauthorized {
            LOG("  ERROR: Bluetooth permission not granted")
        } else if central.state == .poweredOff {
            LOG("  WARNING: Bluetooth is OFF")
        }
    }

    // State restoration — iOS relaunches app in background and passes back the saved state
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        LOG("willRestoreState called - app was relaunched by iOS")
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            LOG("  Restoring \(peripherals.count) peripherals")
            for p in peripherals {
                LOG("  Restored peripheral: \(p.identifier.uuidString), state=\(p.state.rawValue)")
                p.delegate = self
                peripheralMap[p.identifier] = p
                if p.state != .connected {
                    LOG("    Reconnecting...")
                    central.connect(p, options: nil)
                }
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        let id = peripheral.identifier.uuidString
        LOG("didConnect: \(id), name=\(peripheral.name ?? "unknown")")
        if !connectedIds.contains(id) { connectedIds.append(id) }
        LOG("  Total connected devices: \(connectedIds.count)")
        onConnected(id)
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        let id = peripheral.identifier.uuidString
        LOG("didDisconnect: \(id), error=\(error?.localizedDescription ?? "none")")
        connectedIds.removeAll { $0 == id }
        LOG("  Total connected devices: \(connectedIds.count)")
        onDisconnected(id)
        scheduleReconnect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        LOG("didDiscover: \(peripheral.identifier.uuidString), name=\(peripheral.name ?? "unknown"), rssi=\(RSSI)")
        guard targetUUIDs.contains(peripheral.identifier),
              peripheralMap[peripheral.identifier] == nil else {
            LOG("  Ignoring - not a target or already known")
            return
        }
        LOG("  Found target device! Stopping scan and connecting...")
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
