import Foundation
import CoreBluetooth
import CoreLocation
import UserNotifications

/// BLE GATT command to write to a characteristic.
struct BleCommand {
    let name: String
    let serviceUuid: String
    let characteristicUuid: String
    let value: String
}

/// One entry in the devices list: the BLE UUID used for connection and the PAJ ID for reports.
struct BtDeviceEntry {
    let bleDeviceId: String
    let pajDeviceId: String
    let onConnectCommand: BleCommand?
    let onDisconnectCommand: BleCommand?
}

/// Notification texts configuration
struct NotificationTexts {
    let connectedHeader: String
    let connected: String
    let trackerHeader: String
    let tracker: String
    
    static let defaults = NotificationTexts(
        connectedHeader: "Device connected",
        connected: "{device} connected via Bluetooth, power saving activated",
        trackerHeader: "BT Location Reporter",
        tracker: "Tracking location in background…"
    )
}

/// Configuration passed from the JS layer.
struct BtLocationConfig {
    let devices: [BtDeviceEntry]
    let endpoint: String
    let authToken: String?
    let intervalMs: Double
    let extraFields: [String: Any]
    let debug: Bool
    let texts: NotificationTexts
    var bleDeviceIds: [String] { devices.map { $0.bleDeviceId } }
    /// Map from `bleDeviceId` to `pajDeviceId`. Uses `uniquingKeysWith:` to
    /// tolerate accidental duplicates in `devices` (e.g. when `addDevices`
    /// is called with an entry whose `bleDeviceId` was already present);
    /// the latest entry wins. `Dictionary(uniqueKeysWithValues:)` would
    /// crash on duplicates with "Fatal error: Duplicate values for key".
    var pajIdMap: [String: String] {
        Dictionary(
            devices.map { ($0.bleDeviceId, $0.pajDeviceId) },
            uniquingKeysWith: { _, new in new }
        )
    }
}

/// Internal helpers for deduplicating device entries by `bleDeviceId`.
/// Centralized here so `start()`, `addDevices()` and future call sites
/// behave the same way: if the same `bleDeviceId` appears more than once,
/// the last occurrence wins.
enum DeviceEntryDedup {

    /// Collapses `entries` so each `bleDeviceId` appears exactly once.
    /// When the same id shows up multiple times within the same list, the
    /// later entry replaces the earlier one. Relative order of first
    /// appearance is preserved.
    static func lastWins(_ entries: [BtDeviceEntry]) -> [BtDeviceEntry] {
        var byId: [String: BtDeviceEntry] = [:]
        var order: [String] = []
        for e in entries {
            if byId[e.bleDeviceId] == nil {
                order.append(e.bleDeviceId)
            }
            byId[e.bleDeviceId] = e
        }
        return order.compactMap { byId[$0] }
    }

    /// Merges `incoming` into `existing` using "last wins" semantics: any
    /// existing entry whose `bleDeviceId` appears in `incoming` is removed,
    /// then the (already deduped) `incoming` list is appended at the end.
    /// The internal order within `incoming` is also collapsed to last-wins.
    static func merge(existing: [BtDeviceEntry],
                      incoming: [BtDeviceEntry]) -> [BtDeviceEntry] {
        let incomingDeduped = lastWins(incoming)
        let incomingIds = Set(incomingDeduped.map { $0.bleDeviceId })
        let kept = existing.filter { !incomingIds.contains($0.bleDeviceId) }
        return kept + incomingDeduped
    }
}

/**
 * Main coordinator for iOS background session.
 * 
 * Design: Location-triggered reports (no timer needed).
 * When CLLocationManager delivers a new location, we send a report.
 * This works reliably in background because iOS keeps location updates active.
 *
 * Background modes required in Info.plist:
 *   UIBackgroundModes → bluetooth-central, location
 */
@MainActor
class BtLocationReporter: NSObject {

