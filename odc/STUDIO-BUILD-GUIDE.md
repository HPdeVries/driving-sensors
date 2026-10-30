# ODC Studio build guide: DrivingSensors mobile library + DriveMe demo app

Mentor (ODC's AI editor) can't create or edit mobile apps or mobile libraries, so these two steps are manual in ODC Studio. Tenant: pro-hrm.outsystems.dev.

Already in the tenant:
- `DrivingSensors` (`9b88953a-8ffb-4a8f-8482-e7bf9f371618`), a **regular** library, released 0.1.0. It has the 3 structures and 8 client actions, plus the extensibility config. Mentor could only create a regular library, but Capacitor plugins belong in a **Mobile Library** (like the Keyboard and Haptics plugins in this tenant). Part A moves the contents over.
- `DriveMe` (`d6b48b42-8877-43d5-b266-278cf54701f4`), an empty mobile app.

## Part A: Mobile library `DrivingSensorsPlugin` (about 5 min)

1. Create a **Mobile library** named `DrivingSensorsPlugin`.
2. Open the library's **Extensibility configuration** and paste `odc/extensibility-configuration.fixed-texts.json`.
3. Open `DrivingSensors` in a second Studio tab. Select the structures `TelemetryEvent`, `PermissionStatus` and `TrackingStatus`, copy them, and paste them into `DrivingSensorsPlugin`. Then do the same for the 8 client actions under Logic > Client Actions. Make sure everything is **Public**.
   - If cross-module paste isn't available, recreate them from `odc/structures.md` and `odc/client-actions/*.js`. Each `.js` file starts with the action's parameters.
4. Publish `DrivingSensorsPlugin`. Once DriveMe works, delete the old `DrivingSensors` library.

For a later iOS build: add `odc/buildAction.json` as a resource (Deploy Action: **Deploy to Target Directory**). Then add `"buildAction": { "config": "$resources.buildAction.json" }` inside `buildConfigurations` of the extensibility config. Android doesn't need it.

## Part B: DriveMe app

### Settings
- App identifier, under the app's mobile distribution / build settings: `com.ceeshr.driveme`.
- Framework: **Capacitor** (MABS 12).
- Manage dependencies: add `DrivingSensorsPlugin` with all structures and client actions.

### Data
Local Storage entity `LocalTelemetryEvent`:

| Attribute | Type |
|---|---|
| Id | Long Integer, auto-number (default) |
| NativeId | Long Integer |
| EventType | Text (30) |
| EventTime | Date Time |
| SessionId | Text (50) |
| Latitude, Longitude | Decimal (12,8) |
| SpeedKmh, PeakMps2, AccuracyM | Decimal (8,2) |

### Client action `SyncFromDevice`
Outputs: `Imported` (Integer), `Message` (Text).

1. `GetBufferedEvents` with AfterId = `0` and Limit = `500`.
2. If `not GetBufferedEvents.Success`: set Message = `GetBufferedEvents.ErrorMessage`, then End.
3. For Each over `GetBufferedEvents.Events`, then `CreateLocalTelemetryEvent` with:
   - NativeId = `.id`, EventType = `.type`, SessionId = `.sessionId`
   - EventTime = `AddSeconds(NewDateTime(1970,1,1,0,0,0), LongIntegerToInteger(.ts / 1000))`. That is UTC; add your timezone offset if you like.
   - Latitude = `.lat`, Longitude = `.lon`, SpeedKmh = `.speedMps * 3.6`, PeakMps2 = `.peakMps2`, AccuracyM = `.accuracyM`
4. If `GetBufferedEvents.Count > 0`: call `AckEvents` with UpToId = `GetBufferedEvents.LastId`.
5. Set Imported = `GetBufferedEvents.Count`.

The ack comes after the inserts, so a crash midway doesn't lose events: they stay on the device and are fetched again. A crash between insert and ack can produce duplicates, which you can dedupe on NativeId plus SessionId.

### Screen `Home`
Local variables: `PluginAvailable` (Boolean), `Status` (TrackingStatus), `Permissions` (PermissionStatus).

Client action `RefreshStatus`:
1. `CheckPlugin`, then assign `PluginAvailable = CheckPlugin.Available`.
2. `GetStatus`, then assign `Status = GetStatus.Status`.
3. `CheckPermissions`, then assign `Permissions = CheckPermissions.Permissions`.

The screen's `OnReady` runs `RefreshStatus`, then `SyncFromDevice`, then refreshes the aggregates.

Layout, top to bottom:
1. **Status card**:
   - Plugin available, Tracking active (`Status.active`), In trip (`Status.inTrip`), Buffered on device (`Status.buffered`), Session (`Status.sessionId`).
   - Permissions: `location`, `backgroundLocation`, `motion`, `notifications`.
2. **Buttons.** Each calls the action, shows a feedback message with `ErrorCode + ": " + ErrorMessage` when Success is false, then calls `RefreshStatus`:

   | Button | Calls |
   |---|---|
   | 1. Allow location | `RequestPermissions` with Permissions = `"location,motion,notifications"` |
   | 2. Allow background location | `RequestPermissions` with Permissions = `"backgroundLocation"` (Android shows the system settings page: choose "Allow all the time") |
   | Start tracking | `StartTracking` with OptionsJson = `""` (plugin defaults) |
   | Stop tracking | `StopTracking` |
   | Sync now | `SyncFromDevice`, then the feedback message `"Imported " + Imported + " events"`, then refresh the aggregates |

3. **Counters.** An aggregate on LocalTelemetryEvent grouped by EventType with Count, shown as a small list.
4. **Latest events.** An aggregate on LocalTelemetryEvent sorted by EventTime descending, Max Records 50. A list shows EventTime, EventType, SpeedKmh and PeakMps2. Row style: `If(EventType = "HARSH_BRAKE", "background-red", If(EventType = "HARSH_ACCEL", "background-orange", ""))`.

Optional: run `SyncFromDevice` from the app's On Application Resume handler.

## First test on Android
1. MABS 12 build (Capacitor), install, open.
2. "Plugin available" must be **True**. If it's False (`PLUGIN_NOT_AVAILABLE`), the plugin wasn't included in the build. Check the build log for `driving-sensors`.
3. Tap 1, then 2 (choose "Allow all the time"), then Start tracking. A "Trip recording active" notification should appear.
4. Drive (see the field-test checklist in `example-app/README.md`), then open the app and tap Sync now.

## Upgrading the library to v0.1.1

v0.1.1 adds `serviceRunning` and `lastLocation` to the status plus two new calls, `getLastReadings` and `getCurrentReadings`. Do these steps in ODC Studio, in order.

### In the mobile library `DrivingSensorsPlugin`
1. Open **Extensibility configuration** and change the plugin source to `"npm": "https://github.com/HPdeVries/driving-sensors#v0.1.2"` (paste `odc/extensibility-configuration.fixed-texts.json` again if you prefer; only that line differs). Make sure the `v0.1.1` tag exists on GitHub with `dist/` committed.
2. Add the three new **public** structures from `odc/structures.md` (attribute names are case-sensitive):
   - `LocationReading`: `ts` (Long Integer), `lat`, `lon`, `speedMps`, `accuracyM` (all Decimal).
   - `MotionReading`: `ts` (Long Integer), `ax`, `ay`, `az`, `gx`, `gy`, `gz`, `horizontalMps2`, `peakHorizontalMps2` (all Decimal), `samples` (Integer).
   - `SensorReadings`: `hasLocation` (Boolean), `location` (LocationReading), `hasMotion` (Boolean), `motion` (MotionReading).
3. Extend the existing structure `TrackingStatus` with `serviceRunning` (Boolean) and `lastLocation` (LocationReading). A missing `lastLocation` deserializes to a record with `ts = 0`.
4. `GetStatus` needs **no change** to its JS (it stringifies the whole result). Optionally update the comment from `odc/client-actions/GetStatus.js`. Its existing `Status` output (TrackingStatus, JSON Deserialize of `StatusJson`) now carries the new fields automatically.
5. Add client action **GetLastReadings** (public):
   - Outputs: `Success` (Boolean), `ErrorCode` (Text), `ErrorMessage` (Text), `ReadingsJson` (Text), `Readings` (SensorReadings).
   - Flow: JavaScript node with the body of `odc/client-actions/GetLastReadings.js`, then an **If** on `Success`. True: **JSON Deserialize** with JSON String = `ReadingsJson`, Data Type = `SensorReadings`, and assign its output to `Readings`. False: go straight to End (`Readings` stays at its defaults).
   - Give the JS node the output parameters `Success`, `ErrorCode`, `ErrorMessage`, `ReadingsJson`, and no inputs.
6. Add client action **GetCurrentReadings** (public), built the same way from `odc/client-actions/GetCurrentReadings.js`:
   - Inputs: `TimeoutMs` (Integer), `MotionSampleMs` (Integer). Use `0` for the plugin default. Keep them **Integer**, not Long Integer.
   - Outputs: as GetLastReadings, including the typed `Readings` (SensorReadings) filled by JSON Deserialize of `ReadingsJson` when `Success`.
7. **Publish** the library (a new version).

### In DriveMe
1. **Manage dependencies**: update `DrivingSensorsPlugin` to the new version and tick the new structures and the two new client actions.
2. Publish DriveMe and make a new **MABS build** (the plugin source changed, so a full native build is needed, not just a publish). Reinstall on the device.

### DriveMe suggestion: Readings card and status additions
Local variables on `Home`: `Readings` (SensorReadings).

- **Status card**: add `Status.serviceRunning`, and, when `Status.lastLocation.ts <> 0`, the last location time (`AddSeconds(NewDateTime(1970,1,1,0,0,0), LongIntegerToInteger(Status.lastLocation.ts / 1000))`), speed (`Status.lastLocation.speedMps * 3.6` km/h) and accuracy (`Status.lastLocation.accuracyM` m).
- **Readings card** with two buttons. Each calls its action, shows `ErrorCode + ": " + ErrorMessage` as feedback when Success is false, and otherwise assigns `Readings = <action>.Readings`:

  | Button | Calls |
  |---|---|
  | Last reading | `GetLastReadings` |
  | Current reading | `GetCurrentReadings` with TimeoutMs = `0`, MotionSampleMs = `0` (a fresh fix can take several seconds: disable the button while it runs) |

- The card shows, inside `If(Readings.hasLocation, ...)`: latitude `Readings.location.lat`, longitude `Readings.location.lon`, speed `Readings.location.speedMps * 3.6` km/h, accuracy `Readings.location.accuracyM` m. Inside `If(Readings.hasMotion, ...)`: horizontal acceleration `Readings.motion.horizontalMps2` m/s2 and peak `Readings.motion.peakHorizontalMps2` m/s2. Otherwise show "No location" / "No motion data".

## v0.1.2: GetPluginVersion

v0.1.2 adds one call, `getPluginVersion`, which reports the version of the native plugin code compiled into the app. Steps in ODC Studio:

1. In the mobile library `DrivingSensorsPlugin`, change the plugin source in **Extensibility configuration** to `"npm": "https://github.com/HPdeVries/driving-sensors#v0.1.2"` (make sure the `v0.1.2` tag exists on GitHub with `dist/` committed).
2. Add client action **GetPluginVersion** (public): no inputs; outputs `Success` (Boolean), `ErrorCode` (Text), `ErrorMessage` (Text), `Version` (Text), `Platform` (Text). Flow: a JavaScript node with the body of `odc/client-actions/GetPluginVersion.js` (output parameters as listed, no inputs), then End.
3. **Publish** the library, then in DriveMe use **Manage dependencies** to update it and tick the new client action, publish DriveMe, make a new **MABS build**, and uninstall and reinstall the app on the device.

Why this matters: ODC updates the JavaScript over the air, so the JS in a running app can be newer than the native binary that MABS built. `GetPluginVersion` asks the native side, so it tells you which native version is really installed. On a native binary older than 0.1.2 the call fails with a "not implemented" error, which itself means "native < 0.1.2".
