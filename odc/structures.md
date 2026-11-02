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
| ax, ay, az | Decimal | linear acceleration (gravity removed), m/s2, device axes |
| gx, gy, gz | Decimal | gravity vector, m/s2, device axes (phone orientation) |
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

## v0.2.0: raw sensor recording structures

All **public**, in the mobile library `DrivingSensorsPlugin`. Attribute names equal the JSON keys. Each JSON example below is realistic and meant for **"Create structure from JSON"** in Studio (decimals with a non-zero fraction infer Decimal, timestamps above 2^31 infer Long Integer). After pasting, check the inferred types against the tables (e.g. a count that happens to be 0 would infer Integer anyway; a rate such as `100.0` could infer Integer, which is why the examples use fractions).

### RecordingSensorAvailability (nested `available`)

| Attribute | ODC type |
|---|---|
| acc, gyr, mag, bar, loc | Boolean |

```json
{ "acc": true, "gyr": true, "mag": true, "bar": true, "loc": true }
```

### RecordingSensorRates (nested `measuredHz`)

Actual samples/s per sensor over the last ~5 s. Omitted when not recording, so it deserializes to all 0.

| Attribute | ODC type |
|---|---|
| acc, gyr, mag, bar, loc | Decimal |

```json
{ "acc": 99.4, "gyr": 99.8, "mag": 49.6, "bar": 9.8, "loc": 0.98 }
```

### RecordingSensorCounts (nested `counts` of a chunk)

Samples per sensor in one chunk. (The plugin type is one `RecordingSensorStats` with numbers; ODC needs two structures because one is Decimal and the other Integer.)

| Attribute | ODC type |
|---|---|
| acc, gyr, mag, bar, loc | Integer |

```json
{ "acc": 6012, "gyr": 6003, "mag": 3001, "bar": 598, "loc": 60 }
```

### RecordingStatus (`StatusJson` of GetRecordingStatus)

| Attribute | ODC type | Notes |
|---|---|---|
| recording | Boolean | |
| recordingId | Text | omitted when never started |
| startedAt | Long Integer | epoch ms, 0 = absent |
| chunksPending | Integer | finished chunks waiting to be fetched |
| bytesPending | Long Integer | bytes on disk (finished + current); Long Integer attribute is fine inside JSON |
| available | RecordingSensorAvailability | |
| measuredHz | RecordingSensorRates | all 0 when not recording |
| stopReason | Text | `STORAGE_FULL`, `PERMISSION_LOST`, `ERROR`; empty = none |

```json
{
  "recording": true,
  "recordingId": "3f0c2c36-6f4e-4a4f-9c53-1d2a8d6a9b11",
  "startedAt": 1793367101003,
  "chunksPending": 3,
  "bytesPending": 712345,
  "available": { "acc": true, "gyr": true, "mag": true, "bar": true, "loc": true },
  "measuredHz": { "acc": 99.4, "gyr": 99.8, "mag": 49.6, "bar": 9.8, "loc": 0.98 },
  "stopReason": "STORAGE_FULL"
}
```

(`stopReason` is only present after an automatic stop.) In the status screen compare `measuredHz` with the targets 100 / 100 / 50 / 10 / 1.

### RecordingChunkInfo (one element of `ChunksJson`, a JSON **array**)

| Attribute | ODC type | Notes |
|---|---|---|
| chunkId | Text | `<recordingId>-<seq 6 digits>` |
| recordingId | Text | |
| seq | Integer | |
| startTs, endTs | Long Integer | epoch ms of first/last sample |
| bytes | Integer | size of the gzip payload (a few hundred KB) |
| counts | RecordingSensorCounts | |

```json
{
  "chunkId": "3f0c2c36-6f4e-4a4f-9c53-1d2a8d6a9b11-000002",
  "recordingId": "3f0c2c36-6f4e-4a4f-9c53-1d2a8d6a9b11",
  "seq": 2,
  "startTs": 1793367221003,
  "endTs": 1793367280998,
  "bytes": 235186,
  "counts": { "acc": 6012, "gyr": 6003, "mag": 3001, "bar": 598, "loc": 60 }
}
```

`ListRecordingChunks.Chunks` is a **List of RecordingChunkInfo**: JSON Deserialize `ChunksJson` with that data type.

### RecordingChunkMeta (`MetaJson` of GetRecordingChunk)

The chunk object **without** `data` (the base64 goes in the separate `DataBase64` output, so the big string is never deserialized). RecordingChunkInfo fields plus:

| Attribute | ODC type | Notes |
|---|---|---|
| chunkId, recordingId, seq, startTs, endTs, bytes, counts | as RecordingChunkInfo | repeat them (ODC has no structure inheritance) |
| format | Text | always `csv-v1` |
| encoding | Text | always `gzip+base64` |
| platform | Text | `android` / `ios` |
| deviceModel | Text | |
| osVersion | Text | |
| pluginVersion | Text | |
| sensorInfoJson | Text | the plugin's `sensorInfo` map as a **JSON string** |

Why `sensorInfoJson`: `sensorInfo` is a free-form map (`{"acc":"Bosch BMI160 (raw)", ...}`) whose keys depend on the device; ODC structures need fixed attributes. The GetRecordingChunk client action therefore replaces `sensorInfo` with `sensorInfoJson` (a stringified copy). Store it as text and parse it later in the forwarding step if needed.