    private(set) var isRunning = false
    private weak var plugin: BtLocationReporterPlugin?
    public private(set) var config: BtLocationConfig?
    private var bleManager: BleManager?
    private var locationMgr: LocationReporter?
    private var gpsSwitcher: GpsSwitcher?
    private var dynamicPajIdMap: [String: String] = [:]
    private var locationPermissionRequested = false

    /// Retained bridge that feeds `LiveTrackingManager` events back into the
    /// plugin (for JS emission) and into `applyEffectiveInterval()` (for
    /// reconfiguring the `LocationReporter` throttle).
    private var liveTrackingBridge: LiveTrackingCoordinatorBridge?

    init(plugin: BtLocationReporterPlugin) {
        self.plugin = plugin
        super.init()
        LOG("[BtLocationReporter] Initialized")
    }

    // ── Public API ────────────────────────────────────────────────────────

    func start(config: BtLocationConfig, completion: @escaping (Error?) -> Void) {
        guard !isRunning else { completion(nil); return }

        LOG("[BtLocationReporter] [PERSIST] start(config:) called. Devices: \(config.devices.count), interval: \(config.intervalMs)ms, debug: \(config.debug)")
        self.config = config
        self.isRunning = true
        self.locationPermissionRequested = false

        // Enable debug logging
        FileLogger.shared.debugEnabled = config.debug

        LOG("[BtLocationReporter] Starting: \(config.devices.count) devices, interval=\(config.intervalMs)ms, debug=\(config.debug)")
        LOG("[BtLocationReporter] [PERSIST] start(config:) - after setting config and debugEnabled")

        // Show local notification about monitoring started
        showMonitoringStartedNotification()

        // 1. Setup location manager with callback (but don't request permission yet)
        locationMgr = LocationReporter()
        locationMgr?.setReportInterval(ms: config.intervalMs)
        locationMgr?.onLocationUpdate = { [weak self] location in
            Task { @MainActor in
                self?.onNewLocation(location)
            }
        }

        // 1.b. Wire up the live tracking bridge so shortened intervals take
        //      effect on the running LocationReporter and JS listeners get
        //      `liveTrackingStarted` / `liveTrackingStopped` events.
        let bridge = LiveTrackingCoordinatorBridge(
            plugin: plugin,
            onIntervalChanged: { [weak self] in
                Task { @MainActor in self?.applyEffectiveInterval() }
            }
        )
        self.liveTrackingBridge = bridge
        LiveTrackingManager.shared.listener = bridge
        // Make sure any leftover sessions from a previous start are gone.
        LiveTrackingManager.shared.clear()
        
        // 2. Setup GPS Switcher for GATT commands
        self.gpsSwitcher = GpsSwitcher()
        for device in config.devices {
            self.gpsSwitcher?.registerDevice(
                bleDeviceId: device.bleDeviceId,
                onConnect: device.onConnectCommand,
                onDisconnect: device.onDisconnectCommand
            )
        }
        
        // 3. Setup BLE manager (stays connected in background)
        // Location permission will be requested when first device connects
        self.bleManager = BleManager(
            deviceIds: config.bleDeviceIds,
            onConnected: { [weak self] id, peripheral in self?.handleBleConnected(id, peripheral: peripheral) },
            onDisconnected: { [weak self] id, peripheral in self?.handleBleDisconnected(id, peripheral: peripheral) },
            onBluetoothOff: { [weak self] in self?.handleBluetoothOff() }
        )
        self.bleManager?.start()
        
        LOG("[BtLocationReporter] Started successfully (location paused until BLE connects and permission granted)")
        completion(nil)
    }
    
    func requestLocationPermission(completion: @escaping (Bool) -> Void) {
        LOG("[BtLocationReporter] Requesting location permission")
        locationMgr?.requestAlwaysPermission { [weak self] granted in
            guard let self else { return }
            if granted {
                LOG("[BtLocationReporter] Location permission granted")
                // If we have connected devices, start tracking
                if let connectedIds = self.bleManager?.connectedIds, !connectedIds.isEmpty {
                    self.locationMgr?.resume()
                }
            } else {
                LOG_ERROR("[BtLocationReporter] Location permission denied")
            }
            completion(granted)
        }
    }

