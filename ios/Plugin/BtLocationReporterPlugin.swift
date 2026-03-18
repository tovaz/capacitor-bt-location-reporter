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
        guard let deviceIds = call.getArray("deviceIds") as? [String], !deviceIds.isEmpty else {
            call.reject("deviceIds array is required and must not be empty")
            return
        }
        guard let endpoint = call.getString("reportEndpoint") else {
            call.reject("reportEndpoint is required")
            return
        }

        let config = BtLocationConfig(
            deviceIds:      deviceIds,
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
        guard let ids = call.getArray("deviceIds") as? [String] else {
            call.reject("deviceIds is required"); return
        }
        coordinator?.addDevices(ids)
        call.resolve()
    }

    @objc func removeDevices(_ call: CAPPluginCall) {
        guard let ids = call.getArray("deviceIds") as? [String] else {
            call.reject("deviceIds is required"); return
        }
        coordinator?.removeDevices(ids)
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
