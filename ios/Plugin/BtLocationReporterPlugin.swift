import Capacitor
import CoreLocation
import Foundation

/// Capacitor plugin entry point for iOS.
/// Delegates all heavy lifting to [BtLocationReporter] (the coordinator singleton).
@objc(BtLocationReporterPlugin)
public class BtLocationReporterPlugin: CAPPlugin, CAPBridgedPlugin {

    public let identifier   = "BtLocationReporterPlugin"
    public let jsName       = "BtLocationReporter"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start",                    returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop",                     returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isRunning",                returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addDevices",               returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeDevices",            returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLogPath",               returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLogs",                  returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestLocationPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "hasLocationPermission",     returnType: CAPPluginReturnPromise),
    ]

    private var coordinator: BtLocationReporter?
    
    public override func load() {
        super.load()
        LOG("Plugin loaded - Capacitor bridge ready")
        LOG("Log file: \(FileLogger.shared.getLogFilePath())")
    }

    // ── Plugin methods ────────────────────────────────────────────────────

    @objc func start(_ call: CAPPluginCall) {
        LOG_INFO("start() called from JS")
        
        guard let rawDevices = call.getArray("devices") as? [[String: Any]], !rawDevices.isEmpty else {
            LOG_ERROR("devices array is required and must not be empty")
            call.reject("devices array is required and must not be empty")
            return
        }
        guard let endpoint = call.getString("reportEndpoint") else {
            LOG_ERROR("reportEndpoint is required")
            call.reject("reportEndpoint is required")
            return
        }
        
        LOG("Config received: endpoint=\(endpoint), devices count=\(rawDevices.count)")

        // Parse [{ bleDeviceId, pajDeviceId, onConnectCommand?, onDisconnectCommand? }] from JS
        let devices: [BtDeviceEntry] = rawDevices.compactMap { dict in
            // DEBUG: Log the raw dictionary to see what keys/values we're receiving
            // LOG_DEBUG("Raw device dict keys: \(dict.keys.sorted())")
            for (key, value) in dict {
                LOG_DEBUG("  \(key) = \(value) (type: \(type(of: value)))")
            }
            
            guard let bleId = dict["bleDeviceId"] as? String,
                  let pajId = dict["pajDeviceId"] else { return nil }
            
            // DEBUG: Log what we're trying to parse for commands
            let rawOnConnect = dict["onConnectCommand"]
            let rawOnDisconnect = dict["onDisconnectCommand"]
            // LOG_DEBUG("  rawOnConnect = \(String(describing: rawOnConnect)) (type: \(type(of: rawOnConnect)))")
            // LOG_DEBUG("  rawOnDisconnect = \(String(describing: rawOnDisconnect)) (type: \(type(of: rawOnDisconnect)))")
            
            let onConnect = self.parseCommand(dict["onConnectCommand"])
            let onDisconnect = self.parseCommand(dict["onDisconnectCommand"])
            
            // LOG("  Device: bleId=\(bleId), pajId=\(pajId), onConnect=\(onConnect?.name ?? "none"), onDisconnect=\(onDisconnect?.name ?? "none")")
            return BtDeviceEntry(
                bleDeviceId: bleId,
                pajDeviceId: String(describing: pajId),
                onConnectCommand: onConnect,
                onDisconnectCommand: onDisconnect
            )
        }

        // Parse debug mode
        let debug = call.getBool("debug") ?? false
        
        // Parse notification texts
        let textsObj = call.getObject("texts")
        let texts = NotificationTexts(
            connectedHeader: textsObj?["connectedHeader"] as? String ?? "Device connected",
            connected: textsObj?["connected"] as? String ?? "{device} connected via Bluetooth, power saving activated",
            trackerHeader: textsObj?["trackerHeader"] as? String ?? "BT Location Reporter",
            tracker: textsObj?["tracker"] as? String ?? "Tracking location in background…"
        )

        let config = BtLocationConfig(
            devices:        devices,
            endpoint:       endpoint,
            authToken:      call.getString("authToken"),
            intervalMs:     call.getDouble("reportIntervalMs") ?? 30_000,
            extraFields:    call.getObject("extraPayloadFields") as? [String: Any] ?? [:],
            debug:          debug,
            texts:          texts
        )

        Task { @MainActor in
            if self.coordinator == nil {
                LOG("Creating new BtLocationReporter coordinator")
                self.coordinator = BtLocationReporter(plugin: self)
            }
            LOG("Calling coordinator.start()")
            self.coordinator?.start(config: config) { [weak self] error in
                if let error = error {
                    LOG_ERROR("start() failed: \(error.localizedDescription)")
                    self?.coordinator = nil
                    call.reject(error.localizedDescription)
                } else {
                    LOG_INFO("start() completed successfully")
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
            return BtDeviceEntry(
                bleDeviceId: bleId,
                pajDeviceId: String(describing: pajId),
                onConnectCommand: self.parseCommand(dict["onConnectCommand"]),
                onDisconnectCommand: self.parseCommand(dict["onDisconnectCommand"])
            )
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
            return BtDeviceEntry(
                bleDeviceId: bleId,
                pajDeviceId: String(describing: pajId),
                onConnectCommand: nil,
                onDisconnectCommand: nil
            )
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
    
    func emitLocationPermissionRequired() {
        LOG("Emitting locationPermissionRequired event")
        notifyListeners("locationPermissionRequired", data: [
            "reason": "First BLE device connected - location permission needed to start tracking"
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
    
    @objc func requestLocationPermission(_ call: CAPPluginCall) {
        LOG("requestLocationPermission() called from JS")
        Task { @MainActor in
            if let coordinator = self.coordinator {
                coordinator.requestLocationPermission { granted in
                    LOG("Location permission granted: \(granted)")
                    call.resolve(["granted": granted])
                }
            } else {
                // No coordinator yet - just check current status
                let status = CLLocationManager.authorizationStatus()
                let granted = status == .authorizedAlways || status == .authorizedWhenInUse
                call.resolve(["granted": granted])
            }
        }
    }
    
    @objc func hasLocationPermission(_ call: CAPPluginCall) {
        let status = CLLocationManager.authorizationStatus()
        let granted = status == .authorizedAlways || status == .authorizedWhenInUse
        call.resolve(["granted": granted])
    }
    
    // ── Helper methods ────────────────────────────────────────────────────
    
    private func parseCommand(_ value: Any?) -> BleCommand? {
        // Capacitor sends nested objects as JSObject ([String: JSValue]) or as NSDictionary
        // We need to handle both cases
        
        guard let value = value else {
            // LOG_DEBUG("parseCommand: value is nil")
            return nil
        }
        
        // LOG_DEBUG("parseCommand: received type = \(type(of: value))")
        
        // Try to convert to [String: Any] - works for NSDictionary
        var dict: [String: Any]?
        
        if let d = value as? [String: Any] {
            dict = d
        } else if let nsDict = value as? NSDictionary {
            dict = nsDict as? [String: Any]
        } else {
            // For JSObject, try to extract using Mirror or direct casting
            // LOG_DEBUG("parseCommand: attempting JSObject extraction")
            let mirror = Mirror(reflecting: value)
            var extracted: [String: Any] = [:]
            for child in mirror.children {
                if let key = child.label {
                    extracted[key] = child.value
                }
            }
            if !extracted.isEmpty {
                dict = extracted
            }
        }
        
        guard let dict = dict else {
            // LOG_DEBUG("parseCommand: could not convert to dictionary")
            return nil
        }
        
        // LOG_DEBUG("parseCommand: dict keys = \(dict.keys.sorted())")
        
        // Extract values - support both camelCase and snake_case keys (backend sends snake_case)
        guard let name = extractString(dict["name"]) else {
            // LOG_DEBUG("parseCommand: 'name' missing or not String")
            return nil
        }
        // Try camelCase first, then snake_case
        guard let serviceUuid = extractString(dict["serviceUuid"]) ?? extractString(dict["service_uuid"]) else {
            // LOG_DEBUG("parseCommand: 'serviceUuid/service_uuid' missing or not String")
            return nil
        }
        guard let characteristicUuid = extractString(dict["characteristicUuid"]) ?? extractString(dict["characteristic_uuid"]) else {
            // LOG_DEBUG("parseCommand: 'characteristicUuid/characteristic_uuid' missing or not String")
            return nil
        }
        guard let value = extractString(dict["value"]) else {
            // LOG_DEBUG("parseCommand: 'value' missing or not String")
            return nil
        }
        
        LOG_INFO("parseCommand: SUCCESS - name=\(name), service=\(serviceUuid), char=\(characteristicUuid)")
        return BleCommand(
            name: name,
            serviceUuid: serviceUuid,
            characteristicUuid: characteristicUuid,
            value: value
        )
    }
    
    /// Extracts a String from various types (String, NSString, or wrapped values)
    private func extractString(_ value: Any?) -> String? {
        guard let value = value else { return nil }
        
        if let str = value as? String {
            return str
        }
        if let nsStr = value as? NSString {
            return nsStr as String
        }
        // Try description as last resort
        let desc = String(describing: value)
        if desc != "nil" && !desc.isEmpty && desc != "Optional(nil)" {
            LOG_DEBUG("extractString: using description fallback: \(desc)")
            return desc
        }
        return nil
    }
}