    func stop() {
        isRunning = false
        bleManager?.stop()
        bleManager = nil
        locationMgr?.stop()
        locationMgr = nil
        gpsSwitcher?.cleanup()
        gpsSwitcher = nil

        // Tear down the live tracking bridge so no residual timers or
        // listeners survive the stop. All sessions are explicitly cleared
        // (in-memory only — nothing is persisted).
        if LiveTrackingManager.shared.listener === liveTrackingBridge {
            LiveTrackingManager.shared.listener = nil
        }
        LiveTrackingManager.shared.clear()
        liveTrackingBridge = nil

        LOG("[BtLocationReporter] Stopped")
    }

    // MARK: - Live tracking helpers

    /**
     * Re-applies the effective reporting interval (minimum of the
     * configured default and every active live-tracking session) to the
     * running `LocationReporter`. Safe to call from any state — if the
     * location manager is not yet initialized this is a no-op.
     */
    func applyEffectiveInterval() {
        guard let defaultMs = config?.intervalMs else { return }
        let effectiveMs = LiveTrackingManager.shared.getEffectiveIntervalMs(defaultMs: defaultMs)
        locationMgr?.setReportInterval(ms: effectiveMs)
        LOG("[BtLocationReporter] effective report interval = \(effectiveMs)ms (default=\(defaultMs)ms)")
    }

    func addDevices(_ entries: [BtDeviceEntry]) {
        // Defensive: even if the caller already deduped, collapse one more
        // time so downstream components (BleManager, GpsSwitcher) never see
        // the same bleDeviceId twice in a single batch.
        let deduped = DeviceEntryDedup.lastWins(entries)
        bleManager?.addDevices(deduped.map { $0.bleDeviceId })
        deduped.forEach {
            dynamicPajIdMap[$0.bleDeviceId] = $0.pajDeviceId
            gpsSwitcher?.registerDevice(bleDeviceId: $0.bleDeviceId, onConnect: $0.onConnectCommand, onDisconnect: $0.onDisconnectCommand)
        }
        LOG("[BtLocationReporter] Added \(deduped.count) devices (last-wins dedup)")
    }

    func writeWithoutResponse(deviceId: String,
                              serviceUUID: CBUUID,
                              characteristicUUID: CBUUID,
                              data: Data,
                              completion: @escaping (Error?) -> Void) {
        guard let bleManager = bleManager else {
            completion(NSError(domain: "BtLocationReporter", code: 0,
                               userInfo: [NSLocalizedDescriptionKey: "BLE not started"]))
            return
        }
        bleManager.writeWithoutResponse(deviceId: deviceId,
                                        serviceUUID: serviceUUID,
                                        characteristicUUID: characteristicUUID,
                                        data: data,
                                        completion: completion)
    }

    func removeDevices(_ entries: [BtDeviceEntry]) {
        let bleIds = entries.map { $0.bleDeviceId }
        bleManager?.removeDevices(bleIds)
        bleIds.forEach {
            dynamicPajIdMap.removeValue(forKey: $0)
            gpsSwitcher?.unregisterDevice(bleDeviceId: $0)
        }
        LOG("[BtLocationReporter] Removed \(entries.count) devices")
        
        // Check if we need to pause location after removing devices
        if let connectedIds = bleManager?.connectedIds, connectedIds.isEmpty {
            // locationMgr?.pause()
            // locationMgr?.enableLowPowerMode()
        }
    }

    // ── Location callback (triggered by CLLocationManager, works in background) ──

    private func onNewLocation(_ location: CLLocation) {
        guard isRunning, let config = config else { return }
        guard let connectedIds = bleManager?.connectedIds, !connectedIds.isEmpty else {
            // No BLE devices connected - skip silently
            return
        }
        
        sendReport(config: config, location: location, connectedIds: connectedIds)
    }

    // ── BLE handlers ──────────────────────────────────────────────────────

