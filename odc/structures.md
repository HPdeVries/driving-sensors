# ODC structures and app-side sync design

Create these in the mobile library (so consuming apps can reuse them) and mark them public. Attribute names must match the JSON keys exactly (ODC JSON Deserialize matches by name, case-insensitive per the usual ODC behaviour: **unverified**, so keep exact case anyway). Fields the plugin omits (it never sends `null`) deserialize to the ODC default value; that is why optional numbers are plain Decimal and you must not rely on "null".

## Structures

### TelemetryEvent (one element of `EventsJson`, which is a JSON **array**)

| Attribute | ODC type | Notes |
|---|---|---|
| id | Long Integer | native buffer id, monotonically increasing |
| ts | Long Integer | epoch ms. Convert with `AddMilliseconds(#1970-01-01#, ts)` (UTC) |
| type | Text | `LOCATION`, `HARSH_BRAKE`, `HARSH_ACCEL`, `TRIP_START`, `TRIP_END` |
| sessionId | Text | |
| lat | Decimal | omitted for some events -> 0 |
| lon | Decimal | omitted -> 0 |
| speedMps | Decimal | omitted -> 0 |
| accuracyM | Decimal | omitted -> 0 |
| peakMps2 | Decimal | HARSH_* only |

Deserialize `EventsJson` with a **JSON Deserialize** node, *Data Type* = `List of TelemetryEvent`. Because ODC cannot distinguish "0" from "absent", the local entity below stores `HasPosition`/`HasSpeed` booleans derived from `type` (LOCATION, TRIP_START, TRIP_END, HARSH_* carry lat/lon per SPEC.md), or simply store 0 and treat accuracy 0 as "unknown".

### PermissionStatus (`PermissionsJson`)

| Attribute | ODC type |
|---|---|
| location | Text |
| backgroundLocation | Text |
| motion | Text |
| notifications | Text |

Values: `granted`, `denied`, `prompt`, `prompt-with-rationale`.

### TrackingStatus (`StatusJson`)

| Attribute | ODC type |
|---|---|
| active | Boolean |
| sessionId | Text |
| inTrip | Boolean |
| buffered | Integer |
| serviceRunning | Boolean (v0.1.1) |
| lastLocation | LocationReading (v0.1.1) |

`lastLocation` is omitted by the plugin when no location is known yet. A missing nested object deserializes to a record with all defaults, so **`lastLocation.ts = 0` means "no last location"**. `serviceRunning` is true while the native tracking service is alive.

### LocationReading (v0.1.1, nested in `TrackingStatus` and `SensorReadings`)

| Attribute | ODC type | Notes |
|---|---|---|
| ts | Long Integer | epoch ms; 0 = not present |
| lat | Decimal | |
| lon | Decimal | |
| speedMps | Decimal | m/s, multiply by 3.6 for km/h |
| accuracyM | Decimal | |

### MotionReading (v0.1.1, nested in `SensorReadings`)

| Attribute | ODC type | Notes |
|---|---|---|
| ts | Long Integer | epoch ms |
| ax, ay, az | Decimal | accelerometer, m/s2 |
| gx, gy, gz | Decimal | gyroscope |
| horizontalMps2 | Decimal | horizontal acceleration magnitude |
| peakHorizontalMps2 | Decimal | peak horizontal acceleration in the sample window |
| samples | Integer | number of samples used |

### SensorReadings (v0.1.1, `ReadingsJson` of GetLastReadings / GetCurrentReadings)

| Attribute | ODC type | Notes |
|---|---|---|
| hasLocation | Boolean | **explicit flag**, set by the client action JS (`hasLocation = !!location`) |
| location | LocationReading | all defaults when `hasLocation` is False |
| hasMotion | Boolean | explicit flag |
| motion | MotionReading | all defaults when `hasMotion` is False |

The plugin omits readings it does not have; ODC cannot tell an omitted value from 0, so always test `hasLocation` / `hasMotion` before using the nested records. (The client action JS adds both flags to `ReadingsJson`, so the plugin does not need to send them.)

### TrackingOptions (serialize with **JSON Serialize** into `OptionsJson`)

| Attribute | ODC type | Default |
|---|---|---|
| harshBrakeMps2 | Decimal | 3.0 |
| harshAccelMps2 | Decimal | 3.0 |
| locationIntervalMs | Integer | 1000 |
| accelSampleHz | Integer | 50 |
| tripStartSpeedMps | Decimal | 4.2 |
| tripEndIdleMs | Integer | 180000 |
| minEventGapMs | Integer | 3000 |
| notificationTitle | Text | "Trip recording active" |
| notificationText | Text | "Recording driving data" |

