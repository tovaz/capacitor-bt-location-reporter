# CLAUDE.md

> AI-oriented reference for the `@tovaz/capacitor-bt-location-reporter` plugin.
> This document describes the full surface area (public API, native
> implementations, runtime flow, persistence, permissions and edge cases) so
> that any AI assistant can safely reason about, extend or debug the plugin
> without re-reading every source file.

---

## 1. Project overview

- **Package name:** `@tovaz/capacitor-bt-location-reporter`
- **Version:** `0.3.3` (npm), `0.1.0` (iOS podspec `TovazCapacitorBtLocationReporter`)
- **Purpose:** A Capacitor 7 plugin for **Android** and **iOS** that runs a
  long-lived background session which (a) auto-connects to a fixed list of
  BLE devices, (b) obtains GPS location while at least one device is
  connected, and (c) POSTs device IDs + location to a configurable HTTPS
  endpoint. It also sends optional GATT "GPS_OFF / GPS_ON" commands to the
  peripheral when it connects/disconnects or when the report fails.
- **Web platform:** stub only (all methods are no-ops with a console warning —
  see `src/web.ts`).
- **Capacitor JS id:** `BtLocationReporter` (registered in `src/index.ts`).
- **Android:**
  - `com.android.library`, Kotlin 1.9.22
  - `minSdk = 23`, `target/compileSdk = 35`
  - Java/Kotlin target 17
  - Package: `com.paj.btlocationreporter`
  - Key deps: Nordic BLE `2.7.5`, Google FusedLocation `21.3.0`,
    OkHttp `4.12.0`, Coroutines `1.9.0`, WorkManager `2.10.0`
- **iOS:**
  - Deployment target `iOS 14.0`, Swift `5.9`
  - Single-target CocoaPods spec consumes `ios/Plugin/**/*.swift`
- **Source tree:**
  ```
  capacitor-bt-location-reporter/
  ├── src/                                  TypeScript API (definitions, index, web stub)
  │   ├── definitions.ts                    All public interfaces, methods, events
  │   ├── index.ts                          registerPlugin('BtLocationReporter', ...)
  │   └── web.ts                            BtLocationReporterWeb no-op class
  ├── android/
  │   ├── build.gradle
  │   └── src/main/
  │       ├── AndroidManifest.xml           Permissions, service, receivers
  │       └── java/com/paj/btlocationreporter/
  │           ├── BtLocationReporterPlugin.kt   Capacitor entry (@PluginMethod)
  │           ├── BtLocationReporterService.kt  Foreground service (BLE + GPS + HTTP)
  │           ├── BleConnectionManager.kt        Direct GATT connect/reconnect/writes
  │           ├── GpsSwitcher.kt                 onConnect/onDisconnect GATT writes
  │           ├── BleScanReceiver.kt             PendingIntent-based BLE scan wakeup
  │           ├── BootReceiver.kt                Auto-restart on BOOT_COMPLETED
  │           ├── ConfigStore.kt                 SharedPreferences config_json
  │           ├── LinkedDeviceStore.kt           SharedPreferences linked_device_ids
  │           └── FileLogger.kt                  Logcat + file logger
  ├── ios/Plugin/
  │   ├── BtLocationReporterPlugin.swift    CAPPlugin entry + UserDefaults config store
  │   ├── BtLocationReporter.swift          @MainActor coordinator (BLE+Loc+HTTP)
  │   ├── BleManager.swift                  CBCentralManager (with state restoration)
  │   ├── LocationReporter.swift            CLLocationManager (background capable)
  │   ├── GpsSwitcher.swift                 onConnect/onDisconnect GATT writes
  │   └── FileLogger.swift                  NSLog + file logger
  ├── TovazCapacitorBtLocationReporter.podspec
  ├── package.json
  ├── rollup.config.js
  └── tsconfig.json
  ```

---

## 2. Public TypeScript API

All types live in `src/definitions.ts`. The exported plugin instance is
`BtLocationReporter` (from `src/index.ts`).

### 2.1 Methods on `BtLocationReporterPlugin`

| Method | Input | Output | Purpose |
|---|---|---|---|
| `start(options: BtLocationConfig)` | see §2.2 | `Promise<void>` | Start the background session. Android launches a foreground service; iOS activates the BLE + Location coordinator. Idempotent when already running (Android updates the running config/device set in place). |
| `stop()` | — | `Promise<void>` | Stop the session, release BLE/GPS, clear persisted config. |
| `isRunning()` | — | `Promise<{ running: boolean }>` | Whether the native session is active. |
| `addDevices({ devices })` | `{ devices: BtDeviceEntry[] }` | `Promise<void>` | Add more devices to the running session. No restart needed. |
| `removeDevices({ devices })` | `{ devices: BtDeviceEntry[] }` (matched by `bleDeviceId`) | `Promise<void>` | Remove devices from the running session. |
| `requestLocationPermission()` | — | `Promise<{ granted: boolean }>` | Programmatically request location permission. Intended as the response to `locationPermissionRequired`. |
| `hasLocationPermission()` | — | `Promise<{ granted: boolean }>` | Check current permission state without prompting. |
| `writeWithoutResponse({ deviceId, service, characteristic, value })` | `{ deviceId: string, service: UUID string, characteristic: UUID string, value: number[] }` (0–255 bytes) | `Promise<void>` | GATT write with `WRITE_TYPE_NO_RESPONSE` / `.withoutResponse`. Requires the device to be connected. Auto-discovers services if not cached. |
| `getLogPath()` | — | `Promise<{ path: string }>` | Absolute path of the internal debug log file. |
| `getLogs()` | — | `Promise<{ logs: string }>` | Full contents of the internal debug log file. |
| `addListener('locationReport', cb)` | `LocationReportEvent` | listener handle | Emitted after every POST (success or failure). |
| `addListener('bleConnection', cb)` | `BleConnectionEvent` | listener handle | Emitted on every BLE connect/disconnect. |
| `addListener('locationPermissionRequired', cb)` | `LocationPermissionRequiredEvent` | listener handle | Emitted when the first BLE device connects and location permission is still missing. |
| `removeAllListeners()` | — | `Promise<void>` | Drops all listeners. |

