import Foundation
import CoreLocation
import UIKit

/**
 * Background-capable location manager.
 * Triggers callback on location updates instead of polling (works in background).
 *
 * Info.plist requirements:
 *   - NSLocationAlwaysAndWhenInUseUsageDescription
 *   - NSLocationWhenInUseUsageDescription
 *   - UIBackgroundModes → location
 */
class LocationReporter: NSObject, CLLocationManagerDelegate {

    private let manager = CLLocationManager()
    private(set) var lastLocation: CLLocation?
    private(set) var isTracking = false
    
    /// Callback on new location - this triggers reports (works in background!)
    var onLocationUpdate: ((CLLocation) -> Void)?

    private var permissionCompletion: ((Bool) -> Void)?
    private var lastReportTime: Date = .distantPast
    private var minReportInterval: TimeInterval = 30.0
    private(set) var hasPermission = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10  // Report when moved 10m
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .otherNavigation
        
        // App lifecycle observers
        NotificationCenter.default.addObserver(self, selector: #selector(appDidEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(appWillEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
        
        LOG("[LocationReporter] Initialized")
    }
    
    deinit {
        NotificationCenter.default.removeObserver(self)
    }
    
    func setReportInterval(ms: Double) {
        minReportInterval = ms / 1000.0
    }
    
    @objc private func appDidEnterBackground() {
        LOG("[LocationReporter] === BACKGROUND === location updates continue")
    }
    
    @objc private func appWillEnterForeground() {
        LOG("[LocationReporter] === FOREGROUND ===")
    }
    
    private func startLocationUpdates() {
        guard !isTracking else { return }
        isTracking = true
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = false
        manager.startUpdatingLocation()
        LOG("[LocationReporter] Started (background enabled)")
    }
    
    /// Pause location updates (saves battery when no BLE connected)
    func pause() {
        guard isTracking else { return }
        isTracking = false
        manager.stopUpdatingLocation()
        LOG("[LocationReporter] Paused (no BLE devices)")
    }
    
    /// Resume location updates (when BLE device connects)
    func resume() {
        guard hasPermission, !isTracking else { return }
        startLocationUpdates()
        LOG("[LocationReporter] Resumed (BLE device connected)")
    }

    func requestAlwaysPermission(completion: @escaping (Bool) -> Void) {
        let status = manager.authorizationStatus
        switch status {
        case .authorizedAlways:
            hasPermission = true
            // Don't start yet - wait for first BLE connection
            completion(true)
        case .authorizedWhenInUse, .notDetermined:
            permissionCompletion = completion
            manager.requestAlwaysAuthorization()
        default:
            LOG_ERROR("[LocationReporter] Permission denied")
            completion(false)
        }
    }

    func stop() {
        isTracking = false
        hasPermission = false
        manager.stopUpdatingLocation()
        LOG("[LocationReporter] Stopped")
    }

    /// Activa el modo bajo consumo: accuracy baja, distancia alta, pausa automática y solo cambios significativos.
    /// Llama a este método cuando no haya dispositivos BLE conectados o el Bluetooth esté apagado.
    func enableLowPowerMode() {
        // Configuración recomendada para bajo consumo
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 500
        manager.pausesLocationUpdatesAutomatically = true
        manager.showsBackgroundLocationIndicator = false

        // Detener cualquier tracking activo
        if isTracking {
            manager.stopUpdatingLocation()
            isTracking = false
        }

        // Iniciar solo cambios significativos
        manager.startMonitoringSignificantLocationChanges()
        LOG("[LocationReporter] Low Power Mode ACTIVATED: accuracy=HundredMeters, distanceFilter=500m, pauses=YES, indicator=NO, usando startMonitoringSignificantLocationChanges()")
    }

    // ── CLLocationManagerDelegate ─────────────────────────────────────────

    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        switch status {
        case .authorizedAlways:
            LOG("[LocationReporter] Auth: Always granted")
            hasPermission = true
            // Don't start yet - wait for first BLE connection
            permissionCompletion?(true)
            permissionCompletion = nil
        case .denied, .restricted:
            LOG_ERROR("[LocationReporter] Auth: Denied")
            permissionCompletion?(false)
            permissionCompletion = nil
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        lastLocation = location
        
        // Throttle based on interval
        let elapsed = Date().timeIntervalSince(lastReportTime)
        if elapsed >= minReportInterval {
            lastReportTime = Date()
            LOG("[LocationReporter] Location: (\(String(format: "%.5f", location.coordinate.latitude)), \(String(format: "%.5f", location.coordinate.longitude))) acc=\(Int(location.horizontalAccuracy))m")
            onLocationUpdate?(location)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let err = error as? CLError, err.code == .locationUnknown { return }
        LOG_ERROR("[LocationReporter] Error: \(error.localizedDescription)")
    }
}
