# capacitor-bt-location-reporter

Capacitor plugin for **background BLE auto-connect** and **GPS location reporting**.

Keeps BLE devices connected and periodically POSTs GPS coordinates + connected device IDs
to an HTTPS endpoint — even when the app is in the background or the screen is off.

| Platform | Mechanism |
|---|---|
| Android | Foreground Service (type `location\|connectedDevice`) + FusedLocationProvider + BluetoothGatt |
| iOS | `CBCentralManager` (bluetooth-central bg mode) + `CLLocationManager` (location bg mode) + background `URLSession` |
| Web | No-op stub (logs a warning) |

---

## Installation

### 1. Inside the host Capacitor project

```bash
# If published to npm:
npm install @tovaz/capacitor-bt-location-reporter
npx cap sync

# Or from a local path during development:
npm install ../capacitor-bt-location-reporter
npx cap sync
```

### 2. Android — no extra steps needed
Permissions and the ForegroundService declaration are already in the plugin's `AndroidManifest.xml`.

> **Note:** Android 10+ requires the user to grant **Background Location** (`ACCESS_BACKGROUND_LOCATION`) separately.
> The OS will show a second dialog after the regular location permission.
> You can trigger this from your Angular code before calling `BtLocationReporter.start()`.

### 3. iOS — Info.plist entries (required)

Add the following to your app's `ios/App/App/Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
    <string>location</string>
</array>

<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>We need your location in the background to track your PAJ device position.</string>

<key>NSLocationWhenInUseUsageDescription</key>
<string>We need your location to track your PAJ device position.</string>

<key>NSBluetoothAlwaysUsageDescription</key>
<string>We need Bluetooth access to connect to your PAJ tracker.</string>
```

---



## BtLocationReporter.start: Full Configuration

The `start` method receives a configuration object with the following fields:

| Field               | Type                        | Required | Description                                                                                 | Default |
|---------------------|-----------------------------|----------|---------------------------------------------------------------------------------------------|---------|
| devices             | `BtDeviceEntry[]`           | Yes      | List of BLE devices to monitor.                                                             |         |
| reportEndpoint      | `string`                    | Yes      | HTTPS URL that will receive the POST location payload.                                      |         |
| authToken           | `string`                    | No       | Authorization token sent with every POST request.                                           |         |
| reportIntervalMs    | `number`                    | No       | Interval between location reports in milliseconds.                                          | 30000   |
| debug               | `boolean`                   | No       | Enables debug logging to file and console.                                                  | false   |
| texts               | `NotificationTexts`         | No       | Customizable notification texts (see below).                                                |         |
| extraPayloadFields  | `Record<string, unknown>`   | No       | Additional fields merged into every POST payload.                                           |         |

**NotificationTexts:**

| Field           | Type     | Description                                                                                  | Default |
|-----------------|----------|----------------------------------------------------------------------------------------------|---------|
| connectedHeader | string   | Title for BLE connection notification.                                                        | "Device connected" |
| connected       | string   | Body for BLE connection notification. Use `{device}` as placeholder.                         | "{device} connected via Bluetooth, power saving activated" |
| trackerHeader   | string   | Title for foreground service notification.                                                    | "BT Location Reporter" |
| tracker         | string   | Body for foreground service notification.                                                    | "Tracking location in background…" |

### Extended Example

```typescript
import { BtLocationReporter } from '@tovaz/capacitor-bt-location-reporter';

