package com.paj.btlocationreporter.livetracking

import android.os.Handler
import android.os.Looper
import com.paj.btlocationreporter.LOG
import com.paj.btlocationreporter.LOG_ERROR
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, non-persistent store for live tracking sessions.
 *
 * A live tracking session temporarily reduces the GPS reporting interval for
 * a specific pajDeviceId. Sessions are kept in memory only — they never touch
 * SharedPreferences and will not survive a plugin `stop()` or a process death.
 *
 * The [Listener] contract lets the running service react to start/stop/expire
 * events (to reconfigure the FusedLocation request) and to notify JS via the
 * plugin's event bus.
 *
 * Thread-safety: `sessions` is a [ConcurrentHashMap] and expiration runs on
 * the main thread via [Handler]. All public entry points are wrapped in
 * try/catch so a misbehaving listener can never crash the caller.
 */
object LiveTrackingManager {

    /** Active sessions keyed by the stringified pajDeviceId. */
    private val sessions = ConcurrentHashMap<String, Session>()

    /** Main-thread handler used to schedule auto-expiration callbacks. */
    private val handler = Handler(Looper.getMainLooper())

    /** Listener registered by the running service, or `null` when stopped. */
    @Volatile
    var listener: Listener? = null

    /**
     * Listener used by the service to react to state changes and to
     * forward events to the JS layer.
     */
    interface Listener {
        fun onLiveTrackingStarted(session: Session)
        /**
         * [pajDeviceId] is null only when [reason] == "stopAll".
         * Valid reasons: "manual", "expired", "stopAll".
         */
        fun onLiveTrackingStopped(pajDeviceId: String?, reason: String)
        /**
         * Called whenever the effective reporting interval may have changed
         * (after a start, stop, stopAll or auto-expiration). The listener
         * is expected to reconfigure the location provider if necessary.
         */
        fun onEffectiveIntervalChanged()
    }

    /**
     * Internal session representation including the scheduled auto-expire
     * runnable. The [Runnable] is explicitly excluded from equality.
     */
    class Session(
        val pajDeviceId: String,
        val intervalSec: Long,
        val durationSec: Long,
        val startedAtMs: Long,
        val expiresAtMs: Long,
    ) {
        /** Internal handle to the pending auto-expire callback. */
        internal var expireRunnable: Runnable? = null

        /** Seconds remaining before the session auto-expires (clamped to 0). */
        val remainingSec: Long
            get() {
                val rem = (expiresAtMs - System.currentTimeMillis()) / 1000L
                return if (rem < 0) 0 else rem
            }
    }

