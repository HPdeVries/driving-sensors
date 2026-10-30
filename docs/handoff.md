# Handoff: Driving Telemetry Capacitor plugin for ODC

Goal: a first-version PoC that collects **speed, acceleration peaks and position while the app is in the background**, buffers them locally on the device, and exposes them to an OutSystems Developer Cloud (ODC) mobile app for later sync. Use case: driving-behaviour data for insurance discounts on older cars (modern cars expose this via APIs). Native upload to the cloud is explicitly **out of scope** for now.

Status legend: **[doc]** = confirmed in ODC/Capacitor docs, **[mine]** = my design suggestion or general knowledge, **[verify]** = not confirmed, test first.

---

## 1. Findings that drive the design

- The official ODC Location plugin (Cordova/Capacitor dual-stack) is foreground-only. The Capacitor repo states it does not support background geolocation directly. **[doc]**
- `@capacitor/motion` is Web-API based (DeviceMotion), so it runs in the WebView and does not deliver in the background. **[doc]**
- No ready-made plugin exposes background speed and acceleration. Background location plugins exist (capacitor-community/background-geolocation, Capgo, Capawesome (paid), Transistorsoft (licensed)) but none covers motion peaks for this use case. **[doc]**
- ODC client logic (client actions, REST, offline sync) is JavaScript in the WebView and is suspended or throttled in the background. Android throttles WebView HTTP after about 5 minutes in the background (documented by the community background-geolocation plugin). **[doc]**
- Therefore: **native code detects, buffers and persists; the ODC app polls** on resume or on a timer and moves events into a Local Storage entity for the normal offline sync. **[mine]**
- ODC Local Storage entities live in the WebView storage layer, so native code should not write to them. **[mine, verify]**

## 2. Architecture

```
ODC app (screens, Local Storage entity, offline sync)
  -> ODC mobile library "DrivingTelemetry" (client actions with JS nodes)
       -> window.CapacitorPlugins.DrivingTelemetry   (Capacitor bridge)
            |- Android: foreground service, FusedLocation, linear-acceleration sensor, native buffer
            `- iOS: CLLocationManager (background mode), CMMotionManager, native buffer
```

Flow: `startTracking` -> native detects trips and events and appends to a native SQLite/file buffer -> app calls `getBufferedEvents` on resume or timer -> inserts into Local Storage entity -> `ackEvents` -> ODC offline sync uploads later.

Store: 1 Hz location samples plus detected events (harsh brake/accel, trip start/end). **Not** raw 50 Hz accelerometer samples.

## 3. Plugin API (`src/definitions.ts`)

```ts
export interface DrivingTelemetryPlugin {
  checkPermissions(): Promise<PermissionStatus>;
  requestPermissions(o?: { permissions?: ('location' | 'backgroundLocation' | 'motion' | 'notifications')[] }): Promise<PermissionStatus>;
  startTracking(o: TrackingOptions): Promise<{ sessionId: string }>;
  stopTracking(): Promise<void>;
  getStatus(): Promise<{ active: boolean; sessionId?: string; buffered: number }>;
  getBufferedEvents(o?: { afterId?: number; limit?: number }): Promise<{ events: TelemetryEvent[] }>;
  ackEvents(o: { upToId: number }): Promise<void>;
}

export interface TrackingOptions {
  harshBrakeMps2: number;     // threshold, positive number, braking
  harshAccelMps2: number;     // threshold, acceleration
  locationIntervalMs: number; // e.g. 1000
  accelSampleHz: number;      // e.g. 50
}

