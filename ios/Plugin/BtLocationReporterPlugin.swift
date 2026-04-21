import Capacitor
import CoreBluetooth
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
        CAPPluginMethod(name: "writeWithoutResponse",      returnType: CAPPluginReturnPromise),
    ]

    private var coordinator: BtLocationReporter?
    private let configStoreKey = "BtLocationReporterPlugin.config"

    // MARK: - Config Store Helpers

    private func saveConfig(_ config: BtLocationConfig) {
        do {
            let codable = ConfigCodable(config: config)
            let data = try JSONEncoder().encode(codable)
            UserDefaults.standard.set(data, forKey: configStoreKey)
            LOG("[BtLocationReporterPlugin] Config saved to UserDefaults")
            // Log JSON decodificado
            if let json = try? JSONSerialization.jsonObject(with: data, options: []),
               let pretty = try? JSONSerialization.data(withJSONObject: json, options: .prettyPrinted),
               let str = String(data: pretty, encoding: .utf8) {
                LOG("[BtLocationReporterPlugin] Config JSON saved:\n\(str)")
            }
        } catch {
            LOG_ERROR("[BtLocationReporterPlugin] Failed to save config: \(error)")
        }
    }

    private func loadConfig() -> BtLocationConfig? {
        guard let data = UserDefaults.standard.data(forKey: configStoreKey) else { return nil }
        do {
            let codable = try JSONDecoder().decode(ConfigCodable.self, from: data)
            return codable.toConfig()
        } catch {
            LOG_ERROR("[BtLocationReporterPlugin] Failed to load config: \(error)")
            return nil
        }
    }

    // Codable wrapper for BtLocationConfig (solo campos serializables)
    private struct ConfigCodable: Codable {
        let devices: [DeviceCodable]
        let endpoint: String
        let authToken: String?
        let intervalMs: Double
        let extraFields: [String: String] // Solo String para simplificar
        let debug: Bool
        let texts: NotificationTextsCodable

        init(config: BtLocationConfig) {
            self.devices = config.devices.map { DeviceCodable(entry: $0) }
            self.endpoint = config.endpoint
            self.authToken = config.authToken
            self.intervalMs = config.intervalMs
            self.extraFields = config.extraFields.compactMapValues { String(describing: $0) }
            self.debug = config.debug
            self.texts = NotificationTextsCodable(texts: config.texts)
        }
        func toConfig() -> BtLocationConfig {
            BtLocationConfig(
                devices: devices.map { $0.toEntry() },
                endpoint: endpoint,
                authToken: authToken,
                intervalMs: intervalMs,
                extraFields: extraFields,
                debug: debug,
                texts: texts.toTexts()
            )
        }
    }
    private struct DeviceCodable: Codable {
        let bleDeviceId: String
        let pajDeviceId: String
        init(entry: BtDeviceEntry) {
            self.bleDeviceId = entry.bleDeviceId
            self.pajDeviceId = entry.pajDeviceId
        }
        func toEntry() -> BtDeviceEntry {
            BtDeviceEntry(bleDeviceId: bleDeviceId, pajDeviceId: pajDeviceId, onConnectCommand: nil, onDisconnectCommand: nil)
        }
    }
    private struct NotificationTextsCodable: Codable {
        let connectedHeader: String
        let connected: String
        let trackerHeader: String
        let tracker: String
        init(texts: NotificationTexts) {
            self.connectedHeader = texts.connectedHeader
            self.connected = texts.connected
            self.trackerHeader = texts.trackerHeader
            self.tracker = texts.tracker
        }
        func toTexts() -> NotificationTexts {
            NotificationTexts(
                connectedHeader: connectedHeader,
                connected: connected,
                trackerHeader: trackerHeader,
                tracker: tracker
            )
        }
    }
    
    public override func load() {
        super.load()
        LOG("Plugin loaded - Capacitor bridge ready")
        LOG("Log file: \(FileLogger.shared.getLogFilePath())")
        Task { @MainActor in
            self.restoreIfPending()
        }
    }

    @MainActor
    private func restoreIfPending() {
        // Solo restaurar si el flag está presente Y si iOS nos relanzó en background (no si el usuario abrió la app normalmente).
        // Esto evita que se cree CBCentralManager (y se pida permiso BT) en aperturas normales del usuario.
        let pendingRestore = UserDefaults.standard.bool(forKey: "BtLocationReporterPlugin.pendingRestore")
        guard pendingRestore else { return }

        let isBackgroundLaunch = UIApplication.shared.applicationState != .active
        guard isBackgroundLaunch else {
            LOG("[BtLocationReporterPlugin] App abierta en primer plano — omitiendo auto-restore (se requiere start() explícito desde JS)")
            return
        }

        guard let config = loadConfig() else { return }
        LOG("[BtLocationReporterPlugin] Relanzamiento BLE en background detectado — restaurando sesión")
        UserDefaults.standard.removeObject(forKey: "BtLocationReporterPlugin.pendingRestore")
        restoreCoordinatorIfNeeded(config: config)
    }

    @MainActor
    private func restoreCoordinatorIfNeeded(config: BtLocationConfig) {
        if self.coordinator == nil {
            self.coordinator = BtLocationReporter(plugin: self)
        }
        startCoordinator(config: config)
    }

    @MainActor
    private func startCoordinator(config: BtLocationConfig) {
        self.coordinator?.start(config: config) { error in
            if let error = error {
                LOG_ERROR("[BtLocationReporterPlugin] Auto-restore failed: \(error.localizedDescription)")
            } else {
                LOG("[BtLocationReporterPlugin] Auto-restore completed successfully")
            }
        }
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

        // Guardar config en el store local
        self.saveConfig(config)

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
            // Limpiar el flag de restauración para que la detención explícita impida el auto-arranque en la próxima apertura
            UserDefaults.standard.removeObject(forKey: "BtLocationReporterPlugin.pendingRestore")
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
            // Actualizar config en el store local
            if let current = self.coordinator?.config {
                let newConfig = BtLocationConfig(
                    devices: current.devices + entries,
                    endpoint: current.endpoint,
                    authToken: current.authToken,
                    intervalMs: current.intervalMs,
                    extraFields: current.extraFields,
                    debug: current.debug,
                    texts: current.texts
                )
                self.saveConfig(newConfig)
            }
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
            // Actualizar config en el store local
            if let current = self.coordinator?.config {
                let removeIds = Set(entries.map { $0.bleDeviceId })
                let newDevices = current.devices.filter { !removeIds.contains($0.bleDeviceId) }
                let newConfig = BtLocationConfig(
                    devices: newDevices,
                    endpoint: current.endpoint,
                    authToken: current.authToken,
                    intervalMs: current.intervalMs,
                    extraFields: current.extraFields,
                    debug: current.debug,
                    texts: current.texts
                )
                self.saveConfig(newConfig)
            }
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

    @objc func writeWithoutResponse(_ call: CAPPluginCall) {
        guard let deviceId = call.getString("deviceId") else {
            call.reject("deviceId is required"); return
        }
        guard let service = call.getString("service") else {
            call.reject("service is required"); return
        }
        guard let characteristic = call.getString("characteristic") else {
            call.reject("characteristic is required"); return
        }
        guard let rawValue = call.getArray("value") as? [Int] else {
            call.reject("value (byte array) is required"); return
        }

        let data = Data(rawValue.map { UInt8(clamping: $0) })
        let serviceUUID = CBUUID(string: service)
        let charUUID    = CBUUID(string: characteristic)

        LOG("writeWithoutResponse: device=\(deviceId) service=\(service) char=\(characteristic) bytes=\(data.count)")

        Task { @MainActor in
            guard let coordinator = self.coordinator else {
                call.reject("Service not started — call start() first")
                return
            }
            coordinator.writeWithoutResponse(
                deviceId: deviceId,
                serviceUUID: serviceUUID,
                characteristicUUID: charUUID,
                data: data
            ) { error in
                if let error = error {
                    LOG_ERROR("writeWithoutResponse failed: \(error.localizedDescription)")
                    call.reject(error.localizedDescription)
                } else {
                    LOG("writeWithoutResponse succeeded")
                    call.resolve()
                }
            }
        }
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
