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