    private func handleBleConnected(_ deviceId: String, peripheral: CBPeripheral) {
        let wasEmpty = true; //(bleManager?.connectedIds.count ?? 0) == 1  // Just became 1 (this device)

        LOG("[BtLocationReporter] BLE connected: \(deviceId)")
        LOG("[BtLocationReporter] [PERSIST] handleBleConnected: deviceId=\(deviceId)")
        gpsSwitcher?.onDeviceConnected(bleDeviceId: deviceId, peripheral: peripheral)
        plugin?.emitBleConnection(deviceId: deviceId, connected: true)

        // Show local notification about BLE connection
        showBleConnectionNotification(deviceId: deviceId, deviceName: peripheral.name)

        // Resume location tracking when first device connects (if we have permission)
        if wasEmpty {
            if locationMgr?.hasPermission == true {
                LOG("[BtLocationReporter] First device connected — resuming location")
                LOG("[BtLocationReporter] [PERSIST] handleBleConnected: calling locationMgr.resume()")
                locationMgr?.resume()
            } else if !locationPermissionRequested {
                // First BLE connected but no location permission - request it automatically
                locationPermissionRequested = true
                LOG("[BtLocationReporter] First device connected — auto-requesting location permission")
                LOG("[BtLocationReporter] [PERSIST] handleBleConnected: requesting location permission")
                plugin?.emitLocationPermissionRequired()

                // Automatically request permission (iOS shows system dialog)
                locationMgr?.requestAlwaysPermission { [weak self] granted in
                    if granted {
                        LOG("[BtLocationReporter] Location permission granted — starting tracking")
                        LOG("[BtLocationReporter] [PERSIST] location permission granted, calling locationMgr.resume()")
                        self?.locationMgr?.resume()
                    } else {
                        LOG_ERROR("[BtLocationReporter] Location permission denied")
                        LOG("[BtLocationReporter] [PERSIST] location permission denied")
                    }
                }
            }
        }
    }

    private func handleBleDisconnected(_ deviceId: String, peripheral: CBPeripheral) {
        LOG("[BtLocationReporter] BLE disconnected: \(deviceId)")
        gpsSwitcher?.onDeviceDisconnected(bleDeviceId: deviceId, peripheral: peripheral)
        plugin?.emitBleConnection(deviceId: deviceId, connected: false)
        
        // Pause location tracking when all devices disconnected
        if let connectedIds = bleManager?.connectedIds, connectedIds.isEmpty {
            LOG("[BtLocationReporter] All devices disconnected — pausing location")
            // locationMgr?.pause()
            // locationMgr?.enableLowPowerMode()
        }
    }
    
    private func handleBluetoothOff() {
        LOG("[BtLocationReporter] Bluetooth OFF — pausing location")
        // locationMgr?.pause()
        // locationMgr?.enableLowPowerMode()
    }

    // ── HTTP Report ───────────────────────────────────────────────────────

    private func sendReport(config: BtLocationConfig, location: CLLocation, connectedIds: [String]) {
        let pajIdMap = config.pajIdMap.merging(dynamicPajIdMap) { _, new in new }
        let connectedPajIds = connectedIds.compactMap { pajIdMap[$0] }

        var body: [String: Any] = config.extraFields
        body["devicesId"] = connectedPajIds
        body["lat"] = location.coordinate.latitude
        body["lng"] = location.coordinate.longitude
        body["accuracy"] = location.horizontalAccuracy
        body["timestamp"] = Int64(Date().timeIntervalSince1970 * 1_000)
        // Add heading if available
        if location.course >= 0 {
            body["direction"] = location.course
        }

        LOG("[BtLocationReporter] Report: devices=\(connectedPajIds), loc=(\(String(format: "%.5f", location.coordinate.latitude)), \(String(format: "%.5f", location.coordinate.longitude)))")
        
        // DEBUG: Skip HTTP, emit success
        // Task { @MainActor [weak self] in
        //     self?.plugin?.emitLocationReport(payload: body, httpStatus: 200, success: true)
        // }
        
        
        guard let url = URL(string: config.endpoint),
              let jsonData = try? JSONSerialization.data(withJSONObject: body) else {
            LOG_ERROR("[BtLocationReporter] Invalid URL or JSON")
            gpsSwitcher?.onLocationReportFailed(connectedDeviceIds: connectedIds)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = jsonData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = config.authToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            let httpStatus = (response as? HTTPURLResponse)?.statusCode ?? 0
            let success = error == nil && (200..<300).contains(httpStatus)
            
            if success {
                LOG("[BtLocationReporter] HTTP OK: \(httpStatus)")
            } else {
                LOG_ERROR("[BtLocationReporter] HTTP failed: \(httpStatus) - \(error?.localizedDescription ?? "")")
                Task { @MainActor in self?.gpsSwitcher?.onLocationReportFailed(connectedDeviceIds: connectedIds) }
            }
            
            Task { @MainActor in self?.plugin?.emitLocationReport(payload: body, httpStatus: httpStatus, success: success) }
        }.resume()
        
    }
    

