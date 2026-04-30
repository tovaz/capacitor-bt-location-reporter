/**
 * BLE GATT command to write to a characteristic.
 */
export interface BleCommand {
  /** Human-readable name for logging */
  name: string;
  /** GATT service UUID */
  serviceUuid: string;
  /** GATT characteristic UUID */
  characteristicUuid: string;
  /** String value to write (will be converted to UTF-8 bytes) */
  value: string;
}

/**
 * Maps a BLE device ID to its corresponding PAJ device ID.
 * The plugin connects/reconnects using bleDeviceId, but reports using pajDeviceId.
 */
export interface BtDeviceEntry {
  /** BLE hardware ID — MAC address on Android, UUID string on iOS */
  bleDeviceId: string;
  /** PAJ platform device ID sent in every location report */
  pajDeviceId: string | number;
  /** Command to send when device connects (e.g., GPS_OFF to disable device GPS) */
  onConnectCommand?: BleCommand;
  /** Command to send when device disconnects (e.g., GPS_ON to enable device GPS) */
  onDisconnectCommand?: BleCommand;
}

/**
 * Customizable notification texts.
 */
export interface NotificationTexts {
  /** Title for BLE connection notification. Default: "Device connected" */
  connectedHeader?: string;
  /** Body for BLE connection notification. Use {device} as placeholder. Default: "{device} connected via Bluetooth, power saving activated" */
  connected?: string;
  /** Title for foreground service notification. Default: "BT Location Reporter" */
  trackerHeader?: string;
  /** Text for foreground service notification. Default: "Tracking location in background…" */
  tracker?: string;
}

/**
 * Configuration passed to the plugin when starting the background service.
 */
export interface BtLocationConfig {
  /**
   * Enable debug logging. Default: false.
   * When true, logs are written to file and console.
   */
  debug?: boolean;

  /**
   * Customizable notification texts.
   */
  texts?: NotificationTexts;

  /**
   * List of devices to connect and monitor.
   * BLE connection uses bleDeviceId; location reports use pajDeviceId.
   */
  devices: BtDeviceEntry[];

  /**
   * HTTPS endpoint that will receive the POST payload on every location tick.
   * @example "https://api.example.com/v1/bt-location"
   */
  reportEndpoint: string;

  /**
   * Authorization header value sent with every POST request.
   * @example "Bearer eyJhbGciOi..."
   */
  authToken?: string;

  /**
   * Interval in milliseconds between location reports. Default: 30 000 (30 s).
   */
  reportIntervalMs?: number;

  /**
   * Additional arbitrary fields merged into every POST payload.
   */
  extraPayloadFields?: Record<string, unknown>;
}

/**
 * Payload shape sent in every location report POST.
 */
export interface BtLocationPayload {
  /** PAJ device IDs of all currently connected BLE devices */
  devicesId: (string | number)[];
  lat: number;
  lng: number;
  accuracy: number;
  timestamp: number;
  [key: string]: unknown;
}

/**
 * Event emitted when the service delivers a new location report.
 */
export interface LocationReportEvent {
  payload: BtLocationPayload;
  /** HTTP response status (0 = offline / error). */
  httpStatus: number;
  success: boolean;
}

/**
 * Event emitted when a BLE device connects or disconnects.
 */
export interface BleConnectionEvent {
  deviceId: string;
  connected: boolean;
}

/**
 * Event emitted when the first BLE device connects and location permission is needed.
 * The app should call requestLocationPermission() in response.
 */
export interface LocationPermissionRequiredEvent {
  reason: string;
}

/**
 * Live tracking session for a specific pajDeviceId.
 * Temporarily shortens the GPS reporting interval for a limited time window.
 * Sessions are kept in memory only (non-persistent).
 */
export interface LiveTrackingSession {
  /** PAJ device id the session is bound to */
  pajDeviceId: string | number;
  /** Shortened reporting interval in seconds */
  intervalSec: number;
  /** Total duration of the session in seconds */
  durationSec: number;
  /** Seconds remaining before the session auto-expires */
  remainingSec: number;
  /** Epoch ms timestamp when the session was started */
  startedAt: number;
  /** Epoch ms timestamp when the session will expire */
  expiresAt: number;
}