### 2.2 Configuration and data types

```ts
interface BtLocationConfig {
  debug?: boolean;                         // default false; enables file logging
  texts?: NotificationTexts;               // notification strings (Android channel + iOS UNUserNotifications)
  devices: BtDeviceEntry[];                // REQUIRED, non-empty
  reportEndpoint: string;                  // REQUIRED, HTTPS URL receiving the POST
  authToken?: string;                      // sent as `Authorization: Bearer <token>`
  reportIntervalMs?: number;               // default 30_000 (30 s)
  extraPayloadFields?: Record<string, unknown>; // merged into every POST body
}

interface BtDeviceEntry {
  bleDeviceId: string;                     // Android: MAC "AA:BB:CC:DD:EE:FF"; iOS: CBPeripheral UUID string
  pajDeviceId: string | number;            // ID that will be included in POST.devicesId[]
  onConnectCommand?: BleCommand;           // e.g. GPS_OFF (sent 3 s after connect)
  onDisconnectCommand?: BleCommand;        // e.g. GPS_ON  (sent 3 s after disconnect or on report failure)
}

interface BleCommand {
  name: string;                            // human-readable (logging only)
  serviceUuid: string;                     // GATT service UUID; also accepts snake_case `service_uuid`
  characteristicUuid: string;              // GATT characteristic UUID; also accepts `characteristic_uuid`
  value: string;                           // UTF-8 string; written as UTF-8 bytes
}

interface NotificationTexts {
  connectedHeader?: string;                // default "Device connected"
  connected?: string;                      // default "{device} connected via Bluetooth, power saving activated" ({device} is replaced by peripheral name or id)
  trackerHeader?: string;                  // default "BT Location Reporter"
  tracker?: string;                        // default "Tracking location in background…"
}

interface BtLocationPayload {
  devicesId: (string | number)[];          // pajDeviceIds of CURRENTLY CONNECTED devices
  lat: number;
  lng: number;
  accuracy: number;
  timestamp: number;                       // ms since epoch
  direction?: number;                      // Android: location.bearing (if available); iOS: location.course (if >= 0)
  [key: string]: unknown;                  // extraPayloadFields merged in
}

interface LocationReportEvent {
  payload: BtLocationPayload;
  httpStatus: number;                      // 0 = network/offline error
  success: boolean;                        // true iff 200..299
}

interface BleConnectionEvent { deviceId: string; connected: boolean; }
interface LocationPermissionRequiredEvent { reason: string; }
```

Defaults summary (applied natively when a field is omitted):

- `reportIntervalMs` → `30000` (Android) / `30000` (iOS — stored as `Double`)
- `debug` → `false`
- `authToken` → no `Authorization` header
- `texts` → defaults listed above
- `extraPayloadFields` → `{}`

---

## 3. Android implementation

### 3.1 AndroidManifest (`android/src/main/AndroidManifest.xml`)

Permissions declared:

- Bluetooth 12+: `BLUETOOTH_SCAN` (`usesPermissionFlags="neverForLocation"`),
  `BLUETOOTH_CONNECT`.
- Legacy Bluetooth (SDK ≤ 30): `BLUETOOTH`, `BLUETOOTH_ADMIN`.
- Location: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
  `ACCESS_BACKGROUND_LOCATION`.
- Foreground service: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- Network: `INTERNET`, `ACCESS_NETWORK_STATE`.
- Wake / boot: `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`.

Components registered:

- `<receiver .BleScanReceiver>` (exported) — target of BLE scan `PendingIntent`.
- `<service .BtLocationReporterService>` — `foregroundServiceType="location|connectedDevice"`, not exported.
- `<receiver .BootReceiver>` — listens for `android.intent.action.BOOT_COMPLETED`.

### 3.2 Plugin entry — `BtLocationReporterPlugin.kt`

Annotated `@CapacitorPlugin(name = "BtLocationReporter")`. **Permissions are
not declared in the annotation on purpose**, so Capacitor does not
auto-prompt at app start. The plugin saves a singleton reference in
`instance` (used by the service to emit events back to JS) and initializes
`FileLogger` in `load()`.

Event constants:

- `EVENT_LOCATION_REPORT = "locationReport"`
- `EVENT_BLE_CONNECTION = "bleConnection"`
- `EVENT_LOCATION_PERMISSION_REQUIRED = "locationPermissionRequired"`

| `@PluginMethod` | Reads from `PluginCall` | Behavior |
|---|---|---|
| `start` | `devices` (JSONArray, required), `reportEndpoint` (String, required), `authToken?`, `reportIntervalMs?`, `debug?`, `texts?`, `extraPayloadFields?` | 1. Persist raw config JSON via `ConfigStore.saveConfig(context, call.data.toString())`. 2. Persist the set of `bleDeviceId`s via `LinkedDeviceStore`. 3. Check Android-12+ BT perms → if missing, save call and request `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN`. 4. Check `ACCESS_FINE_LOCATION` → if missing, save call and request it. 5. Call `launchService(...)` (builds an `Intent(ACTION_START)` with all extras and calls `startForegroundService`/`startService`). 6. `call.resolve()`. On permission denial the saved call is rejected with `"Bluetooth permissions were not granted"` or `"Location permission was not granted"`. |
| `stop` | — | Sends `Intent(ACTION_STOP)` to the service, calls `ConfigStore.clearConfig` and `LinkedDeviceStore.clearLinkedDevices`. |
| `isRunning` | — | Resolves `{ running: BtLocationReporterService.isRunning }`. |
| `addDevices` | `devices` (JSONArray) | Parses entries, sets `BtLocationReporterService.pendingCommand = Command.AddDevices(entries, commands)` and appends the new ids to `LinkedDeviceStore`. The service picks up the command in its coroutine. |
| `removeDevices` | `devices` (JSONArray) — matched by `bleDeviceId` | Same pattern with `Command.RemoveDevices(bleIds)`; clears `LinkedDeviceStore` if list becomes empty. |
| `requestLocationPermission` | — | Returns early with `{granted: true}` if already granted (also calls `BtLocationReporterService.onLocationPermissionGranted()`); otherwise stores the call as `pendingLocationCall` and requests `ACCESS_FINE_LOCATION`. |
| `hasLocationPermission` | — | `ContextCompat.checkSelfPermission(ACCESS_FINE_LOCATION)`. |
| `getLogPath` / `getLogs` | — | Delegate to `FileLogger`. |
| `writeWithoutResponse` | `deviceId`, `service` (UUID), `characteristic` (UUID), `value` (JSONArray of ints, `& 0xFF`) | Forwards to `BtLocationReporterService.writeWithoutResponse(...)` which calls into `BleConnectionManager`. |

