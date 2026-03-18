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

    override init() {
        super.init()
        manager.delegate              = self
        manager.desiredAccuracy       = kCLLocationAccuracyBest
        manager.distanceFilter        = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        // Background settings will be configured when actually starting location updates
    }
    
    /// Configures background mode and starts location updates
    private func startLocationUpdates() {
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /// Requests "Always" location authorization and calls completion when resolved.
    func requestAlwaysPermission(completion: @escaping (Bool) -> Void) {
        let status = manager.authorizationStatus
        switch status {
        case .authorizedAlways:
            startLocationUpdates()
            completion(true)
        case .authorizedWhenInUse:
            // Upgrade to Always
            permissionCompletion = completion
            manager.requestAlwaysAuthorization()
        case .notDetermined:
            permissionCompletion = completion
            manager.requestAlwaysAuthorization()
        default:
            // denied / restricted
            completion(false)
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    // ── CLLocationManagerDelegate ─────────────────────────────────────────

    func locationManager(_ manager: CLLocationManager,
                         didChangeAuthorization status: CLAuthorizationStatus) {
        switch status {
        case .authorizedAlways:
            startLocationUpdates()
            permissionCompletion?(true)
            permissionCompletion = nil
        case .denied, .restricted:
            permissionCompletion?(false)
            permissionCompletion = nil
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager,
                         didUpdateLocations locations: [CLLocation]) {
        lastLocation = locations.last
    }

    func locationManager(_ manager: CLLocationManager,
                         didFailWithError error: Error) {
        guard let err = error as? CLError else { return }
        
        switch err.code {
        case .denied:
            // Permission revoked at runtime
            manager.stopUpdatingLocation()
        case .locationUnknown:
            // Temporary failure - GPS not available yet, do nothing and wait
            // This is common on simulator or when device just started
            break
        default:
            // Other errors - log but continue
            print("[LocationReporter] CLError: \(err.localizedDescription)")
        }
    }
}
