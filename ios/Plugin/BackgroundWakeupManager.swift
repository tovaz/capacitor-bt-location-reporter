import Foundation
import CoreLocation

/**
 * Owns Significant Location Changes (SLC) registration.
 *
 * ## Why this exists
 * SLC can relaunch the app after a user force-quit (swipe away). When iOS
 * relaunches the app it passes `UIApplication.LaunchOptionsKey.location` in
 * `didFinishLaunchingWithOptions`. At that point we must create a
 * `CLLocationManager` *immediately* (before Capacitor finishes loading) to
 * receive the queued location event. If we wait until the plugin's `load()`
 * the event is lost.
 *
 * ## Usage from the consuming app's AppDelegate
 * ```swift
 * func application(_ application: UIApplication,
 *                  didFinishLaunchingWithOptions launchOptions: ...) -> Bool {
 *     if launchOptions?[.location] != nil {
 *         BackgroundWakeupManager.shared.handleLocationLaunch()
 *     }
 *     return true
 * }
 * ```
 *
 * The plugin's `load()` sets `onLocationWakeup` so that when the SLC event
 * arrives (which may be before or after `load()`) the coordinator is restored.
 *
 * ## Normal (non-relaunch) usage
 * `BtLocationReporter.start()` calls `ensureSLCRegistered()` to keep SLC
 * active so a future force-quit can be recovered.
 * `BtLocationReporter.stop()` calls `stopSLC()` to clean up.
 */
public class BackgroundWakeupManager: NSObject {

    /// Shared singleton — access from AppDelegate and the plugin.
    public static let shared = BackgroundWakeupManager()

    /// UserDefaults key written when SLC fires to signal a pending restore.
    public static let pendingRestoreKey = "BtLocationReporterPlugin.pendingLocationRestore"

    private var locationManager: CLLocationManager?

    /**
     * Called by the plugin's `load()`.
     * Invoked on the main actor when SLC delivers a location event (either
     * before or after the plugin finished loading).
     */
    public var onLocationWakeup: (() -> Void)?

    private override init() {
        super.init()
    }

    // MARK: - Public API

    /**
     * Call from `AppDelegate.didFinishLaunchingWithOptions` when
     * `launchOptions[.location]` is present.
     * Creates the CLLocationManager immediately so the queued SLC event is
     * delivered to our delegate before iOS re-suspends the app.
     */
    public func handleLocationLaunch() {
        LOG("[BackgroundWakeupManager] App relaunched by SLC — initialising CLLocationManager early")
        createManagerIfNeeded()
    }

    /**
     * Ensures SLC is registered. Call from `BtLocationReporter.start()`.
     * Safe to call multiple times — only creates the manager once.
     */
    public func ensureSLCRegistered() {
        createManagerIfNeeded()
        LOG("[BackgroundWakeupManager] SLC registration confirmed")
    }

    /**
     * Stops SLC. Call from `BtLocationReporter.stop()`.
     */
    public func stopSLC() {
        locationManager?.stopMonitoringSignificantLocationChanges()
        locationManager?.delegate = nil
        locationManager = nil
        LOG("[BackgroundWakeupManager] SLC stopped")
    }

    // MARK: - Private

    private func createManagerIfNeeded() {
        guard locationManager == nil else { return }
        let mgr = CLLocationManager()
        mgr.delegate = self
        mgr.startMonitoringSignificantLocationChanges()
        locationManager = mgr
    }
}

// MARK: - CLLocationManagerDelegate

extension BackgroundWakeupManager: CLLocationManagerDelegate {

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        LOG("[BackgroundWakeupManager] SLC location event received — marking pending restore")
        // Write the flag before calling the closure so restoreIfPending()
        // always sees it even if onLocationWakeup is not set yet.
        UserDefaults.standard.set(true, forKey: BackgroundWakeupManager.pendingRestoreKey)
        onLocationWakeup?()
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let err = error as? CLError, err.code == .locationUnknown { return }
        LOG_ERROR("[BackgroundWakeupManager] SLC error: \(error.localizedDescription)")
    }

    public func locationManager(_ manager: CLLocationManager,
                                didChangeAuthorization status: CLAuthorizationStatus) {
        LOG("[BackgroundWakeupManager] Auth status: \(status.rawValue)")
    }
}