`handleRequestPermissionsResult` handles both request codes
(`BT_PERMISSION_REQUEST_CODE = 12346`, `LOCATION_PERMISSION_REQUEST_CODE = 12345`).
When BT perms are granted `start(savedCall)` is re-invoked (which then asks
for location). When location is granted it calls
`BtLocationReporterService.onLocationPermissionGranted()` so the running
service can resume location updates; any `pendingStartCall` is re-run and the
`pendingLocationCall` is resolved with `{ granted }`.

Event emission helpers (called from the service):

- `notifyLocationReport(payload: JSObject, httpStatus: Int, success: Boolean)`
- `notifyBleConnection(deviceId: String, connected: Boolean)`
- `notifyLocationPermissionRequired()` — emits `{ reason: "First BLE device connected - location permission needed to start tracking" }`.
- `requestLocationPermissionFromService()` — triggered by the service when
  the first BLE device connects without permission.

### 3.3 Foreground service — `BtLocationReporterService.kt`

Foreground notification:

- Channel `bt_location_reporter_channel` (IMPORTANCE_LOW), notification id `7411`.
- A separate channel `bt_ble_connection_channel` (default importance) is used
  for per-device "device connected" notifications (id base `8000` + low-16 of
  `deviceId.hashCode()`), subject to `POST_NOTIFICATIONS` permission on API 33+.

Intent entry points supported by `onStartCommand`:

1. `action == ACTION_STOP` → `shutdown()`.
2. Any intent with a `config_json` extra → `handleStartFromConfig(configJson)`
   (used by `BleScanReceiver` and `BootReceiver`).
3. `action == ACTION_START` → `handleStart(intent)` with the full set of
   extras populated by `launchService(...)`.
4. Otherwise, if `ConfigStore.getConfig(context)` returns a non-blank JSON
   string → `handleStartFromConfig(...)`; else `stopSelf()`.

On start (`handleStart`):

1. Parse notification titles/texts, `endpoint`, `authToken`, `intervalMs`,
   `extraJson`, `debug`.
2. `startCommandProcessor()` launches an IO coroutine that polls
   `pendingCommand` every 500 ms and applies `AddDevices` / `RemoveDevices`.
3. Instantiate `GpsSwitcher` and register each device's optional
   `onConnectCommand` / `onDisconnectCommand`.
4. `startForeground(NOTIF_ID, buildNotification(title, text))`.
5. Instantiate `BleConnectionManager` (see §3.4) and call `start()`.
6. `startBleScanWithPendingIntent()` — registers a `PendingIntent`-based
   `bluetoothLeScanner.startScan` with one `ScanFilter` per MAC address from
   `LinkedDeviceStore`, using `SCAN_MODE_LOW_POWER`.

If `handleStart` runs while `isRunning == true` the service updates
runtime config in place, diffs the target id set against the active
`BleConnectionManager`, and calls `addDevices` / `removeDevices` accordingly.

Location flow:

- `FusedLocationProviderClient` is lazily obtained in `startLocationUpdates()`.
- `LocationRequest` is built with `PRIORITY_HIGH_ACCURACY`, interval =
  `intervalMs`, `setMinUpdateDistanceMeters(10f)`.
- Updates are received on the main-thread `LocationCallback` and forwarded
  to `onNewLocation(location)`.
- `onNewLocation` throttles to `intervalMs` using `lastReportTime` and
  returns early if there are no connected BLE devices.
- Location updates are **paused** when the last BLE device disconnects and
  **resumed** when the first one connects again
  (`pauseLocationUpdates()` / `resumeLocationUpdates()`).

HTTP report (`postLocationReport`):

- Client: OkHttp with 15 s connect/read/write timeouts.
- Payload: `extraJson` object merged with `{ devicesId, lat, lng, accuracy, timestamp, direction? }`.
  `devicesId` contains the `pajDeviceId` values (strings) of currently
  connected BLE devices, looked up via `pajIdMap[bleId]`.
- Headers: `Content-Type: application/json`, optional `Authorization: Bearer $authToken`.
- Success path: `response.isSuccessful` → `notifyResult(body, response.code, true)`.
- Failure paths (IO failure **or** non-2xx response): call
  `gpsSwitcher.onLocationReportFailed(connectedIds)` (sends `onDisconnectCommand`
  / GPS_ON to every connected device) then `notifyResult(body, code-or-0, false)`.
- There is **no retry**; the report is fire-and-forget.

`notifyResult` converts the `JSONObject` body to a Capacitor `JSObject` and
calls `BtLocationReporterPlugin.instance?.notifyLocationReport(...)`.

BLE callbacks plumbed through `handleBleConnected` / `handleBleDisconnected`:

- `handleBleConnected`: informs `GpsSwitcher`, emits `bleConnection` to JS,
  shows BLE-connected notification, and — if this is the first connected
  device — resumes location updates (requesting `ACCESS_FINE_LOCATION` via
  the plugin if somehow not granted yet).