    // ── Local Notifications ───────────────────────────────────────────────

    private func showBleConnectionNotification(deviceId: String, deviceName: String?) {
        let center = UNUserNotificationCenter.current()
        let texts = config?.texts ?? NotificationTexts.defaults

        // Check if permission is granted (don't request, just check)
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized else {
                LOG("[BtLocationReporter] No notification permission, skipping BLE connection notification")
                return
            }

            let displayName = deviceName?.isEmpty == false ? deviceName! : deviceId

            let content = UNMutableNotificationContent()
            content.title = texts.connectedHeader
            content.body = texts.connected.replacingOccurrences(of: "{device}", with: displayName)
            content.sound = .default

            // Show immediately
            let request = UNNotificationRequest(
                identifier: "ble_connection_\(deviceId)",
                content: content,
                trigger: nil  // nil = deliver immediately
            )

            center.add(request) { error in
                if let error = error {
                    LOG_ERROR("[BtLocationReporter] Failed to show notification: \(error.localizedDescription)")
                } else {
                    LOG("[BtLocationReporter] Showed BLE connection notification for: \(displayName)")
                }
            }
        }
    }

    /// Show notification when a known BLE device is nearby (not connected)
    func showBleNearbyNotification(deviceId: String, deviceName: String?) {
        let center = UNUserNotificationCenter.current()
        // Mensaje fijo en inglés, no depende de texts
        let displayName = deviceName?.isEmpty == false ? deviceName! : deviceId
        let content = UNMutableNotificationContent()
        content.title = "Bluetooth device nearby"
        content.body = "A known Bluetooth device (\(displayName)) is nearby. Open the app to connect and save battery."
        content.sound = .default

        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized else {
                LOG("[BtLocationReporter] No notification permission, skipping BLE nearby notification")
                return
            }
            let request = UNNotificationRequest(
                identifier: "ble_nearby_\(deviceId)",
                content: content,
                trigger: nil
            )
            center.add(request) { error in
                if let error = error {
                    LOG_ERROR("[BtLocationReporter] Failed to show BLE nearby notification: \(error.localizedDescription)")
                } else {
                    LOG("[BtLocationReporter] Showed BLE nearby notification for: \(displayName)")
                }
            }
        }
    }

    // Show notification when monitoring starts
    private func showMonitoringStartedNotification() {
        let center = UNUserNotificationCenter.current()
        let texts = config?.texts ?? NotificationTexts.defaults
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized else {
                LOG("[BtLocationReporter] No notification permission, skipping monitoring started notification")
                return
            }
            let content = UNMutableNotificationContent()
            content.title = texts.trackerHeader
            content.body = texts.tracker
            content.sound = .default
            let request = UNNotificationRequest(
                identifier: "bt_monitoring_started",
                content: content,
                trigger: nil
            )
            center.add(request) { error in
                if let error = error {
                    LOG_ERROR("[BtLocationReporter] Failed to show monitoring started notification: \(error.localizedDescription)")
                } else {
                    LOG("[BtLocationReporter] Showed monitoring started notification")
                }
            }
        }
    }
}
