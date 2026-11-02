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
1. Open **Extensibility configuration** and change the plugin source to `"npm": "https://github.com/HPdeVries/driving-sensors#v0.2.0"` (paste `odc/extensibility-configuration.fixed-texts.json` again if you prefer; only that line differs). Make sure the `v0.1.1` tag exists on GitHub with `dist/` committed.
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

1. In the mobile library `DrivingSensorsPlugin`, change the plugin source in **Extensibility configuration** to `"npm": "https://github.com/HPdeVries/driving-sensors#v0.2.0"` (make sure the `v0.1.2` tag exists on GitHub with `dist/` committed).
2. Add client action **GetPluginVersion** (public): no inputs; outputs `Success` (Boolean), `ErrorCode` (Text), `ErrorMessage` (Text), `Version` (Text), `Platform` (Text). Flow: a JavaScript node with the body of `odc/client-actions/GetPluginVersion.js` (output parameters as listed, no inputs), then End.
3. **Publish** the library, then in DriveMe use **Manage dependencies** to update it and tick the new client action, publish DriveMe, make a new **MABS build**, and uninstall and reinstall the app on the device.

Why this matters: ODC updates the JavaScript over the air, so the JS in a running app can be newer than the native binary that MABS built. `GetPluginVersion` asks the native side, so it tells you which native version is really installed. On a native binary older than 0.1.2 the call fails with a "not implemented" error, which itself means "native < 0.1.2".

## v0.2.0: raw sensor recording

v0.2.0 adds raw sensor recording (accelerometer, gyroscope, magnetometer, barometer, GPS) in gzip CSV chunks, independent of trip tracking. Six new calls: `startRecording`, `stopRecording`, `getRecordingStatus`, `listRecordingChunks`, `getRecordingChunk`, `ackRecordingChunk`. The external API is not known yet, so the app uploads chunks to **its own server** (a server entity with a Binary Data attribute); forwarding comes later. Format and sizes: `odc/RECORDING-FORMAT.md`.

### A. Mobile library `DrivingSensorsPlugin` (copy from the Mentor-editable `DrivingSensors` copy library if you keep one)

1. **Extensibility configuration**: the plugin source is now `"npm": "https://github.com/HPdeVries/driving-sensors#v0.2.0"` (paste `odc/extensibility-configuration.fixed-texts.json` again; only that line differs). Make sure the `v0.2.0` tag exists on GitHub with `dist/` committed.
2. Add these **public structures** from `odc/structures.md` (section "v0.2.0: raw sensor recording structures"), in this order because of nesting: `RecordingSensorAvailability`, `RecordingSensorRates`, `RecordingSensorCounts`, `RecordingStatus`, `RecordingChunkInfo`, `RecordingChunkMeta`. Tip: use "Create structure from JSON" with the example under each one, then check the types against the table.
3. Add six **public client actions**. For each: a JavaScript node with the body of the matching file in `odc/client-actions/`, the JS node's inputs/outputs exactly as listed in the file's header comment, and for actions with a typed output an **If Success** then **JSON Deserialize**, as in GetStatus. Use **Integer** (not Long Integer) for number inputs.

   | Action | Inputs | Outputs (besides Success, ErrorCode, ErrorMessage) | Typed output (JSON Deserialize) |
   |---|---|---|---|
   | StartRecording | OptionsJson (Text) | RecordingId (Text) | |
   | StopRecording | | | |
   | GetRecordingStatus | | StatusJson (Text), Status (RecordingStatus) | Status = Deserialize StatusJson as RecordingStatus |
   | ListRecordingChunks | Limit (Integer) | ChunksJson (Text), Count (Integer), Chunks (List of RecordingChunkInfo) | Chunks = Deserialize ChunksJson as List of RecordingChunkInfo |
   | GetRecordingChunk | ChunkId (Text) | MetaJson (Text), DataBase64 (Text), Meta (RecordingChunkMeta) | Meta = Deserialize MetaJson as RecordingChunkMeta. **Do not deserialize DataBase64** |
   | AckRecordingChunk | ChunkId (Text) | | |

   The JS node itself only has the untyped outputs; the typed ones are assigned after it. `Limit` = `0` means the plugin default (100).
4. **Publish** the library.

### B. App `DriveMe`

1. **Manage dependencies**: update `DrivingSensorsPlugin`, tick the new structures and client actions. Also add a dependency on the **BinaryData** extension library (it provides `Base64ToBinary`; exact library name and action signature in your tenant are **unverified**: search "BinaryData" in Manage dependencies).
2. **Server entity** `RecordingChunk` (Data > Entities, not public):

   | Attribute | Type | Notes |
   |---|---|---|
   | Id | Long Integer, auto-number | |
   | ChunkId | Text (100) | **unique index**, idempotency key |
   | RecordingId | Text (50) | |
   | Seq | Integer | |
   | StartTs, EndTs | Long Integer | epoch ms |
   | Bytes | Integer | gzip size |
   | CountsJson | Text (200) | `JSON Serialize` of `Meta.counts` |
   | MetaJson | Text (2000) | full MetaJson from the device |
   | Data | Binary Data | the gzip bytes |
   | ReceivedOn | Date Time | `CurrDateTime()` |
   | SentOn | Date Time | for later forwarding (NullDate = not sent) |

   Add the unique index on ChunkId via the entity's **Indexes** (Add Index, Is Unique).