- `handleBleDisconnected`: informs `GpsSwitcher`, emits `bleConnection`
  (`connected=false`), and pauses location updates if no devices remain.
- `handleBluetoothOff`: pauses location updates.

`shutdown` stops coroutines, disconnects BLE, cancels the FusedLocation
callback, stops the PendingIntent scan, stops the foreground notification
and `stopSelf()`.

### 3.4 BLE connection manager — `BleConnectionManager.kt`

- Direct connection strategy: for every target id
  `adapter.getRemoteDevice(id).connectGatt(context, autoConnect=true, callback)`.
- Maintains `targetIds` (desired), `connectedIds` (live), `gattMap`.
- Reconnect: on `STATE_DISCONNECTED` the cached `BluetoothGatt` is closed
  and `scheduleReconnect` retries after `RECONNECT_DELAY_MS = 3_000`.
- Service discovery: automatically triggered on `STATE_CONNECTED`, and the
  `onServicesDiscovered` callback is forwarded to the service (which calls
  `GpsSwitcher.onServicesDiscovered`).
- `writeWithoutResponse(deviceId, serviceUuid, charUuid, data, callback)`:
  fast path when the characteristic is cached; otherwise queues a
  `PendingWrite` and triggers `discoverServices()` (the write is performed
  from `onServicesDiscovered`). Uses deprecated `characteristic.value =
  data; gatt.writeCharacteristic(...)` for compatibility < API 33.
- Registers a `BluetoothAdapter.ACTION_STATE_CHANGED` receiver so it can
  clear internal state when the radio turns off.

### 3.5 GPS switcher — `GpsSwitcher.kt`

- `registerDevice(deviceId, onConnect?, onDisconnect?)` stores per-device
  `onConnectCommand` (GPS_OFF) and `onDisconnectCommand` (GPS_ON).
- `onDeviceConnected` → posts the GPS_OFF write after a **3 s** delay
  (`commandDelayMs`). If the characteristic is cached it writes directly;
  otherwise stores the command in `pendingCommands` and calls
  `gatt.discoverServices()`; the write is done from `onServicesDiscovered`.
- `onDeviceDisconnected` → sends the GPS_ON command after 3 s (best-effort
  on an already-disconnected device).
- `onLocationReportFailed(connectedDeviceIds)` → sends GPS_ON immediately
  to every currently connected device.
- `writeToCharacteristic` picks `WRITE_TYPE_NO_RESPONSE` when the
  characteristic supports it, otherwise `WRITE_TYPE_DEFAULT`. The value is
  `command.value.toByteArray(Charsets.UTF_8)`.

### 3.6 Broadcast receivers

- `BleScanReceiver.kt` is the target of the `PendingIntent` used in
  `startBleScanWithPendingIntent()`. On receive it reads
  `ConfigStore.getConfig(context)` and iterates over the scan results in
  `android.bluetooth.le.extra.LIST_SCAN_RESULT` (handled as both `Array` and
  `ArrayList`). If a matched MAC is present and the service is not already
  running, it starts it with the persisted `config_json` extra.
- `BootReceiver.kt` listens for `ACTION_BOOT_COMPLETED`. If a persisted
  config exists, it starts the foreground service with `config_json`.

### 3.7 Persistence

- `ConfigStore` — SharedPreferences `bt_location_reporter`, key `config_json`,
  stores the **raw JSON** of the last `start()` options object (from
  `call.data.toString()`).
- `LinkedDeviceStore` — SharedPreferences `bt_location_reporter`, key
  `linked_device_ids`, stores a `Set<String>` of `bleDeviceId`s for the BLE
  PendingIntent scan filters.
- `FileLogger` — writes dual logs (logcat + file). Path returned by
  `getLogPath()`; `debug` flag toggles verbose output.

---

## 4. iOS implementation

### 4.1 Plugin entry — `ios/Plugin/BtLocationReporterPlugin.swift`

`@objc(BtLocationReporterPlugin) public class BtLocationReporterPlugin : CAPPlugin, CAPBridgedPlugin`
with `jsName = "BtLocationReporter"` and a `pluginMethods` table listing all
10 exposed methods (`start`, `stop`, `isRunning`, `addDevices`,
`removeDevices`, `getLogPath`, `getLogs`, `requestLocationPermission`,
`hasLocationPermission`, `writeWithoutResponse`).

A singleton `BtLocationReporter` coordinator lives in `self.coordinator`.
Configuration persistence uses **UserDefaults** at key
`BtLocationReporterPlugin.config`, encoded as JSON via the private
`ConfigCodable` / `DeviceCodable` / `NotificationTextsCodable` wrappers.

`load()` is overridden so that on every app launch the plugin checks:

- `UserDefaults.standard.bool(forKey: "BtLocationReporterPlugin.pendingRestore")`
  is `true`, **and**
- `UIApplication.shared.applicationState != .active` (i.e. the app was
  relaunched in background by iOS, not opened by the user).

If both hold, the stored config is loaded and the coordinator is started
automatically (auto-restore). The flag is cleared afterwards. On a normal
foreground open, the restore is **skipped** — the JS layer must call
`start()` explicitly. This prevents an unwanted BT permission prompt at every
launch.

Plugin methods (parameter parsing → coordinator call):

