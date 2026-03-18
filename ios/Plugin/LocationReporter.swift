import Foundation
import CoreLocation

/**
 * Wraps CLLocationManager for continuous background location updates.
 *
 * The host app's Info.plist MUST include:
 *   - NSLocationAlwaysAndWhenInUseUsageDescription
 *   - NSLocationWhenInUseUsageDescription
 *   - UIBackgroundModes → location
 *
 * [lastLocation] is updated on every CLLocationManager callback and
 * read by [BtLocationReporter] on each report tick.
 */
class LocationReporter: NSObject, CLLocationManagerDelegate {

    private let manager = CLLocationManager()
    private(set) var lastLocation: CLLocation?

    private var permissionCompletion: ((Bool) -> Void)?
    private var locationUpdateCount = 0

    override init() {
        super.init()
        LOG("LocationReporter init")
        manager.delegate              = self
        manager.desiredAccuracy       = kCLLocationAccuracyBest
        manager.distanceFilter        = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        LOG("  CLLocationManager configured")
        // Background settings will be configured when actually starting location updates
    }
    
    /// Configures background mode and starts location updates
    private func startLocationUpdates() {
        LOG("startLocationUpdates() - enabling background mode")
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
        LOG("  Location updates STARTED")
    }

    // ── Public API ────────────────────────────────────────────────────────

    /// Requests "Always" location authorization and calls completion when resolved.
    func requestAlwaysPermission(completion: @escaping (Bool) -> Void) {
        let status = manager.authorizationStatus
        LOG("requestAlwaysPermission() - current status=\(status.rawValue)")
        
        switch status {
        case .authorizedAlways:
            LOG("  Already authorized (Always)")
            startLocationUpdates()
            completion(true)
        case .authorizedWhenInUse:
            LOG("  WhenInUse granted, requesting upgrade to Always")
            // Upgrade to Always
            permissionCompletion = completion
            manager.requestAlwaysAuthorization()
        case .notDetermined:
            LOG("  Not determined, requesting Always authorization")
            permissionCompletion = completion
            manager.requestAlwaysAuthorization()
        default:
            LOG("  ERROR: Permission denied or restricted")
            // denied / restricted
            completion(false)
        }
    }

    func stop() {
        LOG("LocationReporter.stop() - stopping location updates")
        manager.stopUpdatingLocation()
    }

    // ── CLLocationManagerDelegate ─────────────────────────────────────────

    func locationManager(_ manager: CLLocationManager,
                         didChangeAuthorization status: CLAuthorizationStatus) {
        let statusNames = ["notDetermined", "restricted", "denied", "authorizedAlways", "authorizedWhenInUse"]
        let statusName = status.rawValue < statusNames.count ? statusNames[Int(status.rawValue)] : "unknown"
        LOG("didChangeAuthorization: \(statusName) (rawValue=\(status.rawValue))")
        
        switch status {
        case .authorizedAlways:
            LOG("  Authorization GRANTED (Always)")
            startLocationUpdates()
            permissionCompletion?(true)
            permissionCompletion = nil
        case .denied, .restricted:
            LOG("  Authorization DENIED/RESTRICTED")
            permissionCompletion?(false)
            permissionCompletion = nil
        default:
            LOG("  Authorization status changed but not actionable yet")
            break
        }
    }

    func locationManager(_ manager: CLLocationManager,
                         didUpdateLocations locations: [CLLocation]) {
        lastLocation = locations.last
        locationUpdateCount += 1
        
        // Log every 10th update to avoid spam
        if locationUpdateCount % 10 == 1 {
            if let loc = lastLocation {
                LOG("didUpdateLocations (#\(locationUpdateCount)): lat=\(loc.coordinate.latitude), lng=\(loc.coordinate.longitude), accuracy=\(loc.horizontalAccuracy)m")
            }
        }
    }

    func locationManager(_ manager: CLLocationManager,
                         didFailWithError error: Error) {
        guard let err = error as? CLError else {
            LOG("didFailWithError: unknown error - \(error.localizedDescription)")
            return
        }
        
        switch err.code {
        case .denied:
            LOG("didFailWithError: DENIED - permission revoked at runtime")
            // Permission revoked at runtime
            manager.stopUpdatingLocation()
        case .locationUnknown:
            LOG("didFailWithError: locationUnknown - GPS not available yet (normal on startup/simulator)")
            // Temporary failure - GPS not available yet, do nothing and wait
            // This is common on simulator or when device just started
            break
        default:
            LOG("didFailWithError: \(err.code.rawValue) - \(err.localizedDescription)")
        }
    }
}
