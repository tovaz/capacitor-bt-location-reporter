import Foundation

/**
 * In-memory, non-persistent store for live tracking sessions on iOS.
 *
 * Mirrors the Android `LiveTrackingManager` one-to-one. A live tracking
 * session temporarily reduces the GPS reporting interval for a specific
 * pajDeviceId. Sessions are kept in memory only — they never touch
 * UserDefaults and will not survive a plugin `stop()` or a process death.
 *
 * All state mutations run on the main actor so scheduling [Timer] objects
 * is safe without extra locks. Public entry points guard against obvious
 * misuse (empty id, non-positive interval or duration).
 */
@MainActor
final class LiveTrackingManager {

    /** Process-wide singleton. */
    static let shared = LiveTrackingManager()

    /** Callback surface used by the coordinator / plugin. */
    protocol Listener: AnyObject {
        func onLiveTrackingStarted(_ session: Session)
        /// `pajDeviceId` is nil only when `reason == "stopAll"`.
        /// Valid reasons: "manual", "expired", "stopAll".
        func onLiveTrackingStopped(pajDeviceId: String?, reason: String)
        /// Invoked whenever the effective reporting interval may have changed.
        func onEffectiveIntervalChanged()
    }

    /** Read-only view of a live tracking session. */
    struct Session {
        let pajDeviceId: String
        let intervalSec: Double
        let durationSec: Double
        let startedAt: Date
        let expiresAt: Date

        /// Seconds left before the session auto-expires (clamped to 0).
        var remainingSec: Double { max(0, expiresAt.timeIntervalSinceNow) }
    }

    /** Listener set by the coordinator/plugin, weak to avoid retain cycles. */
    weak var listener: Listener?

    // Active sessions keyed by the stringified pajDeviceId.
    private var sessions: [String: Session] = [:]
    // Pending expiration timers keyed by the stringified pajDeviceId.
    private var timers: [String: Timer] = [:]

    private init() {}

    // MARK: - Public API

    /// Starts a new session for `pajDeviceId`. Replaces any previously active
    /// session for the same id. Returns the created session or `nil` on invalid
    /// arguments.
    @discardableResult
    func start(pajDeviceId: String, intervalSec: Double, durationSec: Double) -> Session? {
        let trimmed = pajDeviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, intervalSec > 0, durationSec > 0 else {
            LOG_ERROR("[LiveTrackingManager] start() invalid args: id=\(pajDeviceId) interval=\(intervalSec) duration=\(durationSec)")
            return nil
        }

        // Replace any existing session for this id — starting twice is a replace.
        cancelPending(pajDeviceId: trimmed)

        let now = Date()
        let session = Session(
            pajDeviceId: trimmed,
            intervalSec: intervalSec,
            durationSec: durationSec,
            startedAt: now,
            expiresAt: now.addingTimeInterval(durationSec)
        )
        sessions[trimmed] = session

        // Schedule auto-expiration exactly once.
        let timer = Timer.scheduledTimer(withTimeInterval: durationSec, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.autoExpire(pajDeviceId: trimmed)
            }
        }
        // Keep timer alive across runloop modes (background timers sometimes
        // stop firing when attached to the default mode only).
        RunLoop.main.add(timer, forMode: .common)
        timers[trimmed] = timer

        LOG("[LiveTrackingManager] started pajDeviceId=\(trimmed) intervalSec=\(intervalSec) durationSec=\(durationSec)")

        safeNotifyStarted(session)
        safeNotifyIntervalChanged()
        return session
    }

    /// Manually stops the session for `pajDeviceId`. Returns true when a
    /// session was actually removed.
    @discardableResult
    func stopForDevice(pajDeviceId: String) -> Bool {
        guard sessions[pajDeviceId] != nil else { return false }
        sessions.removeValue(forKey: pajDeviceId)
        cancelPending(pajDeviceId: pajDeviceId)
        safeNotifyStopped(pajDeviceId: pajDeviceId, reason: "manual")
        safeNotifyIntervalChanged()
        return true
    }

    /// Stops every active session and emits a single "stopAll" event. Returns
    /// the number of sessions that were stopped.
    @discardableResult
    func stopAll() -> Int {
        if sessions.isEmpty { return 0 }
        let count = sessions.count
        timers.values.forEach { $0.invalidate() }
        timers.removeAll()
        sessions.removeAll()
        safeNotifyStopped(pajDeviceId: nil, reason: "stopAll")
        safeNotifyIntervalChanged()
        return count
    }

    /// Returns a snapshot of the currently active sessions with up-to-date
    /// remaining times.
    func snapshot() -> [Session] {
        let now = Date()
        return sessions.values.filter { $0.expiresAt > now }
    }

    /// Returns the effective reporting interval in milliseconds given a
    /// default (usually the configured `reportIntervalMs`). The shortest
    /// active session wins; when there is no active session the default is
    /// returned unchanged.
    func getEffectiveIntervalMs(defaultMs: Double) -> Double {
        guard let minIntervalSec = sessions.values.map({ $0.intervalSec }).min() else {
            return defaultMs
        }
        let minMs = minIntervalSec * 1000.0
        if minMs <= 0 { return defaultMs }
        return Swift.min(defaultMs, minMs)
    }

    /// True when there is at least one active live tracking session.
    var hasActiveSessions: Bool { !sessions.isEmpty }

    /// Wipes internal state without emitting events. Called from the
    /// coordinator's `stop()` so state never outlives the session.
    func clear() {
        timers.values.forEach { $0.invalidate() }
        timers.removeAll()
        sessions.removeAll()
    }

    // MARK: - Private helpers

    private func autoExpire(pajDeviceId: String) {
        guard sessions[pajDeviceId] != nil else { return }
        sessions.removeValue(forKey: pajDeviceId)
        timers.removeValue(forKey: pajDeviceId)
        LOG("[LiveTrackingManager] auto-expired pajDeviceId=\(pajDeviceId)")
        safeNotifyStopped(pajDeviceId: pajDeviceId, reason: "expired")
        safeNotifyIntervalChanged()
    }

    private func cancelPending(pajDeviceId: String) {
        timers[pajDeviceId]?.invalidate()
        timers.removeValue(forKey: pajDeviceId)
    }

    // Wrap every listener call in do/catch so a throwing listener cannot
    // propagate an error back into the manager (and, given Swift, we just
    // log unexpected failures defensively).
    private func safeNotifyStarted(_ session: Session) {
        guard let listener = listener else { return }
        listener.onLiveTrackingStarted(session)
    }

    private func safeNotifyStopped(pajDeviceId: String?, reason: String) {
        guard let listener = listener else { return }
        listener.onLiveTrackingStopped(pajDeviceId: pajDeviceId, reason: reason)
    }

    private func safeNotifyIntervalChanged() {
        guard let listener = listener else { return }
        listener.onEffectiveIntervalChanged()
    }
}