| `@objc func` | Params (from `CAPPluginCall`) | Behavior |
|---|---|---|
| `start` | `devices: [[String: Any]]` (non-empty, each with `bleDeviceId`, `pajDeviceId`, optional `onConnectCommand`, `onDisconnectCommand`), `reportEndpoint: String`, `authToken?`, `reportIntervalMs? (default 30_000 as Double)`, `debug?`, `texts?`, `extraPayloadFields?` | Builds a `BtLocationConfig`, saves it to UserDefaults, then `coordinator.start(config: ...)` on the main actor. Rejects on validation/coordinator errors. |
| `stop` | — | `coordinator.stop()`, releases coordinator, removes `pendingRestore` flag. |
| `isRunning` | — | `{ running: coordinator?.isRunning ?? false }`. |
| `addDevices` | `devices: [[String: Any]]` | `coordinator.addDevices(entries)`, then re-saves the expanded config. |
| `removeDevices` | `devices: [[String: Any]]` | `coordinator.removeDevices(entries)`, then re-saves the trimmed config. |
| `requestLocationPermission` | — | If coordinator exists, `coordinator.requestLocationPermission { granted in ... }`; else just returns current `CLLocationManager.authorizationStatus` as `granted`. |
| `hasLocationPermission` | — | `status == .authorizedAlways || .authorizedWhenInUse`. |
| `writeWithoutResponse` | `deviceId`, `service`, `characteristic`, `value: [Int]` | Builds `CBUUID`s and `Data(rawValue.map { UInt8(clamping: $0) })`, then `coordinator.writeWithoutResponse(...)`. Rejects with `"Service not started — call start() first"` when no coordinator is active. |
| `getLogPath` / `getLogs` | — | Delegate to `FileLogger.shared`. |

Event emission helpers:

- `emitLocationReport(payload, httpStatus, success)`
- `emitBleConnection(deviceId, connected)`
- `emitLocationPermissionRequired()` — emits
  `{ reason: "First BLE device connected - location permission needed to start tracking" }`.

`parseCommand(_:)` accepts both `[String: Any]` and `NSDictionary`, supports
both `serviceUuid/characteristicUuid` and `service_uuid/characteristic_uuid`
keys, and falls back to Mirror-based reflection if Capacitor passes a
`JSObject`.

### 4.2 Coordinator — `ios/Plugin/BtLocationReporter.swift`

Defines `BtLocationConfig`, `BtDeviceEntry`, `BleCommand` and
`NotificationTexts` (Swift mirror of the TS types; `extraFields` is
`[String: Any]`, `intervalMs` is `Double` ms).

`@MainActor class BtLocationReporter : NSObject` holds the `BleManager`,
`LocationReporter` and `GpsSwitcher` instances plus a `dynamicPajIdMap`
(for devices added later via `addDevices`).

`start(config:completion:)` flow:

1. Enable file logging per `config.debug`.
2. Show a local `UNUserNotification` titled with `texts.trackerHeader` /
   `texts.tracker` (best-effort, subject to notification permission).
3. Create `LocationReporter`, set `onLocationUpdate` to call
   `onNewLocation(_:)`, and configure its report interval.
4. Create `GpsSwitcher` and register each device's connect/disconnect
   commands.
5. Create `BleManager(deviceIds: config.bleDeviceIds, ...)` and call
   `start()`.
6. `completion(nil)`. Location tracking stays paused until the first BLE
   peripheral connects and location permission is granted.

`stop()` → tears everything down; BLE peripherals are disconnected; location
updates stopped.

`addDevices(_:)` / `removeDevices(_:)` mutate `BleManager`'s target list,
update `dynamicPajIdMap` and add/remove the device in `GpsSwitcher`.

`writeWithoutResponse(deviceId:serviceUUID:characteristicUUID:data:completion:)`
routes straight to `bleManager.writeWithoutResponse(...)` and rejects with
`"BLE not started"` if called before `start()`.

BLE callbacks:

- `handleBleConnected(_:peripheral:)` — invoked for every connect. It always
  treats the event as "first device" (`wasEmpty = true`; Android uses a more
  precise check but this is intentional on iOS because `resume()` is
  idempotent when already tracking). Triggers `GpsSwitcher.onDeviceConnected`,
  emits `bleConnection`, shows a local notification. If `locationMgr
  .hasPermission` is true → `locationMgr.resume()`. Else, if not yet asked,
  emits `locationPermissionRequired` **and** auto-calls
  `locationMgr.requestAlwaysPermission { granted in ... }`, resuming location
  updates on grant.
- `handleBleDisconnected(_:peripheral:)` — `GpsSwitcher.onDeviceDisconnected`,
  emits `bleConnection(connected:false)`. The code to pause location is
  currently commented out, so iOS keeps receiving updates even when no
  device is connected (Android pauses in this case — platform divergence).
- `handleBluetoothOff()` — pause-location code is commented out.

`sendReport(config:location:connectedIds:)`:

- Merges `config.pajIdMap` with `dynamicPajIdMap` (new values win).
- Builds `body = config.extraFields` then sets `devicesId`, `lat`, `lng`,
  `accuracy` (from `location.horizontalAccuracy`), `timestamp` in ms,
  optional `direction` from `location.course` when `course >= 0`.
- Uses `URLSession.shared.dataTask` with method `POST`, headers
  `Content-Type: application/json` and `Authorization: Bearer <token>` if a
  token is configured.
- `success = error == nil && (200..<300).contains(httpStatus)`. On failure
  calls `gpsSwitcher?.onLocationReportFailed(connectedDeviceIds:)`. In both
  cases emits a `locationReport` event.

Notifications:

- `showMonitoringStartedNotification()` — `texts.trackerHeader / texts.tracker`
  at start.
- `showBleConnectionNotification(deviceId:deviceName:)` — per-connect.
- `showBleNearbyNotification(...)` — reserved for "device nearby but not
  connected" (currently unused by the coordinator).

### 4.3 BLE manager — `ios/Plugin/BleManager.swift`

- `CBCentralManager` initialized with
  `CBCentralManagerOptionRestoreIdentifierKey:
  "com.paj.btlocationreporter.central"` and dispatch queue `.global(qos:
  .background)`. **This enables iOS state restoration**: if iOS kills the
  app, it will relaunch it in the background when a known peripheral comes
  in range and deliver its state via
  `centralManager(_:willRestoreState:)`. Inside that callback the plugin
  sets `UserDefaults.standard.set(true, forKey: "BtLocationReporterPlugin.pendingRestore")`
  so the next `load()` call can auto-restart the coordinator.
