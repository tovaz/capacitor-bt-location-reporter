package com.paj.btlocationreporter.livetracking

import com.paj.btlocationreporter.BtLocationReporterPlugin
import com.paj.btlocationreporter.LOG_ERROR

/**
 * Thin bridge between [LiveTrackingManager] and the rest of the plugin.
 *
 * It forwards every manager callback to:
 *   1. The Capacitor plugin instance, so JS listeners receive
 *      `liveTrackingStarted` / `liveTrackingStopped` events.
 *   2. A service-provided `onIntervalChanged` callback, used by the
 *      foreground service to reconfigure the FusedLocation request.
 *
 * Keeping this logic in its own class avoids adding more code to the
 * already-large `BtLocationReporterService` file.
 */
class LiveTrackingServiceBridge(
    private val onIntervalChanged: () -> Unit,
) : LiveTrackingManager.Listener {

    override fun onLiveTrackingStarted(session: LiveTrackingManager.Session) {
        try {
            BtLocationReporterPlugin.instance?.notifyLiveTrackingStarted(session)
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingServiceBridge] notifyLiveTrackingStarted failed: ${e.message}")
        }
        try {
            onIntervalChanged()
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingServiceBridge] onIntervalChanged (start) failed: ${e.message}")
        }
    }

    override fun onLiveTrackingStopped(pajDeviceId: String?, reason: String) {
        try {
            BtLocationReporterPlugin.instance?.notifyLiveTrackingStopped(pajDeviceId, reason)
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingServiceBridge] notifyLiveTrackingStopped failed: ${e.message}")
        }
    }

    override fun onEffectiveIntervalChanged() {
        try {
            onIntervalChanged()
        } catch (e: Exception) {
            LOG_ERROR("[LiveTrackingServiceBridge] onEffectiveIntervalChanged failed: ${e.message}")
        }
    }
}
