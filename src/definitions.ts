/**
 * Maps a BLE device ID to its corresponding PAJ device ID.
 * The plugin connects/reconnects using bleDeviceId, but reports using pajDeviceId.
 */
export interface BtDeviceEntry {
  /** BLE hardware ID — MAC address on Android, UUID string on iOS */
  bleDeviceId: string;
  /** PAJ platform device ID sent in every location report */
  pajDeviceId: string | number;
}

/**
 * Configuration passed to the plugin when starting the background service.
 */
export interface BtLocationConfig {
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
   * Android only — title shown in the persistent foreground service notification.
   * Default: "BT Location Reporter"
   */
  notificationTitle?: string;

  /**
   * Android only — body text for the foreground service notification.
   * Default: "Tracking location in background…"
   */
  notificationText?: string;

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
   * Remove all native event listeners.
   */
  removeAllListeners(): Promise<void>;
}