- `start()` (when state is `.poweredOn`) calls `connectAllKnown()`:
  `retrievePeripherals(withIdentifiers:)` → `connect(peripheral)` for each.
  Any UUIDs not found are searched for via `scanForPeripherals(withServices:
  nil, options: nil)` with a 10 s timeout (`DispatchQueue.global().asyncAfter`).
- Reconnect: on `didDisconnectPeripheral` → `scheduleReconnect` with 3 s
  delay, as long as the UUID is still in `targetUUIDs`.
- `addDevices` / `removeDevices` mutate `targetUUIDs` and trigger connects
  or `cancelPeripheralConnection`.
- `writeWithoutResponse` spawns a retained `BleWriteSession` (a private
  `CBPeripheralDelegate` that installs itself as the peripheral's delegate,
  performs on-the-fly service + characteristic discovery if needed, writes
  with `.withoutResponse` and restores the previous delegate). The session is
  retained in `activeSessions` until it completes (CBPeripheral only holds a
  weak delegate reference).
- `centralManagerDidUpdateState`: on `.poweredOff` clears `connectedIds` and
  invokes `onBluetoothOff`; on `.poweredOn` re-runs `connectAllKnown()`.
- `didDiscover peripheral:`: if the UUID is in `targetUUIDs` and not yet in
  `peripheralMap`, stops the scan, stores the peripheral and connects.

### 4.4 Location reporter — `ios/Plugin/LocationReporter.swift`

- `CLLocationManager` configured with `desiredAccuracy =
  kCLLocationAccuracyBest`, `distanceFilter = 10` m,
  `pausesLocationUpdatesAutomatically = false`, `activityType =
  .otherNavigation`.
- `startLocationUpdates()` sets `allowsBackgroundLocationUpdates = true`,
  `showsBackgroundLocationIndicator = false`, then
  `startUpdatingLocation()`.
- `requestAlwaysPermission(completion:)`: resolves immediately when already
  `.authorizedAlways`; for `.authorizedWhenInUse` or `.notDetermined` stores
  the callback in `permissionCompletion` and calls
  `requestAlwaysAuthorization()`. In
  `locationManager(_:didChangeAuthorization:)` it resolves `true` on
  `.authorizedAlways` (location updates are still **not** started — tied to
  the first BLE connect) and `false` on `.denied/.restricted`.
- `didUpdateLocations` throttles with `minReportInterval` (ms → s) and calls
  `onLocationUpdate(location)` for every in-range update.
- `pause()` / `resume()` wrap `stopUpdatingLocation` / `startLocationUpdates`.
- `enableLowPowerMode()` exists but is currently unused (commented call
  sites). It switches to `kCLLocationAccuracyHundredMeters`, 500 m filter,
  automatic pauses, and `startMonitoringSignificantLocationChanges()`.

### 4.5 GPS switcher — `ios/Plugin/GpsSwitcher.swift`

Mirror of the Android implementation but driven by
`CBPeripheral.writeValue(_:for:type:)`; same 3 s `commandDelayMs` semantics,
same trio of hooks (`onDeviceConnected`, `onDeviceDisconnected`,
`onLocationReportFailed`), same characteristic-caching logic.

### 4.6 File logger — `ios/Plugin/FileLogger.swift`

Writes NSLog + appends to a file under the app's Documents directory (path
returned by `getLogPath()`); verbose only when `debugEnabled` is true.

### 4.7 Required host-app Info.plist entries

The plugin does not bundle an Info.plist. Consumers **must** add to their
app's `Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
  <string>bluetooth-central</string>
  <string>location</string>
