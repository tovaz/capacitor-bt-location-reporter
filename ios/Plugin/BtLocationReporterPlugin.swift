import Capacitor
import Foundation

/// Capacitor plugin entry point for iOS.
/// Delegates all heavy lifting to [BtLocationReporter] (the coordinator singleton).
@objc(BtLocationReporterPlugin)
public class BtLocationReporterPlugin: CAPPlugin, CAPBridgedPlugin {

    public let identifier   = "BtLocationReporterPlugin"
    public let jsName       = "BtLocationReporter"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start",         returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop",          returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isRunning",     returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addDevices",    returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeDevices", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLogPath",    returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLogs",       returnType: CAPPluginReturnPromise),
    ]

    private var coordinator: BtLocationReporter?
    
    public override func load() {
        super.load()
        LOG("Plugin loaded - Capacitor bridge ready")
        LOG("Log file: \(FileLogger.shared.getLogFilePath())")
    }

    // ── Plugin methods ────────────────────────────────────────────────────

    @objc func start(_ call: CAPPluginCall) {
        LOG("start() called from JS")
        
        guard let rawDevices = call.getArray("devices") as? [[String: Any]], !rawDevices.isEmpty else {
            LOG("ERROR: devices array is required and must not be empty")
            call.reject("devices array is required and must not be empty")
            return
        }
        guard let endpoint = call.getString("reportEndpoint") else {
            LOG("ERROR: reportEndpoint is required")
            call.reject("reportEndpoint is required")
            return
        }
        
        LOG("Config received: endpoint=\(endpoint), devices count=\(rawDevices.count)")

        // Parse [{ bleDeviceId, pajDeviceId }] from JS
        let devices: [BtDeviceEntry] = rawDevices.compactMap { dict in
            guard let bleId = dict["bleDeviceId"] as? String,
                  let pajId = dict["pajDeviceId"] else { return nil }
            LOG("  Device: bleId=\(bleId), pajId=\(pajId)")
            return BtDeviceEntry(bleDeviceId: bleId, pajDeviceId: String(describing: pajId))
        }

        let config = BtLocationConfig(
            devices:        devices,
            endpoint:       endpoint,
            authToken:      call.getString("authToken"),
            intervalMs:     call.getDouble("reportIntervalMs") ?? 30_000,
            extraFields:    call.getObject("extraPayloadFields") as? [String: Any] ?? [:]
        )

        Task { @MainActor in
            if self.coordinator == nil {
                LOG("Creating new BtLocationReporter coordinator")
                self.coordinator = BtLocationReporter(plugin: self)
            }
            LOG("Calling coordinator.start()")
            self.coordinator?.start(config: config) { [weak self] error in
                if let error = error {
                    LOG("ERROR: start() failed: \(error.localizedDescription)")
                    self?.coordinator = nil
                    call.reject(error.localizedDescription)
                } else {
                    LOG("SUCCESS: start() completed")
                    call.resolve()
                }
            }
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        LOG("stop() called from JS")
        Task { @MainActor in
            self.coordinator?.stop()
            self.coordinator = nil
            LOG("Plugin stopped and coordinator released")
            call.resolve()
        }
    }

    @objc func isRunning(_ call: CAPPluginCall) {
        Task { @MainActor in
            let running = self.coordinator?.isRunning ?? false
            LOG("isRunning() = \(running)")
            call.resolve(["running": running])
        }
    }

    @objc func addDevices(_ call: CAPPluginCall) {
        guard let rawDevices = call.getArray("devices") as? [[String: Any]], !rawDevices.isEmpty else {
            call.reject("devices array is required"); return
        }
        let entries = rawDevices.compactMap { dict -> BtDeviceEntry? in
            guard let bleId = dict["bleDeviceId"] as? String,
                  let pajId = dict["pajDeviceId"] else { return nil }
            return BtDeviceEntry(bleDeviceId: bleId, pajDeviceId: String(describing: pajId))
        }
        Task { @MainActor in
            self.coordinator?.addDevices(entries)
            call.resolve()
        }
    }

    @objc func removeDevices(_ call: CAPPluginCall) {
        guard let rawDevices = call.getArray("devices") as? [[String: Any]], !rawDevices.isEmpty else {
            call.reject("devices array is required"); return
        }
        let entries = rawDevices.compactMap { dict -> BtDeviceEntry? in
            guard let bleId = dict["bleDeviceId"] as? String,
                  let pajId = dict["pajDeviceId"] else { return nil }
            return BtDeviceEntry(bleDeviceId: bleId, pajDeviceId: String(describing: pajId))
        }
        Task { @MainActor in
            self.coordinator?.removeDevices(entries)
            call.resolve()
        }
    }

    // ── Event helpers (called by BtLocationReporter) ──────────────────────

    func emitLocationReport(payload: [String: Any], httpStatus: Int, success: Bool) {
        notifyListeners("locationReport", data: [
            "payload":    payload,
            "httpStatus": httpStatus,
            "success":    success
        ])
    }

    func emitBleConnection(deviceId: String, connected: Bool) {
        LOG("Emitting bleConnection event: deviceId=\(deviceId), connected=\(connected)")
        notifyListeners("bleConnection", data: [
            "deviceId":  deviceId,
            "connected": connected
        ])
    }
    
    // ── Debug methods ─────────────────────────────────────────────────────
    
    @objc func getLogPath(_ call: CAPPluginCall) {
        let path = FileLogger.shared.getLogFilePath()
        call.resolve(["path": path])
    }
    
    @objc func getLogs(_ call: CAPPluginCall) {
        let logs = FileLogger.shared.getLogContents()
        call.resolve(["logs": logs])
    }
}