/**
 * Event emitted when a live tracking session is started for a device.
 */
export interface LiveTrackingStartedEvent {
  pajDeviceId: string | number;
  intervalSec: number;
  durationSec: number;
  startedAt: number;
  expiresAt: number;
}

/**
 * Event emitted when a live tracking session is stopped.
 * If `pajDeviceId` is null, every active session was stopped at once.
 * `reason` indicates whether it was a manual stop or an automatic expiration.
 */
export interface LiveTrackingStoppedEvent {
  pajDeviceId: string | number | null;
  reason: 'manual' | 'expired' | 'stopAll';
}

/**
 * Status of all required permissions and hardware states.
 * Use checkPermissions() to query and requestPermissions() to prompt the user.
 */
export interface PermissionsStatus {
  /** Whether location permission is granted (ACCESS_FINE_LOCATION / CLAuthorizationStatus) */
  locationPermission: 'granted' | 'denied';
  /** Whether Bluetooth permission is granted (Android 12+ BLUETOOTH_CONNECT/SCAN; iOS 13+ CBManagerAuthorization). Always "granted" on Android < 12. */
  bluetoothPermission: 'granted' | 'denied';
  /** Whether the Bluetooth radio is currently powered on */
  bluetoothEnabled: boolean;
  /** Whether the device has an active internet connection */
  internetAvailable: boolean;
  /** Convenience: true iff locationPermission and bluetoothPermission are "granted" AND bluetoothEnabled is true */
  allGranted: boolean;
}

/**
 * Main plugin interface.
 */
export interface BtLocationReporterPlugin {
  /**
   * Starts the background service.
   * On Android, launches a Foreground Service with a persistent notification.
   * On iOS, activates CBCentralManager + CLLocationManager background modes.
   */
  start(options: BtLocationConfig): Promise<void>;

  /**
   * Stops the background service and releases all resources.
   */
  stop(): Promise<void>;

  /**
   * Returns whether the background service is currently running.
   */
  isRunning(): Promise<{ running: boolean }>;

  /**
   * Adds additional devices to monitor without restarting the service.
   * Both bleDeviceId (for BLE connection) and pajDeviceId (for reports) are required.
   */
  addDevices(options: { devices: BtDeviceEntry[] }): Promise<void>;

  /**
   * Removes devices from monitoring without restarting the service.
   * Matched by bleDeviceId.
   */
  removeDevices(options: { devices: BtDeviceEntry[] }): Promise<void>;

  /**
   * [DEBUG] Returns the path to the log file on the device.
   * iOS: Documents/bt-location-reporter.log
   */
  getLogPath(): Promise<{ path: string }>;

  /**
   * [DEBUG] Returns the contents of the log file.
   */
  getLogs(): Promise<{ logs: string }>;

  /**
   * Request location permission from the user.
   * Call this in response to the 'locationPermissionRequired' event.
   */
  requestLocationPermission(): Promise<{ granted: boolean }>;

  /**
   * Check if location permission has been granted.
   */
  hasLocationPermission(): Promise<{ granted: boolean }>;

  /**
   * Returns the current status of all required permissions and hardware states.
   * Does NOT prompt the user — safe to call at any time.
   */
  checkPermissions(): Promise<PermissionsStatus>;

  /**
   * Requests the specified permissions in sequence. Returns the final PermissionsStatus.
   *
   * - `options.permissions` omitted / `null` / `[]` → requests all missing permissions (Bluetooth first, then location).
   * - Otherwise only requests the listed permissions. Accepted values:
   *   - `'bluetooth'`     → BLUETOOTH_CONNECT + BLUETOOTH_SCAN (Android 12+) / CBCentralManager auth (iOS)
   *   - `'bluetoothscan'` → same as `'bluetooth'` (alias kept for convenience)
   *   - `'location'`      → ACCESS_FINE_LOCATION (Android) / CLLocationManager (iOS)
   *
   * Always returns the full PermissionsStatus regardless of which permissions were requested.
   */
  requestPermissions(options?: { permissions?: ('bluetooth' | 'location' | 'bluetoothscan')[] }): Promise<PermissionsStatus>;