</array>
<key>NSBluetoothAlwaysUsageDescription</key>
<string>...</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>...</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>...</string>
```

Optionally `NSUserNotificationsUsageDescription` if the app wants the BLE
connected / monitoring started notifications to appear.

---

## 5. End-to-end runtime flow

### 5.1 `start()` — cold path

1. **JS** calls `BtLocationReporter.start({ devices, reportEndpoint, ... })`.
2. **Android:**
   a. Plugin validates inputs and persists the full options JSON into
      `ConfigStore` + the `bleDeviceId` set into `LinkedDeviceStore`.
   b. If Android 12+ BT perms are missing, the runtime dialog is shown;
      `start()` re-runs on grant, rejects on deny.
   c. If `ACCESS_FINE_LOCATION` is missing, its dialog is shown;
      `start()` re-runs on grant, rejects on deny.
   d. Plugin sends `Intent(ACTION_START, extras...)` → `startForegroundService(...)`.
   e. `BtLocationReporterService.handleStart(intent)` sets `isRunning = true`,
      starts the command processor, builds `GpsSwitcher`, calls
      `startForeground(NOTIF_ID, notification)`, instantiates
      `BleConnectionManager.start()` and begins a `PendingIntent`-based
      BLE scan with `ScanFilter`s for the persisted MACs.
   f. `call.resolve()` returns to JS.
3. **iOS:**
   a. Plugin validates inputs and persists the config into
      UserDefaults (`BtLocationReporterPlugin.config`).
   b. On main actor: creates `BtLocationReporter` coordinator, calls
      `start(config:)`. The coordinator shows a "monitoring started"
      notification (if authorized), builds `LocationReporter` (paused),
      `GpsSwitcher` (with commands), and `BleManager` (which starts
      connecting to known peripherals, scanning briefly for unknown ones).
      Location permission is **not** requested yet.
   c. `call.resolve()` returns to JS.

### 5.2 BLE connection → location report

1. Native BLE stack reports a peripheral connection.
2. Android: `BleConnectionManager.onConnectionStateChange(STATE_CONNECTED)`
   → `gatt.discoverServices()` + `onConnected(deviceId, gatt)`.
   iOS: `centralManager(_:didConnect:)` → `onConnected(id, peripheral)`.
3. Coordinator (`handleBleConnected`):
   - `GpsSwitcher.onDeviceConnected(...)` schedules the `onConnectCommand`
     (e.g. GPS_OFF) for 3 s later. If the characteristic isn't cached, GATT
     service discovery is triggered; the command runs from
     `onServicesDiscovered`.
   - Emits `bleConnection { deviceId, connected: true }` to JS.
   - Shows a local BLE-connected notification (subject to permission).
   - If this is the first BLE device and location permission is already
     granted → resumes location updates. Otherwise emits
     `locationPermissionRequired` and auto-requests
     `ACCESS_FINE_LOCATION` (Android via `requestLocationPermissionFromService()`)
     / `requestAlwaysAuthorization` (iOS).
4. Location provider delivers a `CLLocation` / fused `Location`.
5. `onNewLocation` / `onLocationUpdate` throttles to `reportIntervalMs`,
   skips if no devices are currently connected, then builds the payload
   `{ devicesId: [pajIds], lat, lng, accuracy, timestamp, direction?, ...extra }`
   and POSTs it.
6. Native emits `locationReport { payload, httpStatus, success }`.
7. On HTTP failure: `GpsSwitcher.onLocationReportFailed(connectedIds)` sends
   the `onDisconnectCommand` (e.g. GPS_ON) to every connected device, so the
   peripheral resumes its own location reporting. No retry is attempted.

### 5.3 Disconnection

- `bleConnection { connected: false }` is emitted.
- `GpsSwitcher.onDeviceDisconnected` tries to send the `onDisconnectCommand`
  (best-effort, may race with a real disconnect).
- Android pauses location updates when the last device disconnects. iOS
  currently leaves location updates running (the pause call sites are
  commented out).

### 5.4 Background survival

- **Android:** the foreground service keeps the process alive. If the
  process is killed anyway, a `PendingIntent`-based BLE scan filter matches
  the persisted MAC addresses and `BleScanReceiver` restarts the service
  with the persisted config. After reboot, `BootReceiver` does the same.
- **iOS:** `CBCentralManager` state restoration relaunches the app in the
  background when a persisted peripheral is discovered. The plugin's
  `centralManager(_:willRestoreState:)` sets the `pendingRestore` flag;
  on the next `load()` (which runs during the background launch), the
  coordinator is re-started with the config stored in UserDefaults.
  **If the user opens the app in the foreground, auto-restore is skipped**
  — JS must call `start()` again.

### 5.5 `stop()`

- Android: `Intent(ACTION_STOP)` → `shutdown()` → stops the BLE scan, BLE
  manager, FusedLocation callback, foreground notification, `stopSelf()`.
  `ConfigStore` and `LinkedDeviceStore` are cleared.
- iOS: `coordinator.stop()` disconnects peripherals, stops location updates,
  cleans up `GpsSwitcher`, releases the coordinator. Both the stored config
  **and** the `pendingRestore` flag are removed from UserDefaults.

---

## 6. Configuration, setup & build

### 6.1 Install for consumer apps

```bash
npm install @tovaz/capacitor-bt-location-reporter
npx cap sync
```

No `capacitor.config.json` options are read by the plugin — **all**
configuration is passed at runtime to `start()`.

Android: the plugin ships its own `AndroidManifest.xml` fragment that
declares all required permissions and the service/receivers. The consuming
app only has to ensure any runtime permission UX is acceptable (Capacitor's
default permission prompts are suppressed by design; they are requested on
demand by `start()` / `requestLocationPermission()`).

iOS: consumers must add the Info.plist entries listed in §4.7.

### 6.2 Build & tooling

```bash
npm run clean   # rimraf ./dist
npm run build   # clean + tsc + rollup -c
npm run lint    # eslint . --ext ts
npm run fmt     # prettier --write src/**/*.ts
```

Rollup (`rollup.config.js`) emits `dist/plugin.js` (IIFE) and
`dist/plugin.cjs.js` (CJS), TypeScript emits ESM to `dist/esm/` with type
declarations. `package.json` exposes `main`, `module`, `types` and `unpkg`
accordingly.

No automated tests are present — the project is validated manually on
device.

### 6.3 Example usage (TypeScript)

```ts
import { BtLocationReporter } from '@tovaz/capacitor-bt-location-reporter';

await BtLocationReporter.start({
  debug: true,
  reportEndpoint: 'https://api.example.com/v1/bt-location',
  authToken: 'Bearer eyJhbGciOi...',   // optional; "Bearer " is added natively, so just pass the token
  reportIntervalMs: 30_000,
  texts: {
    connectedHeader: 'Device connected',
    connected: '{device} connected — power saving on',
    trackerHeader: 'MyApp',
    tracker: 'Tracking location…',
  },
  devices: [
    {
      bleDeviceId: 'AA:BB:CC:DD:EE:FF',       // Android MAC or iOS UUID
      pajDeviceId: 42,
      onConnectCommand:    { name: 'GPS_OFF', serviceUuid: '...', characteristicUuid: '...', value: 'OFF' },
      onDisconnectCommand: { name: 'GPS_ON',  serviceUuid: '...', characteristicUuid: '...', value: 'ON'  },
    },
  ],
  extraPayloadFields: { appVersion: '1.2.3' },
});

