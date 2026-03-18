import Foundation
import CoreBluetooth
import CoreLocation

/// One entry in the devices list: the BLE UUID used for connection and the PAJ ID for reports.
struct BtDeviceEntry {
    let bleDeviceId: String
    let pajDeviceId: String
}

/// Configuration passed from the JS layer.
struct BtLocationConfig {
    let devices:    [BtDeviceEntry]
    let endpoint:   String
    let authToken:  String?
    let intervalMs: Double
    let extraFields: [String: Any]

    var bleDeviceIds: [String] { devices.map { $0.bleDeviceId } }
    var pajIdMap: [String: String] { Dictionary(uniqueKeysWithValues: devices.map { ($0.bleDeviceId, $0.pajDeviceId) }) }
}

/**
 * Main coordinator for the iOS background session.
 *
 * Responsibilities:
 *  - Owns [BleManager] (CBCentralManager) and [LocationReporter] (CLLocationManager).
 *  - Fires the periodic HTTP POST when ≥1 BLE device is connected.
 *  - Forwards events back to [BtLocationReporterPlugin] so they reach the JS layer.
 *
 * Background modes required in the host app's Info.plist:
 *   UIBackgroundModes → bluetooth-central, location
 */
@MainActor
class BtLocationReporter: NSObject {

    // ── State ─────────────────────────────────────────────────────────────

    private(set) var isRunning = false

    private weak var plugin:   BtLocationReporterPlugin?
    private var  config:       BtLocationConfig?
    private var  bleManager:   BleManager?
    private var  locationMgr:  LocationReporter?
    private var  reportTimer:  Timer?

    // Mutable map for devices added at runtime (after start)
    private var dynamicPajIdMap: [String: String] = [:]

    // ── Init ──────────────────────────────────────────────────────────────

    init(plugin: BtLocationReporterPlugin) {
        self.plugin = plugin
    }

    // ── Public API ────────────────────────────────────────────────────────

    func start(config: BtLocationConfig, completion: @escaping (Error?) -> Void) {
        guard !isRunning else { completion(nil); return }
        self.config  = config
        self.isRunning = true

        // 1. Start location tracking first (prompts user if needed)
        locationMgr = LocationReporter()
        locationMgr?.requestAlwaysPermission { [weak self] granted in
            guard let self else { return }
            if !granted {
                self.isRunning = false
                completion(NSError(domain: "BtLocationReporter",
                                   code: 1,
                                   userInfo: [NSLocalizedDescriptionKey: "Location permission denied"]))
                return
            }

            // 2. Start BLE manager (uses BLE UUIDs for connection management)
            self.bleManager = BleManager(
                deviceIds: config.bleDeviceIds,
                onConnected:    { [weak self] id in self?.handleBleConnected(id) },
                onDisconnected: { [weak self] id in self?.handleBleDisconnected(id) }
            )
            self.bleManager?.start()

            // 3. Start report timer
            self.scheduleReportTimer(intervalMs: config.intervalMs)
            completion(nil)
        }
    }

    func stop() {
        isRunning = false
        reportTimer?.invalidate()
        reportTimer = nil
        bleManager?.stop()
        bleManager  = nil
        locationMgr?.stop()
        locationMgr = nil
    }

    func addDevices(_ entries: [BtDeviceEntry]) {
        let bleIds = entries.map { $0.bleDeviceId }
        bleManager?.addDevices(bleIds)
        entries.forEach { dynamicPajIdMap[$0.bleDeviceId] = $0.pajDeviceId }
    }

    func removeDevices(_ entries: [BtDeviceEntry]) {
        let bleIds = entries.map { $0.bleDeviceId }
        bleManager?.removeDevices(bleIds)
        bleIds.forEach { dynamicPajIdMap.removeValue(forKey: $0) }
    }

    // ── BLE event handlers ────────────────────────────────────────────────

    private func handleBleConnected(_ deviceId: String) {
        plugin?.emitBleConnection(deviceId: deviceId, connected: true)
    }

    private func handleBleDisconnected(_ deviceId: String) {
        plugin?.emitBleConnection(deviceId: deviceId, connected: false)
    }

    // ── Report timer ──────────────────────────────────────────────────────

    private func scheduleReportTimer(intervalMs: Double) {
        let interval = intervalMs / 1_000.0
        reportTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.tick()
            }
        }
        RunLoop.main.add(reportTimer!, forMode: .common)
    }

    private func tick() {
        guard isRunning,
              let config   = config,
              let location = locationMgr?.lastLocation,
              let connectedIds = bleManager?.connectedIds, !connectedIds.isEmpty
        else { return }

        sendReport(config: config, location: location, connectedIds: connectedIds)
    }

    // ── HTTP POST ─────────────────────────────────────────────────────────

    private func sendReport(config: BtLocationConfig,
                             location: CLLocation,
                             connectedIds: [String]) {
        guard let url = URL(string: config.endpoint) else { return }

        // Map connected BLE UUIDs → PAJ device IDs for the report payload
        // Merge config map (set at start) + dynamic map (devices added at runtime)
        let pajIdMap = config.pajIdMap.merging(dynamicPajIdMap) { _, new in new }
        let connectedPajIds = connectedIds.compactMap { pajIdMap[$0] }

        var body: [String: Any] = config.extraFields
        body["devicesId"] = connectedPajIds
        body["lat"]        = location.coordinate.latitude
        body["lng"]        = location.coordinate.longitude
        body["accuracy"]   = location.horizontalAccuracy
        body["timestamp"]  = Int64(Date().timeIntervalSince1970 * 1_000)

        guard let jsonData = try? JSONSerialization.data(withJSONObject: body) else { return }

        var request        = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody   = jsonData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token = config.authToken {
            request.setValue(token, forHTTPHeaderField: "Authorization")
        }

        // Use background URLSession so iOS can complete the request even if the app suspends
        let session = URLSession(configuration: .background(withIdentifier: "com.paj.btlocationreporter.upload"))
        let task = session.dataTask(with: request) { [weak self] data, response, error in
            let httpStatus = (response as? HTTPURLResponse)?.statusCode ?? 0
            let success    = error == nil && (200..<300).contains(httpStatus)

            Task { @MainActor [weak self] in
                self?.plugin?.emitLocationReport(payload: body, httpStatus: httpStatus, success: success)
            }
        }
        task.resume()
    }
}