3. **Server action** `StoreRecordingChunk` (inputs `MetaJson` Text, `DataBase64` Text; outputs `Success` Boolean, `Message` Text):
   1. **JSON Deserialize** `MetaJson` as `RecordingChunkMeta`.
   2. `Base64ToBinary(DataBase64)` into a local `Data` (Binary Data).
   3. `GetRecordingChunkByChunkId(Meta.chunkId)`. If it exists: Success = True, Message = "duplicate ignored", End. This makes retries idempotent; the unique index is the safety net against a race.
   4. `CreateRecordingChunk` with ChunkId = `Meta.chunkId`, RecordingId = `Meta.recordingId`, Seq = `Meta.seq`, StartTs = `Meta.startTs`, EndTs = `Meta.endTs`, Bytes = `Meta.bytes`, CountsJson = `JSON Serialize(Meta.counts)`, MetaJson = `MetaJson`, Data = `Data`, ReceivedOn = `CurrDateTime()`.
   5. Success = True. Add an **All Exceptions** handler that sets Success = False, Message = `GetExceptionMessage()`.
4. **Client action** `UploadRecordingChunks` (outputs `Uploaded` Integer, `Success` Boolean, `Message` Text). It loops until the device has no more chunks and **stops at the first error**:
   1. `Uploaded = 0`.
   2. `ListRecordingChunks` with Limit = `20`. If not Success: Success = False, Message = ErrorCode + ": " + ErrorMessage, End.
   3. If `ListRecordingChunks.Count = 0`: Success = True, End.
   4. For Each `ListRecordingChunks.Chunks`:
      - `GetRecordingChunk` with ChunkId = `.chunkId`. If not Success: set Message, End.
      - `StoreRecordingChunk` with MetaJson = `GetRecordingChunk.MetaJson`, DataBase64 = `GetRecordingChunk.DataBase64`. If not Success, or the server call fails (network down: use the client action's exception handler): set Message, End. Do **not** ack.
      - `AckRecordingChunk` with ChunkId = `.chunkId`. If not Success: set Message, End (the chunk stays on the device and is ignored as duplicate on the next run).
      - `Uploaded = Uploaded + 1`.
   5. After the loop go back to step 2. It ends via step 3 (empty list) or an error exit.

   The order is the point: **store, then ack**. A crash between store and ack only produces a duplicate that step 3 of the server action ignores.
5. **Screen `Recording`** (add a menu link from `Home`). Local variables: `Status` (RecordingStatus), `Message` (Text).
   - Client action `RefreshRecording`: `GetRecordingStatus`, then `Status = GetRecordingStatus.Status`. Call it in `OnReady` and after every button press, plus a **Refresh** button. Automatic polling every few seconds needs a JavaScript `setInterval` that calls back into the screen (**unverified** in ODC; the example app polls every 2 s).
   - Buttons: **Start recording** calls `StartRecording` with OptionsJson = `""` (defaults); **Stop recording** calls `StopRecording`; **Upload now** calls `UploadRecordingChunks` and shows `"Uploaded " + Uploaded + " chunks"` or Message. When Success is false show `ErrorCode + ": " + ErrorMessage`.
   - **Status card**:
     - `Status.recording`, `Status.recordingId`, start time `AddSeconds(NewDateTime(1970,1,1,0,0,0), LongIntegerToInteger(Status.startedAt / 1000))`.
     - `Status.chunksPending`; `bytesPending` as MB: `Round(Status.bytesPending / 1048576, 2) + " MB"`.
     - `measuredHz` vs target, one row per sensor: acc `Status.measuredHz.acc` / 100, gyr `.gyr` / 100, mag `.mag` / 50, bar `.bar` / 10, loc `.loc` / 1 (format with `Round(x, 1)`; highlight below 80 % of target; iOS delivers the barometer at about 1 Hz).
     - Available flags `Status.available.acc` to `.loc` (a missing sensor shows False and stays at 0 Hz).
     - `Status.stopReason`: when not empty show a warning (`STORAGE_FULL`, `PERMISSION_LOST`, `ERROR`).
   - Recording needs location permission first (`RequestPermissions("location")` on `Home`); for recording with the screen off also background location and notifications, as for tracking.
6. Publish DriveMe and make a new **MABS build** (the plugin source changed: full build and reinstall). Check that `GetPluginVersion` returns `0.2.0`.

### Payload size and ODC limits

A 60-s chunk is about 230 KB gzip, about 310 KB as base64 (measured, see `odc/RECORDING-FORMAT.md`). `UploadRecordingChunks` sends one chunk per server call, so a request carries a few hundred KB, which is fine for a normal server action call. A formal ODC request/payload size limit for server actions was **not found (unverified)**: a web search turned up only community answers saying OutSystems has no configurable REST message-size setting and advising to chunk large payloads. One chunk per call stays far below typical gateway limits of several MB; a backlog does not make a call bigger because it uploads chunk by chunk.

### Later: forward to the external API

When the API is known, add an ODC **Timer** `ForwardRecordingChunks`: select `RecordingChunk` rows with `SentOn = NullDate()` ordered by `Id` (e.g. 10 per run). For each call the consumed REST API with `Data` as binary body (or base64 field), `MetaJson` and `ChunkId` as idempotency key; on 2xx set `SentOn = CurrDateTime()`; on an error stop the run and let the next cycle retry. A second timer deletes rows with `SentOn` older than N days. Format notes for the API contract: `odc/RECORDING-FORMAT.md`.