await BtLocationReporter.addListener('locationPermissionRequired', async () => {
  await BtLocationReporter.requestLocationPermission();
});
await BtLocationReporter.addListener('bleConnection', (e) => console.log(e));
await BtLocationReporter.addListener('locationReport', (e) => console.log(e));
```

> Note on `authToken`: the native layer adds the `Bearer ` prefix itself.
> Passing a bare token (`"eyJ..."`) is the expected shape. Passing a string
> that already starts with `"Bearer "` will produce `Bearer Bearer eyJ...`.

---

## 7. Persistence keys and file locations (quick map)

| Platform | Key / path | Purpose |
|---|---|---|
| Android | `SharedPreferences("bt_location_reporter")` → `config_json` | Raw `start()` options JSON (survives process death, reboot). |
| Android | `SharedPreferences("bt_location_reporter")` → `linked_device_ids` (`Set<String>`) | MAC filters for the BLE `PendingIntent` scan. |
| Android | Notification channel `bt_location_reporter_channel` / id `7411` | Foreground service. |
| Android | Notification channel `bt_ble_connection_channel` / id `8000 + hash` | Per-device "connected" notification. |
| Android | Log file via `FileLogger.getLogPath()` (app files dir) | Returned by `getLogPath`. |
| iOS | `UserDefaults` → `BtLocationReporterPlugin.config` | Serialized `BtLocationConfig` (without `onConnectCommand`/`onDisconnectCommand`). |
| iOS | `UserDefaults` → `BtLocationReporterPlugin.pendingRestore` (Bool) | Set by `centralManager(_:willRestoreState:)`; consumed by `load()` to auto-restart on background launch. |
| iOS | `CBCentralManagerOptionRestoreIdentifierKey` = `"com.paj.btlocationreporter.central"` | iOS state restoration key. |
| iOS | `FileLogger.shared.getLogFilePath()` (Documents dir) | Returned by `getLogPath`. |

---

## 8. Edge cases, quirks & gotchas

- **Platform divergence in `bleDeviceId`.** Android expects a MAC address
  (`"AA:BB:..."`); iOS expects a CBPeripheral UUID string
  (`"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"`). The JS layer treats both as
  opaque strings; the caller must know which is which.
- **`BleCommand` accepts camelCase and snake_case UUID keys.** Both Android
  and iOS accept `serviceUuid` / `service_uuid` and `characteristicUuid` /
  `characteristic_uuid` for compatibility with backends that serialize keys
  differently.
- **iOS persisted config drops GATT commands.** The Codable `DeviceCodable`
  only stores `bleDeviceId` + `pajDeviceId`. After an auto-restore (BLE
  background launch), `onConnectCommand` / `onDisconnectCommand` are `nil`
  until the JS layer calls `start()` again. Android persists the full raw
  JSON and retains them across reboots and receiver-triggered launches.
- **Android requires location permission before `start()` can launch the
  foreground service** (Android 14+ enforces this for
  `foregroundServiceType=location`). iOS is more lenient: the session runs,
  location updates just stay paused until permission is granted.
- **Auto-requested location permission on first BLE connect.** On both
  platforms, if location permission is missing when the first BLE device
  connects, the coordinator emits `locationPermissionRequired` and — iOS
  additionally auto-invokes `requestAlwaysAuthorization`; Android triggers
  its `ACCESS_FINE_LOCATION` prompt through the plugin.
- **No retry on HTTP failure.** On IO error **or** non-2xx response, the
  plugin fires `GpsSwitcher.onLocationReportFailed(...)` (which sends
  `onDisconnectCommand` / GPS_ON to every connected device so the peripheral
  resumes its own tracking) and emits `locationReport { success: false }`.
  The payload is not queued or resent.
- **Throttling.** Both platforms throttle HTTP posts to `reportIntervalMs`
  even though the location provider may emit more frequent updates.
- **`writeWithoutResponse` requires a running session.** Calling it before
  `start()` (iOS) or before the service is alive (Android) rejects with
  `"Service not started — call start() first"` / `"BLE not started"`.
- **Android BLE background scanning uses PendingIntent.** If the process
  dies, the OS still delivers scan matches to `BleScanReceiver`, which
  restarts the foreground service with the persisted `config_json`.
- **iOS `pausesLocationUpdatesAutomatically = false`** to keep updates
  flowing in background. A "low power mode" (significant-change monitoring)
  is implemented but intentionally unused at the moment — the call sites
  are commented out. If enabling it in a future change, remember to also
  restore the call sites in `handleBleDisconnected` and `handleBluetoothOff`
  and re-verify the "first BLE device connects → resume" path.
- **Android `handleBleConnected` is defensive.** Even though `start()`
  validates permission, the service has a fallback that re-requests
  `ACCESS_FINE_LOCATION` if the first BLE device connects without it. This
  is mostly a safety net for users who revoked permission between `start()`
  and the first connect.
- **iOS `handleBleConnected` always treats the new device as the first
  one (`wasEmpty = true`).** This is intentional: `locationMgr.resume()` is
  idempotent and the permission request only runs if
  `locationPermissionRequested == false`, so re-entry is safe.
- **Android version drift:** `package.json` ships `0.3.3` while the iOS
  podspec still reports `0.1.0`. The JS-facing plugin semver is the npm
  package version; the podspec only affects CocoaPods publishing.
- **`BtLocationReporterPlugin.instance` is a mutable static on Android.**
  The service uses it to call back into the plugin to emit listeners. It is
  set in `load()` and consumed from multiple threads; treat it as
  read-mostly and be careful about lifecycle when writing new code.
- **Event payload typings on failure.** On HTTP failure the Android path
  emits `httpStatus: 0` (IO error) or the actual non-2xx code;
  iOS emits the actual status code or `0` when there was no
  `HTTPURLResponse`. Clients should branch on `success`, not `httpStatus`.
- **Listener hygiene.** Because the plugin caches its singleton reference
  inside the Android side and listeners are tied to the bridge instance,
  call `removeAllListeners()` on app logout to avoid stale closures.

---

## Quick reference: files you'll touch most

- TS surface: `src/definitions.ts`
- Android entry: `android/src/main/java/com/paj/btlocationreporter/BtLocationReporterPlugin.kt`
- Android service: `android/src/main/java/com/paj/btlocationreporter/BtLocationReporterService.kt`
- Android manifest: `android/src/main/AndroidManifest.xml`
- iOS entry: `ios/Plugin/BtLocationReporterPlugin.swift`
- iOS coordinator: `ios/Plugin/BtLocationReporter.swift`
- iOS BLE: `ios/Plugin/BleManager.swift`
- iOS location: `ios/Plugin/LocationReporter.swift`
- GATT commands: `android/.../GpsSwitcher.kt` and `ios/Plugin/GpsSwitcher.swift`