  /**
   * Fired whenever a permission or hardware state changes (Bluetooth on/off,
   * location permission granted/denied, location services toggled).
   */
  addListener(
    eventName: 'permissionsChanged',
    listenerFunc: (event: PermissionsStatus) => void,
  ): Promise<{ remove: () => void }>;

  /**
   * Fired whenever the native layer successfully posts a location report.
   * Also fired on failure, with success=false.
   */
  addListener(
    eventName: 'locationReport',
    listenerFunc: (event: LocationReportEvent) => void,
  ): Promise<{ remove: () => void }>;

  /**
   * Fired when a monitored BLE device connects or disconnects.
   */
  addListener(
    eventName: 'bleConnection',
    listenerFunc: (event: BleConnectionEvent) => void,
  ): Promise<{ remove: () => void }>;

  /**
   * Fired when the first BLE device connects and location permission is required.
   * Call requestLocationPermission() in response.
   */
  addListener(
    eventName: 'locationPermissionRequired',
    listenerFunc: (event: LocationPermissionRequiredEvent) => void,
  ): Promise<{ remove: () => void }>;

  /**
   * Fired when a live tracking session is successfully started.
   */
  addListener(
    eventName: 'liveTrackingStarted',
    listenerFunc: (event: LiveTrackingStartedEvent) => void,
  ): Promise<{ remove: () => void }>;

  /**
   * Fired when a live tracking session is stopped — either manually,
   * automatically after duration elapses, or as part of a stopAll call.
   */
  addListener(
    eventName: 'liveTrackingStopped',
    listenerFunc: (event: LiveTrackingStoppedEvent) => void,
  ): Promise<{ remove: () => void }>;

  /**
   * Remove all native event listeners.
   */
  removeAllListeners(): Promise<void>;

  /**
   * Writes data to a BLE characteristic without waiting for a response from the device.
   * Equivalent to BleClient.writeWithoutResponse.
   *
   * @param options.deviceId  BLE device UUID (same as used in `devices[].bleDeviceId`).
   * @param options.service   GATT service UUID.
   * @param options.characteristic GATT characteristic UUID.
   * @param options.value     Bytes to write as an array of numbers (0–255).
   *                          Convert a DataView: Array.from(new Uint8Array(dv.buffer, dv.byteOffset, dv.byteLength))
   */
  writeWithoutResponse(options: {
    deviceId: string;
    service: string;
    characteristic: string;
    value: number[];
  }): Promise<void>;

  /**
   * Temporarily reduces the GPS reporting interval for a specific device.
   *
   * Live tracking sessions are kept in memory only — they are NOT persisted
   * and will not survive a `stop()` call or a process death.
   *
   * While any live tracking session is active the effective reporting
   * interval used by the running service becomes the minimum between the
   * default `reportIntervalMs` and every active session interval.
   *
   * Starting a session for the same `pajDeviceId` twice overrides the
   * previous session.
   *
   * Emits the `liveTrackingStarted` event on success.
   *
   * @param options.pajDeviceId   PAJ device id the session is bound to.
   * @param options.intervalSec   Shortened reporting interval in seconds.
   * @param options.durationSec   How long the session should run, in seconds.
   *                              After this time the session auto-stops and
   *                              `liveTrackingStopped` is emitted with
   *                              `reason: 'expired'`.
   */
  startLiveTracking(options: {
    pajDeviceId: string | number;
    intervalSec: number;
    durationSec: number;
  }): Promise<void>;

  /**
   * Stops a live tracking session.
   *
   * - When called with `{ pajDeviceId }`, stops only that device's session
   *   and emits `liveTrackingStopped` with `reason: 'manual'`.
   * - When called without arguments (or with an empty object), stops every
   *   active live tracking session and emits `liveTrackingStopped` with
   *   `reason: 'stopAll'` and `pajDeviceId: null`.
   *
   * Returns silently when there is nothing to stop (no crash, no reject).
   */
  stopLiveTracking(options?: {
    pajDeviceId?: string | number;
  }): Promise<void>;

  /**
   * Returns the list of currently active live tracking sessions, including
   * how many seconds each session has left before auto-expiring.
   */
  getLiveTrackingDevices(): Promise<{ devices: LiveTrackingSession[] }>;
}