Caveat: JSON Serialize emits every attribute, with ODC defaults (0 / empty) for unset ones. A `0` threshold would be passed to the plugin. Always fill all attributes from a settings source (app Site Properties or a constant record) before serializing. Empty `notificationTitle` / `notificationText` should be replaced by the defaults above first (**unverified** how the native side treats empty strings). Alternative: build the JSON by hand with `Concat` and omit attributes you do not want to override.

## Local Storage entity (in the consuming app)

`DrivingEvent` (Local Storage entity, Is Public optional):

| Attribute | Type | Notes |
|---|---|---|
| Id | Long Integer (Identifier, **not** auto-number) | use the native `id` of the device as key. Unique per install only, so prefix with device identity on the server |
| Ts | Date Time | converted from epoch ms |
| TsEpochMs | Long Integer | keep the raw value, avoids timezone surprises |
| Type | Text | or a Static Entity `EventType` identifier |
| SessionId | Text | |
| Lat, Lon, SpeedMps, AccuracyM, PeakMps2 | Decimal | |
| IsFromServer | Boolean | default False (read/write sync pattern metadata) |
| IsModified | Boolean | default True (marks "needs upload") |

A tiny second entity `SyncState` (one row) is optional; the native buffer plus Ack already tracks progress, so you do not need a "last id" cursor as long as you Ack after the local insert.

## Resume/timer sync flow

```
Trigger: OnApplicationResume  |  Timer every N s while foreground  |  after StartTracking
  1. GetBufferedEvents(AfterId = 0, Limit = 500)       // after Ack the buffer only holds new events
  2. if not Success -> log ErrorCode/ErrorMessage, stop
  3. JSON Deserialize EventsJson -> List<TelemetryEvent>
  4. For each event: DrivingEvent_CreateOrUpdate (idempotent on Id)
  5. AckEvents(UpToId = LastId)                        // ONLY after step 4 committed
  6. If Count = Limit -> loop to step 1
  7. TriggerOfflineDataSync / rely on "Synchronize On Online" (see below)
```

Why this order is safe: the native buffer is only trimmed by `ackEvents`. If the app dies between 4 and 5 the same events are returned next time and `CreateOrUpdate` on the same Id makes step 4 idempotent. Never Ack before the local insert is committed.

Local Storage writes inside a loop are individually committed per CreateOrUpdate call in ODC client actions (**unverified**; if you need atomicity use a single-row "batch" record or accept idempotent replays).

Offline sync (device -> server): events are write-only from the device. Use the *Read/Write data* family of patterns: the sync action (`OnSync` system event -> `SyncTask` client action) sends all `DrivingEvent` records with `IsModified = True` to a server action that bulk-inserts them, then sets `IsModified = False` locally (or deletes them). Because the server cannot change these events there is no conflict handling to implement ("last write wins" is sufficient). Configure the app's mobile properties: **Synchronize On Online**, **Synchronize On Resume**, **Synchronize On Login**, plus **Retry Synchronizations / Retry Interval**; feedback events `OnSyncStart`, `OnSyncComplete`, `OnSyncError` are available on screens/blocks.

Performance note: LOCATION events at 1 Hz produce ~3,600 rows per hour of driving. Consider (a) raising `locationIntervalMs` to 2000-5000 in production, (b) deleting locally synced rows after upload, (c) uploading in batches (a few hundred rows per server call) to stay below request-size limits.

## Sources

- Implement offline data synchronization (ODC): https://success.outsystems.com/documentation/outsystems_developer_cloud/building_apps/data_management/offline_data_synchronization_in_mobile_apps/implement_offline_data_synchronization/ - confirms the `ServerDataSync` + `SyncTask` + `OnSync` pattern, the sync triggers and the OnSyncStart/Complete/Error events. It does not describe the write-data patterns in detail; it only links them.
- ODC offline data sync announcement (Sept 2025): https://www.outsystems.com/product-updates/odc-offline-data-sync/
- Offline sync patterns (OutSystems 11 docs, read/write last-write-wins, `IsFromServer`/`IsModified` metadata; the ODC pattern pages were not retrieved): https://success.outsystems.com/documentation/11/building_apps/data_management/mobile_performance_strategies_and_offline_optimization/offline_data_sync_patterns/read_write_data_last_write_wins/
- **Unverified:** `TriggerOfflineDataSync` / `OfflineDataSyncConfiguration` / `SyncUnit` are OutSystems 11 Sync Framework names; the ODC page above does not mention them. Check in ODC Studio which actions exist (the "OnSync" system event is documented for ODC).