```json
{
  "chunkId": "3f0c2c36-6f4e-4a4f-9c53-1d2a8d6a9b11-000002",
  "recordingId": "3f0c2c36-6f4e-4a4f-9c53-1d2a8d6a9b11",
  "seq": 2,
  "startTs": 1793367221003,
  "endTs": 1793367280998,
  "bytes": 235186,
  "counts": { "acc": 6012, "gyr": 6003, "mag": 3001, "bar": 598, "loc": 60 },
  "format": "csv-v1",
  "encoding": "gzip+base64",
  "platform": "android",
  "deviceModel": "Google Pixel 8",
  "osVersion": "15",
  "pluginVersion": "0.2.0",
  "sensorInfoJson": "{\"acc\":\"BMI3xx Accelerometer (Bosch), raw\",\"gyr\":\"BMI3xx Gyroscope (Bosch), calibrated\"}"
}
```

### RecordingOptions (serialize into `OptionsJson` of StartRecording; usually just pass `""`)

| Attribute | ODC type | Default |
|---|---|---|
| accelHz, gyroHz, magHz, baroHz | Integer | 100 / 100 / 50 / 10 |
| locationIntervalMs | Integer | 1000 |
| chunkSeconds | Integer | 60 |
| maxStorageMb | Integer | 500 |
| notificationTitle, notificationText | Text | see SPEC.md |

Same caveat as TrackingOptions: JSON Serialize emits all attributes with 0 / empty defaults. Pass `""` unless you build the JSON by hand with only the keys you override.

## v0.3.0: native upload and heartbeat structures

All **public**, in the mobile library `DrivingSensorsPlugin`. Same "Create structure from JSON" tips as above. `lastSuccessAt`, `lastErrorAt`, `nextAttemptAt` are epoch ms (Long Integer). Optional keys are simply missing from the JSON until set; the structure then holds the ODC default (0 / empty / False).

### UploadStatus (`StatusJson` of GetUploadStatus)

| Attribute | ODC type | Notes |
|---|---|---|
| configured | Boolean | |
| state | Text | `disabled`, `idle`, `uploading`, `waiting_network`, `backoff`, `auth_failed` |
| endpointBaseUrl, deviceId | Text | the token is never returned |
| uploadRecordings, uploadEvents, wifiOnly | Boolean | |
| pendingChunks, pendingEvents, rejectedChunks | Integer | what is on the device now; rejected = refused with 400/413/422, kept, not retried |
| uploadedChunks, uploadedEvents | Integer | since the last ConfigureUpload |
| lastSuccessAt, lastErrorAt, nextAttemptAt | Long Integer | epoch ms |
| lastError | Text | |
| lastHttpStatus | Integer | |

```json
{
  "configured": true,
  "state": "backoff",
  "endpointBaseUrl": "https://pro-hrm-dev.outsystems.app/DriveMe/rest/Telemetry",
  "deviceId": "7d1f0c3a-5b7e-4c0a-9d51-2f8f1b0a6c11",
  "uploadRecordings": true,
  "uploadEvents": true,
  "wifiOnly": false,
  "pendingChunks": 3,
  "pendingEvents": 128,
  "rejectedChunks": 0,
  "uploadedChunks": 12,
  "uploadedEvents": 2450,
  "lastSuccessAt": 1767225600000,
  "lastErrorAt": 1767225900000,
  "lastError": "HTTP 503",
  "lastHttpStatus": 503,
  "nextAttemptAt": 1767226200000
}
```

### HeartbeatStatus (`StatusJson` of GetHeartbeatStatus)

| Attribute | ODC type |
|---|---|
| running | Boolean |
| intervalSec | Integer |
| sent, succeeded | Integer |
| lastSentAt, lastSuccessAt | Long Integer (epoch ms) |
| lastError | Text |
| lastHttpStatus | Integer |

```json
{
  "running": true,
  "intervalSec": 30,
  "sent": 120,
  "succeeded": 118,
  "lastSentAt": 1767225600000,
  "lastSuccessAt": 1767225600000,
  "lastError": "timeout",
  "lastHttpStatus": 200
}
```

### HeartbeatPayload (body of `POST {base}/heartbeat`; the exposed REST API receives it, see `BACKEND-UPLOAD-GUIDE.md`)

| Attribute | ODC type | Notes |
|---|---|---|
| deviceId, platform, pluginVersion | Text | |
| ts | Long Integer | epoch ms on the device |
| seq | Long Integer | 1, 2, 3 ... since StartHeartbeat |
| intervalSec, processUptimeSec | Integer | uptime resets when the OS restarted the process |
| appState | Text | `foreground` / `background` |
| batteryLevel | Integer | 0..100, -1 unknown |
| charging, tracking, inTrip, recording | Boolean | |
| network | Text | `wifi`, `cellular`, `other`, `none` |
| pendingEvents, pendingChunks | Integer | |
| uploadState | Text | |

```json
{
  "deviceId": "7d1f0c3a-5b7e-4c0a-9d51-2f8f1b0a6c11",
  "platform": "android",
  "pluginVersion": "0.3.0",
  "ts": 1767225600000,
  "seq": 42,
  "intervalSec": 30,
  "processUptimeSec": 1290,
  "appState": "background",
  "batteryLevel": 81,
  "charging": false,
  "network": "cellular",
  "tracking": true,
  "inTrip": true,
  "recording": false,
  "pendingEvents": 4,
  "pendingChunks": 0,
  "uploadState": "idle"
}
```

## Server entity for uploaded chunks (in the consuming app)

See `STUDIO-BUILD-GUIDE.md`, section "v0.2.0: raw sensor recording": entity `RecordingChunk`, server action `StoreRecordingChunk`, client action `UploadRecordingChunks`. CSV format and size estimates: `RECORDING-FORMAT.md`.

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