    /**
     * Starts a new session for [pajDeviceId]. If a session was already active
     * for the same id, it is silently replaced (no "stopped" event is emitted
     * for the old one).
     *
     * @return the created [Session] or `null` when the arguments are invalid.
     */
    fun start(pajDeviceId: String, intervalSec: Long, durationSec: Long): Session? {
        return try {
            if (pajDeviceId.isBlank() || intervalSec <= 0L || durationSec <= 0L) {
                LOG_ERROR(
                    "[LiveTrackingManager] start() invalid args: " +
                        "pajDeviceId=$pajDeviceId intervalSec=$intervalSec durationSec=$durationSec"
                )
                return null
            }

            // Replace any previously active session for this id without
            // emitting a stopped event (starting twice is a "replace").
            sessions[pajDeviceId]?.let { cancelPending(it) }

            val now = System.currentTimeMillis()
            val session = Session(
                pajDeviceId = pajDeviceId,
                intervalSec = intervalSec,
                durationSec = durationSec,
                startedAtMs = now,
                expiresAtMs = now + durationSec * 1000L,
            )

            // Schedule auto-expire strictly after `durationSec` seconds.
            val runnable = Runnable {
                try {
                    // Only auto-expire when the session is still the active
                    // one for this id (no other start() overrode it).
                    if (sessions[pajDeviceId] === session) {
                        sessions.remove(pajDeviceId)
                        safeNotifyStopped(pajDeviceId, "expired")
                        safeNotifyIntervalChanged()
                    }
                } catch (e: Exception) {
                    LOG_ERROR("[LiveTrackingManager] expire callback failed: ${e.message}")
                }
            }
            session.expireRunnable = runnable
            handler.postDelayed(runnable, durationSec * 1000L)

            sessions[pajDeviceId] = session
            LOG(
                "[LiveTrackingManager] started pajDeviceId=$pajDeviceId " +
                    "intervalSec=$intervalSec durationSec=$durationSec"
            )

            safeNotifyStarted(session)
            safeNotifyIntervalChanged()
            session
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] start() failed: ${e.message}")
            null
        }
    }

    /**
     * Stops the session for [pajDeviceId] and emits a "manual" stop event.
     * Returns `true` when an active session was actually removed.
     */
    fun stopForDevice(pajDeviceId: String): Boolean {
        return try {
            val removed = sessions.remove(pajDeviceId) ?: return false
            cancelPending(removed)
            safeNotifyStopped(pajDeviceId, "manual")
            safeNotifyIntervalChanged()
            true
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] stopForDevice() failed: ${e.message}")
            false
        }
    }

    /**
     * Stops every active session and emits a single "stopAll" event.
     * Returns the number of sessions that were stopped.
     */
    fun stopAll(): Int {
        return try {
            if (sessions.isEmpty()) return 0
            val count = sessions.size
            sessions.values.forEach { cancelPending(it) }
            sessions.clear()
            safeNotifyStopped(null, "stopAll")
            safeNotifyIntervalChanged()
            count
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] stopAll() failed: ${e.message}")
            0
        }
    }

    /**
     * Returns a snapshot of the currently active sessions. The returned list
     * is safe to iterate outside the manager — no internal references leak.
     */
    fun snapshot(): List<Session> {
        return try {
            val now = System.currentTimeMillis()
            sessions.values.filter { it.expiresAtMs > now }.toList()
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] snapshot() failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Returns the effective reporting interval in milliseconds, combining
     * the default from `start()` with every active live tracking session.
     * The shortest active session wins; when there is none, the default is
     * returned unchanged.
     */
    fun getEffectiveIntervalMs(defaultMs: Long): Long {
        return try {
            val minSessionMs = sessions.values.minOfOrNull { it.intervalSec * 1000L }
                ?: return defaultMs
            if (minSessionMs <= 0L) defaultMs else kotlin.math.min(defaultMs, minSessionMs)
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] getEffectiveIntervalMs() failed: ${e.message}")
            defaultMs
        }
    }

    /** True when there is at least one active live tracking session. */
    fun hasActiveSessions(): Boolean = sessions.isNotEmpty()

    /**
     * Clears all internal state without emitting any events. Intended to be
     * called from the service's `shutdown()` so state does not survive a
     * plugin `stop()`.
     */
    fun clear() {
        try {
            sessions.values.forEach { cancelPending(it) }
            sessions.clear()
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] clear() failed: ${e.message}")
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun cancelPending(s: Session) {
        try {
            s.expireRunnable?.let { handler.removeCallbacks(it) }
            s.expireRunnable = null
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] cancelPending() failed: ${e.message}")
        }
    }

    private fun safeNotifyStarted(session: Session) {
        try {
            listener?.onLiveTrackingStarted(session)
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] listener.onLiveTrackingStarted threw: ${e.message}")
        }
    }

    private fun safeNotifyStopped(pajDeviceId: String?, reason: String) {
        try {
            listener?.onLiveTrackingStopped(pajDeviceId, reason)
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] listener.onLiveTrackingStopped threw: ${e.message}")
        }
    }

    private fun safeNotifyIntervalChanged() {
        try {
            listener?.onEffectiveIntervalChanged()
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingManager] listener.onEffectiveIntervalChanged threw: ${e.message}")
        }
    }
}
