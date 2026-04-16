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
    isRunning(): Promise<{
        running: boolean;
    }>;
    /**
     * Adds additional devices to monitor without restarting the service.
     * Both bleDeviceId (for BLE connection) and pajDeviceId (for reports) are required.
     */
    addDevices(options: {
        devices: BtDeviceEntry[];
    }): Promise<void>;
    /**
     * Removes devices from monitoring without restarting the service.
     * Matched by bleDeviceId.
     */
    removeDevices(options: {
        devices: BtDeviceEntry[];
    }): Promise<void>;
    /**
     * [DEBUG] Returns the path to the log file on the device.
     * iOS: Documents/bt-location-reporter.log
     */
    getLogPath(): Promise<{
        path: string;
    }>;
    /**
     * [DEBUG] Returns the contents of the log file.
     */
    getLogs(): Promise<{
        logs: string;
    }>;
    /**
     * Request location permission from the user.
     * Call this in response to the 'locationPermissionRequired' event.
     */
    requestLocationPermission(): Promise<{
        granted: boolean;
    }>;
    /**
     * Check if location permission has been granted.
     */
    hasLocationPermission(): Promise<{
        granted: boolean;
    }>;
    /**
     * Fired whenever the native layer successfully posts a location report.
     * Also fired on failure, with success=false.
     */
    addListener(eventName: 'locationReport', listenerFunc: (event: LocationReportEvent) => void): Promise<{
        remove: () => void;
    }>;
    /**
     * Fired when a monitored BLE device connects or disconnects.
     */
    addListener(eventName: 'bleConnection', listenerFunc: (event: BleConnectionEvent) => void): Promise<{
        remove: () => void;
    }>;
    /**
     * Fired when the first BLE device connects and location permission is required.
     * Call requestLocationPermission() in response.
     */
    addListener(eventName: 'locationPermissionRequired', listenerFunc: (event: LocationPermissionRequiredEvent) => void): Promise<{
        remove: () => void;
    }>;
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
}