export interface TelemetryEvent {
  id: number;                 // monotonically increasing, native-assigned
  ts: number;                 // epoch ms, native timestamp
  type: 'LOCATION' | 'HARSH_BRAKE' | 'HARSH_ACCEL' | 'TRIP_START' | 'TRIP_END';
  lat?: number; lon?: number; speedMps?: number;
  peakMps2?: number;          // for harsh events
}
```

Optional later: `addListener('event', ...)` for live foreground updates. **[mine]**

## 4. Native specifics

### Android (Kotlin)
- Plugin class extends `com.getcapacitor.Plugin` with `@CapacitorPlugin`; methods use `@PluginMethod` and `PluginCall`. Permissions via `@Permission` aliases, requested with `requestPermissionForAlias(es)` plus a `@PermissionCallback`. **[doc]**
- Foreground service with a persistent notification. Declare it in the **plugin's own `AndroidManifest.xml`** with `foregroundServiceType="location"`. Install-time permissions go in the plugin manifest; runtime permissions are documented for the app. **[doc for manifest split; verify the library manifest merges in the ODC/MABS build]**
- Location and speed: `FusedLocationProviderClient`. Acceleration: `Sensor.TYPE_LINEAR_ACCELERATION` (gravity removed). Android 12+: sampling above 200 Hz needs `HIGH_SAMPLING_RATE_SENSORS`; 50 Hz needs no extra permission. **[mine]**
- Permissions: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` (two-step runtime flow), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS` (Android 13+). Google Play requires a background-location declaration. **[doc for Play requirement via community plugin docs; rest mine]**
- Test on multiple OEMs (Samsung, Xiaomi, etc.) for background killing and battery optimisation. **[mine]**

### iOS (Swift)
- Two classes: implementation (`NSObject`) and plugin class extending `CAPPlugin, CAPBridgedPlugin` with `identifier`, `jsName`, `pluginMethods`. Methods are `@objc` and take `CAPPluginCall`. Override `checkPermissions` / `requestPermissions`. **[doc]**
- All plugins must ship a `Package.swift` (SPM) next to the podspec; the Capacitor plugin generator scaffolds it. Add `ios/Sources/<PluginName>/PrivacyInfo.xcprivacy` if required-reason APIs are used (bundle via `resource_bundles` in the podspec and `resources` in `Package.swift`). **[doc]**
- `CLLocationManager`: `allowsBackgroundLocationUpdates = true`, `pausesLocationUpdatesAutomatically = false`, `showsBackgroundLocationIndicator = true`. Speed from `CLLocation.speed`. **[mine]**
- Acceleration: `CMMotionManager.startDeviceMotionUpdates`, use `userAcceleration` (gravity removed) and `gravity`. **[mine]**
- Required: `UIBackgroundModes` = `location`; `NSLocationWhenInUseUsageDescription`, `NSLocationAlwaysAndWhenInUseUsageDescription`, `NSMotionUsageDescription`. **[doc for location keys; motion key mine]**
- **[verify]** CoreMotion updates keep running while the location background mode keeps the app alive. Test early on a real device.

### Detection logic (PoC)
- Horizontal acceleration magnitude, orientation-independent: remove the gravity-axis component from the linear acceleration vector. **[mine]**
- Sign (acceleration vs braking) from the GPS speed delta. No full phone-to-car calibration in the PoC. **[mine]**
- Cross-check against GPS-derived acceleration (speed delta at 1 Hz) and, if available, a reference such as the existing provider's data or an OBD logger. **[mine]**
- Trip start/stop: speed threshold plus idle timeout, to save battery. **[mine]**

## 5. ODC wiring

All of this requires **MABS 12 or higher** with the Capacitor framework selected. **[doc]**

### Library extensibility configuration (mobile library "DrivingTelemetry")

```json
{
  "buildConfigurations": {
    "capacitor": { "source": { "npm": "@your-org/driving-telemetry@0.1.0" } },
    "buildAction": { "config": "$resources.buildAction.json" }
  },
  "pluginConfigurations": {
    "permissions": {
      "ios": {
        "NSLocationWhenInUseUsageDescription": { "description": "$extensibilitySettings.LocationWhenInUse" },
        "NSLocationAlwaysAndWhenInUseUsageDescription": { "description": "$extensibilitySettings.LocationAlways" },
        "NSMotionUsageDescription": { "description": "$extensibilitySettings.MotionUsage" }
      },
      "android": [
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.POST_NOTIFICATIONS"
      ]
    }
  }
}
```

- Plugin source can also be a tagged GitHub URL: `"npm": "https://github.com/<org>/<repo>#<tag>"`. **[doc]** **[verify]** whether MABS can pull from a private repo or registry; plan on npm publishing.
- Values set at app level take precedence over library permission values. **[doc]**

### `buildAction.json` (upload as a resource, Deploy Action = *Deploy to Target Directory*)

```json
{
  "platforms": {
    "ios": {
      "plist": [
        { "replace": false, "entries": [ { "UIBackgroundModes": ["location"] } ] }
      ]
    }
  }
}
```

**[verify]** that array entries merge as intended. If the plugin manifest does not carry the Android service declaration, a fallback is an Android `manifest` build action with `inject` into `manifest/application`. Build actions apply to Capacitor apps only and run plugin-level before app-level. **[doc]**

### Client actions in the library
Each wraps one plugin method in a JavaScript node (pattern from the ODC docs):

```js
async function run() {
  try {
    const r = await window.CapacitorPlugins.DrivingTelemetry.getBufferedEvents({ afterId: $parameters.AfterId, limit: 500 });
    $parameters.EventsJson = JSON.stringify(r.events);
    $parameters.Success = true;
  } catch (e) {
    $parameters.ErrorCode = e.code; $parameters.ErrorMessage = e.message; $parameters.Success = false;
  }
  $resolve();
}
if (typeof window.CapacitorPlugins !== 'undefined' && window.CapacitorPlugins.DrivingTelemetry) { run(); }
```

Actions: `CheckPlugin`, `CheckPermissions`, `RequestPermissions`, `StartTracking`, `StopTracking`, `GetStatus`, `GetBufferedEvents`, `AckEvents`. For complex returns, serialize to a JSON string in the JS node and use a **JSON Deserialize** node into a structure. **[doc]**

### App side
On app resume and on a timer: `GetBufferedEvents` -> insert into a Local Storage entity -> `AckEvents` -> standard offline sync. The ODC offline-sync pages were not read yet. **[verify]**

## 6. Build and test workflow

1. Scaffold with the Capacitor plugin generator (`npm init @capacitor/plugin@latest`), Kotlin for Android, Swift for iOS, keep `Package.swift`.
2. Build a **bare Capacitor test app** that uses the plugin directly. Test on real devices with 30+ minute drives (background, screen off, different phone orientations).
3. Publish to npm (or a tagged GitHub repo) and wrap in the ODC library.
4. Every plugin change means a new MABS 12 build and a new install. Iterate in the bare app, not in ODC.

## 7. Open items to verify first

1. Which Capacitor version MABS 12 uses (read ODC page "Capacitor and Cordova support in MABS"). The Capacitor docs consulted were v8.
2. CoreMotion keeps delivering in the background while location background mode is active.
3. The Android library manifest (foreground service, permissions) merges in the MABS build.
4. The `plist` build action merges `UIBackgroundModes` as an array.
5. Whether MABS can fetch the plugin from a private GitHub repo or private npm registry.
6. How a JavaScript node can raise an ODC block event (only needed if a live listener is added later; the official Location plugin does it via a `LocationTracker` block).
7. Android 14+ foreground-service type rules and Google Play background-location declaration requirements.
8. ODC offline-sync pattern details for the Local Storage hand-off.

## 8. Out of scope for the PoC

- Native upload to the ODC cloud (needs token handling for long trips without the WebView).
- Full phone-to-car orientation calibration.
- GDPR consent flow, store review preparation, production hardening.
- Commercial telematics SDK evaluation (alternative: wrap a vendor SDK instead of building).

## 9. Suggested first tasks for Claude Code

1. Generate the plugin scaffold, add `definitions.ts` from section 3, and make the bridge calls return stub data.
2. Implement the Android foreground service with location, speed and a native buffer. Verify buffering with the screen off.
3. Add the linear-acceleration sensor and peak detection with logging. Compare against GPS speed deltas.
4. Implement the iOS equivalent. Run the CoreMotion-in-background check (item 2 above) early.
5. Build the bare Capacitor test app and run a first real drive.
6. Only then wrap in the ODC library using section 5.

## Sources
- ODC: Use mobile plugins; Building a Capacitor plugin from scratch; Set up Capacitor plugin project and define the interface layer; Android native implementation; iOS native implementation; Capacitor plugin architecture; Integrate Capacitor plugin into a mobile app; Library (plugin) extensibility configuration JSON schema; Android build actions; iOS build actions; Location plugin (success.outsystems.com)
- Capacitor: Android plugin guide; iOS plugin guide (capacitorjs.com/docs/plugins)
- github.com/ionic-team/capacitor-geolocation; github.com/capacitor-community/background-geolocation; capawesome.io background geolocation docs; @capacitor/motion docs