await BtLocationReporter.start({
  devices: [
    { bleDeviceId: 'AA:BB:CC:DD:EE:FF', pajDeviceId: 12345 },
    { bleDeviceId: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx', pajDeviceId: 67890 },
  ],
  reportEndpoint: 'https://api.example.com/v1/bt-location',
  authToken: 'Bearer eyJhbGci...',
  reportIntervalMs: 60000, // 1 minute
  debug: true,
  texts: {
    connectedHeader: 'Tracker connected!',
    connected: '{device} is ready for tracking',
    trackerHeader: 'My Tracking App',
    tracker: 'Location active in background…',
  },
  extraPayloadFields: { customerId: 42, customFlag: true },
});
```

---

## Usage (TypeScript / Angular)

```typescript
import { BtLocationReporter } from '@tovaz/capacitor-bt-location-reporter';

// Start the background service
await BtLocationReporter.start({
  // Each entry maps a BLE hardware ID to the PAJ platform device ID.
  // The plugin uses bleDeviceId to connect/reconnect;
  // pajDeviceId is what gets sent in the location payload.
  devices: [
    { bleDeviceId: 'AA:BB:CC:DD:EE:FF', pajDeviceId: 12345 },  // Android: MAC address
    { bleDeviceId: 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx', pajDeviceId: 67890 },  // iOS: UUID
  ],
  reportEndpoint:   'https://api.example.com/v1/bt-location',
  authToken:        'Bearer eyJhbGci...',
  reportIntervalMs: 30_000,                  // default: 30 s
  debug: false, // default: false (logs to file and console if true)
  texts: {
    trackerHeader: 'PAJ Tracker',            // Foreground notification title
    tracker: 'Monitoring your PAJ device…',  // Foreground notification body
    connected: 'Bluetooth device connected.', // BLE connection notification body
    connectedHeader: 'PAJ bluetooth'          // BLE connection notification title
  },
  extraPayloadFields: { customerId: 42 },
});

// Listen for location reports
await BtLocationReporter.addListener('locationReport', event => {
  console.log('Report sent:', event.payload, 'HTTP:', event.httpStatus);
});

// Listen for BLE connection events
// Note: deviceId here is the BLE hardware ID (not the PAJ ID)
await BtLocationReporter.addListener('bleConnection', event => {
  console.log(event.deviceId, event.connected ? 'connected' : 'disconnected');
});

// Add new devices without restarting the service
await BtLocationReporter.addDevices({
  devices: [{ bleDeviceId: '11:22:33:44:55:66', pajDeviceId: 99999 }],
});

// Remove devices without restarting
await BtLocationReporter.removeDevices({
  devices: [{ bleDeviceId: 'AA:BB:CC:DD:EE:FF', pajDeviceId: 12345 }],
});

// Check if running
const { running } = await BtLocationReporter.isRunning();

// Stop
await BtLocationReporter.stop();
await BtLocationReporter.removeAllListeners();
```


### Payload sent to the endpoint

Only PAJ device IDs of **currently connected** BLE devices are included.

```json
{
  "devicesId":  [12345, 67890],
  "lat":        48.1234,
  "lng":        11.5678,
  "accuracy":   5.0,
  "timestamp":  1710000000000,
  "customerId": 42
}
```
---

## Advanced: Internals & Utilities

### FileLogger

**Android:** `android/src/main/java/com/paj/btlocationreporter/FileLogger.kt`

**iOS:** `ios/Plugin/FileLogger.swift`

Dual logger for debugging the plugin:

- **Console:** Standard Android Logcat or iOS Unified Logging (Xcode/Console.app)
- **File:** Writes to a persistent log file on the device for later inspection

Enable debug logging by setting `debug: true` in the config. You can retrieve the log file path and contents using:

```typescript
await BtLocationReporter.getLogPath(); // { path: string }
await BtLocationReporter.getLogs();    // { logs: string }
```

**Android log file path:** `/data/data/{package}/files/bt-location-reporter.log`

**iOS log file path:** `Documents/bt-location-reporter.log` (inside app sandbox)

---

### GpsSwitcher

**Android:** `android/src/main/java/com/paj/btlocationreporter/GpsSwitcher.kt`

**iOS:** `ios/Plugin/GpsSwitcher.swift`

Manages GPS switch commands for BLE devices:

- When a device connects: sends the `onConnectCommand` (e.g., GPS_OFF) to the device via BLE GATT
- When a device disconnects or location fails: sends the `onDisconnectCommand` (e.g., GPS_ON) to the device

You can specify these commands per device in the `devices` array:

```typescript
devices: [
  {
    bleDeviceId: 'AA:BB:CC:DD:EE:FF',
    pajDeviceId: 12345,
    onConnectCommand: {
      name: 'GPS_OFF',
      serviceUuid: '0000xxxx-0000-1000-8000-00805f9b34fb',
      characteristicUuid: '0000yyyy-0000-1000-8000-00805f9b34fb',
      value: '0'
    },
    onDisconnectCommand: {
      name: 'GPS_ON',
      serviceUuid: '0000xxxx-0000-1000-8000-00805f9b34fb',
      characteristicUuid: '0000yyyy-0000-1000-8000-00805f9b34fb',
      value: '1'
    }
  }
]
```

---

---

## Integration with `bt-auto-connect.service.ts`

Call the plugin from `BtAutoConnectService.start()` after the existing BLE logic:

```typescript
import { BtLocationReporter } from '@tovaz/capacitor-bt-location-reporter';
import { environment } from 'src/environments/environment';

async start(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  // ... existing logic ...

  const linked = this.btUiService.btLinkedDevices.get().value ?? [];

  await BtLocationReporter.start({
    devices: linked.map((d: any) => ({
      bleDeviceId: d.device.deviceId,
      pajDeviceId: d['pajDeviceId'],
    })),
    reportEndpoint:   environment.btLocationEndpoint,
    authToken:        `Bearer ${this.authToken}`,
    reportIntervalMs: 30_000,
    texts: {
      trackerHeader: 'PAJ Tracker',
      tracker: 'Monitoring your PAJ device in background…',
    },
  });
}
```

> The native layer handles BLE reconnection and GPS reporting independently.
> `BtMobileLocationService` can remain active for foreground reporting;
> the plugin takes over when the app goes to the background.

---

## Building the plugin

```bash
cd capacitor-bt-location-reporter
npm install
npm run build
```

---

## Publishing to npm / GitHub Packages

See **PUBLISHING.md** for step-by-step instructions.

---

## Project structure

```
capacitor-bt-location-reporter/
├── src/
│   ├── definitions.ts     # TypeScript types & plugin interface
│   ├── index.ts           # registerPlugin + exports
│   └── web.ts             # Web stub
├── android/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/paj/btlocationreporter/
│           ├── BtLocationReporterPlugin.kt   # Capacitor plugin class
│           ├── BtLocationReporterService.kt  # ForegroundService
│           ├── BleConnectionManager.kt       # BluetoothGatt manager
│           └── BootReceiver.kt               # Auto-restart after reboot
├── ios/Plugin/
│   ├── BtLocationReporterPlugin.m            # ObjC bridge
│   ├── BtLocationReporterPlugin.swift        # Capacitor plugin class
│   ├── BtLocationReporter.swift              # Coordinator
│   ├── BleManager.swift                      # CBCentralManager manager
│   └── LocationReporter.swift                # CLLocationManager wrapper
├── BtLocationReporter.podspec
├── package.json
├── tsconfig.json
└── rollup.config.js
```

---

## License

MIT
