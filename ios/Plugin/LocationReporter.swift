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
    private var minReportInterval: TimeInterval = 30.0
    private var reportTimer: Timer?
    private(set) var hasPermission = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone  // Always update lastLocation so timer always has fresh data
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
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.setReportInterval(ms: ms) }
            return
        }
        minReportInterval = ms / 1000.0
        // Reschedule live if already tracking (live tracking calls this to shorten the interval).
        if isTracking { scheduleTimer() }
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
        scheduleTimer()
        LOG("[LocationReporter] Started (background enabled, interval=\(minReportInterval)s)")
    }

    // Called only from main thread (resume, setReportInterval guarantee this).
    private func scheduleTimer() {
        assert(Thread.isMainThread, "scheduleTimer must run on main thread")
        reportTimer?.invalidate()
        let timer = Timer(timeInterval: minReportInterval, repeats: true) { [weak self] _ in
            self?.fireTimedReport()
        }
        RunLoop.main.add(timer, forMode: .common)
        reportTimer = timer
        LOG("[LocationReporter] Report timer scheduled every \(minReportInterval)s")
    }

    private func fireTimedReport() {
        guard let location = lastLocation else {
            LOG("[LocationReporter] Timer fired but no location yet — waiting")
            return
        }
        LOG("[LocationReporter] Timer fired — reporting (\(String(format: "%.5f", location.coordinate.latitude)), \(String(format: "%.5f", location.coordinate.longitude))) acc=\(Int(location.horizontalAccuracy))m")
        onLocationUpdate?(location)
    }
    
    /// Pause location updates (saves battery when no BLE connected)
    func pause() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.pause() }
            return
        }
        guard isTracking else { return }
        isTracking = false
        reportTimer?.invalidate()
        reportTimer = nil
        manager.stopUpdatingLocation()
        LOG("[LocationReporter] Paused (no BLE devices)")
    }
    
    /// Resume location updates (when BLE device connects)
    func resume() {
        // CLLocationManager MUST be driven from the main thread.
        // BLE connect callbacks arrive on CBCentralManager's background queue.
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.resume() }
            return
        }
        guard hasPermission else { return }
        if isTracking {
            // Ya está activo — solo reprograma el timer con el intervalo más reciente
            // (puede haber cambiado por live tracking). NO llames startLocationUpdates()
            // porque su guard !isTracking lo haría salir sin crear un nuevo timer.
            scheduleTimer()
        } else {
            startLocationUpdates()
        }
        // Fire an immediate report on first BLE connect so JS gets data right away
        // without waiting for the first timer tick (up to 30 s).
        if let location = lastLocation {
            LOG("[LocationReporter] Immediate report on resume (BLE connect, last fix available)")
            onLocationUpdate?(location)
        }
        LOG("[LocationReporter] Resumed (BLE device connected)")
    }

    func requestAlwaysPermission(completion: @escaping (Bool) -> Void) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.requestAlwaysPermission(completion: completion) }
            return
        }
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
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.stop() }
            return
        }
        isTracking = false
        hasPermission = false
        reportTimer?.invalidate()
        reportTimer = nil
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
        // Keep lastLocation fresh — the timer picks it up on each tick.
        lastLocation = location
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let err = error as? CLError, err.code == .locationUnknown { return }
        LOG_ERROR("[LocationReporter] Error: \(error.localizedDescription)")
    }
}
