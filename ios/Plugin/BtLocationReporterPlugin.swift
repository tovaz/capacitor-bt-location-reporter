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
    ]

    private var coordinator: BtLocationReporter?

    // ── Plugin methods ────────────────────────────────────────────────────

    @objc func start(_ call: CAPPluginCall) {
        guard let rawDevices = call.getArray("devices") as? [[String: Any]], !rawDevices.isEmpty else {
            call.reject("devices array is required and must not be empty")
            return
        }
        guard let endpoint = call.getString("reportEndpoint") else {
            call.reject("reportEndpoint is required")
            return
        }

        // Parse [{ bleDeviceId, pajDeviceId }] from JS
        let devices: [BtDeviceEntry] = rawDevices.compactMap { dict in
            guard let bleId = dict["bleDeviceId"] as? String,
                  let pajId = dict["pajDeviceId"] else { return nil }
            return BtDeviceEntry(bleDeviceId: bleId, pajDeviceId: String(describing: pajId))
        }

        let config = BtLocationConfig(
            devices:        devices,
            endpoint:       endpoint,
            authToken:      call.getString("authToken"),
            intervalMs:     call.getDouble("reportIntervalMs") ?? 30_000,
            extraFields:    call.getObject("extraPayloadFields") as? [String: Any] ?? [:]
        )

        if coordinator == nil {
            coordinator = BtLocationReporter(plugin: self)
        }
        coordinator?.start(config: config) { [weak self] error in
            if let error = error {
                self?.coordinator = nil
                call.reject(error.localizedDescription)
            } else {
                call.resolve()
            }
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        coordinator?.stop()
        coordinator = nil
        call.resolve()
    }

    @objc func isRunning(_ call: CAPPluginCall) {
        call.resolve(["running": coordinator?.isRunning ?? false])
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
        coordinator?.addDevices(entries)
        call.resolve()
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
        coordinator?.removeDevices(entries)
        call.resolve()
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
        notifyListeners("bleConnection", data: [
            "deviceId":  deviceId,
            "connected": connected
        ])
    }
}
