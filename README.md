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
npm install capacitor-bt-location-reporter
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

## Usage (TypeScript / Angular)

```typescript
import { BtLocationReporter } from 'capacitor-bt-location-reporter';

// Start the background service
await BtLocationReporter.start({
  deviceIds:        ['AA:BB:CC:DD:EE:FF'],   // Android MACs / iOS UUIDs
  reportEndpoint:   'https://api.example.com/v1/bt-location',
  authToken:        'Bearer eyJhbGci...',
  reportIntervalMs: 30_000,                  // default: 30 s
  notificationTitle: 'PAJ Tracker',          // Android only
  notificationText:  'Monitoring your PAJ device…',
  extraPayloadFields: { customerId: 42 },
});

// Listen for location reports
await BtLocationReporter.addListener('locationReport', event => {
  console.log('Report sent:', event.payload, 'HTTP:', event.httpStatus);
});

// Listen for BLE connection events
await BtLocationReporter.addListener('bleConnection', event => {
  console.log(event.deviceId, event.connected ? 'connected' : 'disconnected');
});

// Add a new device without restarting
await BtLocationReporter.addDevices({ deviceIds: ['11:22:33:44:55:66'] });

// Check if running
const { running } = await BtLocationReporter.isRunning();

// Stop
await BtLocationReporter.stop();
await BtLocationReporter.removeAllListeners();
```

### Payload sent to the endpoint

```json
{
  "deviceIds":          ["AA:BB:CC:DD:EE:FF"],
  "connectedDeviceIds": ["AA:BB:CC:DD:EE:FF"],
  "lat":       48.1234,
  "lng":       11.5678,
  "accuracy":  5.0,
  "timestamp": 1710000000000,
  "customerId": 42
}
```

---

## Integration with existing services

In `bt-auto-connect.service.ts`, call the plugin when the service starts:

```typescript
import { BtLocationReporter } from 'capacitor-bt-location-reporter';

async start(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  // ... existing logic ...

  const ids = linked.map(d => d.device.deviceId);
  await BtLocationReporter.start({
    deviceIds:      ids,
    reportEndpoint: environment.btLocationEndpoint,
    authToken:      `Bearer ${this.authToken}`,
  });
}
```

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
