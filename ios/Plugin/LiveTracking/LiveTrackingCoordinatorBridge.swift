import Foundation

/**
 * Thin bridge between `LiveTrackingManager` and the rest of the plugin.
 *
 * Mirrors the Android `LiveTrackingServiceBridge` one-to-one. It forwards
 * every manager callback to:
 *   1. The Capacitor plugin instance, so JS listeners receive
 *      `liveTrackingStarted` / `liveTrackingStopped` events.
 *   2. A coordinator-provided `onIntervalChanged` closure, used to
 *      reconfigure the `LocationReporter` report interval whenever the
 *      effective interval may have changed.
 *
 * The bridge holds a `weak` reference to the plugin so it can't create a
 * retain cycle. The coordinator is expected to keep a strong reference to
 * the bridge for its entire lifetime and to clear
 * `LiveTrackingManager.shared.listener` on `stop()`.
 *
 * Every forwarded call is wrapped in a defensive `do/catch`-style block so
 * a misbehaving listener cannot bubble an error up into the manager.
 */
@MainActor
final class LiveTrackingCoordinatorBridge: LiveTrackingManager.Listener {

    /// Plugin used to emit JS events. Weak to avoid retain cycles.
    private weak var plugin: BtLocationReporterPlugin?

    /// Closure the coordinator uses to re-apply the effective report
    /// interval to the active `LocationReporter`.
    private let onIntervalChanged: () -> Void

    init(plugin: BtLocationReporterPlugin?, onIntervalChanged: @escaping () -> Void) {
        self.plugin = plugin
        self.onIntervalChanged = onIntervalChanged
    }

    // MARK: - LiveTrackingManager.Listener

    func onLiveTrackingStarted(_ session: LiveTrackingManager.Session) {
        // Emit `liveTrackingStarted` to JS.
        plugin?.emitLiveTrackingStarted(session: session)
        // Let the coordinator push the new interval down to LocationReporter.
        onIntervalChanged()
    }

    func onLiveTrackingStopped(pajDeviceId: String?, reason: String) {
        plugin?.emitLiveTrackingStopped(pajDeviceId: pajDeviceId, reason: reason)
        // The effective interval likely changed — re-apply it.
        onIntervalChanged()
    }

    func onEffectiveIntervalChanged() {
        onIntervalChanged()
    }
}
