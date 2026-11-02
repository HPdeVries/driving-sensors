# DrivingSensors native behaviour spec

The TypeScript contract in `src/definitions.ts` is authoritative. This file pins down behaviour both native implementations must share. Android and iOS must behave identically for the same inputs.

## Lifecycle

- `startTracking(options)`: validate permissions (foreground location required; reject with `PERMISSION_DENIED` otherwise, background location missing is allowed but logged), resolve options with defaults from `definitions.ts`, create `sessionId` (UUID v4 string), persist `{active, sessionId, options}` so tracking resumes after a process restart, start location updates. Idempotent while active.
- Location runs for the whole time tracking is active (it is what keeps the app alive in the background). While **not** in a trip: low-power mode (Android: balanced power, 5000 ms interval; iOS: keep `kCLLocationAccuracyBest` but `distanceFilter = 10`). While **in** a trip: high accuracy at `locationIntervalMs` (iOS: `distanceFilter = kCLDistanceFilterNone`).
- The accelerometer runs **only while in a trip**, at `accelSampleHz`.
- `stopTracking()`: if in a trip, write TRIP_END; stop sensors and location; clear persisted active state. Buffer is kept.
- Android: `startTracking` starts a foreground service (`foregroundServiceType="location"`) with a persistent notification, `START_STICKY`, and resumes from persisted state if restarted by the system. iOS: `allowsBackgroundLocationUpdates = true`, `pausesLocationUpdatesAutomatically = false`, `showsBackgroundLocationIndicator = true`, `activityType = .automotiveNavigation`. On plugin `load()`, if persisted state says active and location permission is still granted, resume tracking.

## Speed

Use the platform speed when valid (Android `hasSpeed()`, iOS `speed >= 0`). Otherwise derive it from distance / time to the previous fix (haversine) when the time gap is between 0.5 s and 10 s; else leave it undefined.

## Trip detection (`TripDetector`, pure logic, no platform types)

- Input: location fixes `(ts, speedMps?)`.
- Not in trip -> TRIP_START when 3 consecutive fixes have `speed >= tripStartSpeedMps`. The TRIP_START event uses the first of those 3 fixes (ts, position, speed).
- In trip -> TRIP_END when no fix has had `speed >= tripStartSpeedMps` for `tripEndIdleMs`. The TRIP_END event uses the last fix at or above the threshold. Evaluate this on every fix and also on a timer (every 10 s) so a stationary phone with no fixes still ends the trip.
- A fix with unknown speed neither starts nor extends a trip.

## LOCATION events

While in a trip, write one LOCATION event per received fix, throttled to at most one per `locationIntervalMs` (minus 100 ms tolerance). Include lat, lon, speedMps, accuracyM. No LOCATION events outside trips.

## Harsh event detection (`HarshEventDetector`, pure logic, no platform types)

Inputs: accelerometer samples `(ts, ax, ay, az)` of **linear acceleration in m/s²** (gravity removed), the latest gravity vector `(gx, gy, gz)`, and location fixes `(ts, speedMps?)`.

1. Horizontal magnitude per sample: with `ĝ = g/|g|`, `h = a - (a·ĝ)ĝ`, `m = |h|`. If `|g| < 1`, use `m = |a|`.
2. Smooth `m` with a moving average over the last 250 ms of samples.
3. Trigger threshold `T = min(harshBrakeMps2, harshAccelMps2)`. A candidate starts when the smoothed value rises above `T`, and ends when it drops below `0.7 * T`. Track the peak smoothed value and start/end timestamps.
4. Discard candidates shorter than 200 ms. Discard a candidate whose start is within `minEventGapMs` of the end of the previous **emitted** event.
5. Classify with GPS speed: `v0` = speed of the latest fix with `ts <= start`; `v1` = speed of the first fix with `ts >= end + 1000`. Classification waits for `v1` (keep pending candidates; give up after 5 s with no qualifying fix and discard). If `v0` or `v1` is unknown, or `|v1 - v0| < 0.5`, discard. `v1 < v0` -> HARSH_BRAKE, requires `peak >= harshBrakeMps2`. `v1 > v0` -> HARSH_ACCEL, requires `peak >= harshAccelMps2`. Otherwise discard.
6. Emitted event: `ts` = candidate start, `peakMps2` = peak, `speedMps` = v0, lat/lon from the fix used for v0.

Log each discarded candidate with the reason (debug log) for the cross-check against GPS deltas.

Units: iOS `CMDeviceMotion.userAcceleration` and `gravity` are in g; multiply by 9.80665 before passing to the detector. Android: `TYPE_LINEAR_ACCELERATION` for `a`, `TYPE_GRAVITY` for `g` (fallback to low-pass filtered `TYPE_ACCELEROMETER` if `TYPE_GRAVITY` is missing; if `TYPE_LINEAR_ACCELERATION` is missing, derive `a = accel - lowpass(accel)`).

## Buffer (SQLite)

Table `events`:

| column | type |
|---|---|
| id | INTEGER PRIMARY KEY AUTOINCREMENT |
| ts | INTEGER NOT NULL |
| type | TEXT NOT NULL |
| session_id | TEXT NOT NULL |
| lat, lon, speed_mps, accuracy_m, peak_mps2 | REAL NULL |

- `getBufferedEvents({afterId = 0, limit = 500})`: `WHERE id > afterId ORDER BY id LIMIT min(limit, 1000)`. Omit null fields from the returned objects (do not send `null`).
- `ackEvents({upToId})`: `DELETE WHERE id <= upToId`. Reject with `INVALID_ARGUMENT` if `upToId` is missing.
- Writes happen off the main thread. Android: `SQLiteOpenHelper`. iOS: the system `sqlite3` C API (no third-party dependency), serial dispatch queue.
- Database file: Android `context.getDatabasePath("driving_sensors.db")`; iOS Application Support `driving_sensors.sqlite`, excluded from backup.

## Error codes (`call.reject(message, code)`)

`PERMISSION_DENIED`, `LOCATION_DISABLED`, `INVALID_ARGUMENT`, `NOT_SUPPORTED`, `INTERNAL`.

## Permissions mapping

| alias | Android | iOS |
|---|---|---|
| location | ACCESS_FINE_LOCATION (+ COARSE) | when-in-use or always |
| backgroundLocation | ACCESS_BACKGROUND_LOCATION (API 29+; below 29 equals `location`) | authorizationStatus == .authorizedAlways |
| motion | none, always `granted` | CMMotionActivityManager.authorizationStatus(); request by a short CMMotionActivityManager query |
| notifications | POST_NOTIFICATIONS (API 33+; below that `granted`) | always `granted` |

iOS: requesting `location` calls `requestWhenInUseAuthorization`; requesting `backgroundLocation` calls `requestAlwaysAuthorization`. Resolve the call when the authorization delegate reports a change (or immediately if the state is already determined and cannot change).

## v0.1.1 additions

### getStatus
- `serviceRunning`: Android `TrackingCoordinator.service != null` and its session is set; iOS location updates currently started by the plugin. `active` keeps its meaning (persisted request).
- `lastLocation`: the latest fix (any fix, in or out of trip, including ones with unknown speed) the tracking engine received since it (re)started. Omit when none. Omit null sub-fields.

### getLastReadings
- Cached in memory by the tracking engine: latest fix (`location`) and latest motion sample (`motion`: linear accel, current gravity, and its horizontal magnitude via the same projection as `HarshEventDetector.horizontalMagnitude`). No sensors are switched on. `hasLocation`/`hasMotion` false and the object omitted when nothing is cached (e.g. motion outside a trip after a restart). Motion samples from a previous trip remain as the "last" reading.

### getCurrentReadings({timeoutMs = 10000, motionSampleMs = 1000})
- Validate: both positive numbers if present; clamp timeoutMs to [1000, 60000], motionSampleMs to [100, 5000]. Requires foreground location permission (`PERMISSION_DENIED` otherwise); location services off -> `LOCATION_DISABLED`.
- In parallel: (a) request one fresh high-accuracy fix (Android `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY, cancellationToken)`; iOS a dedicated `CLLocationManager` with `requestLocation()`), timeout -> `hasLocation: false`; (b) register motion sensors for `motionSampleMs` at 50 Hz using the same sensor/fallback logic as tracking, then report the last sample's a/g/horizontal plus `peakHorizontalMps2` (max horizontal over the window) and `samples`. No samples -> `hasMotion: false`.
- Must not disturb an active tracking session (separate listener/manager; do not reset detectors). Resolve once both parts finish.
- Never writes to the event buffer.

### Android notification
- Channel importance DEFAULT with `setSilent(true)` / no sound, so the icon shows in the status bar. Use a new channel id `driving_sensors_tracking_v2` (importance of an existing channel can't be raised).

### Android: undeclared permissions
- In `requestPermissions`, before requesting, check the requested permission strings are declared in the app manifest (`packageManager.getPackageInfo(packageName, GET_PERMISSIONS).requestedPermissions`). If any are missing, reject with `NOT_SUPPORTED` and message `Missing in AndroidManifest.xml: <list>`.

## v0.2.0: raw sensor recording

Independent of trip tracking: either, both or neither can run. On Android both are hosted by the same foreground service (it runs while at least one is active; notification text reflects what is running). On iOS both share the same CLLocationManager for background liveness: location updates keep running while either is active (stopping one must not stop the other's location updates).

### Sensors and units (device axes)

| sensor | Android | iOS | columns x,y,z,v4,v5 |
|---|---|---|---|
| acc | TYPE_ACCELEROMETER (incl. gravity) | CMAccelerometerData (raw), × -9.80665 so that a phone lying face-up reads z ≈ +9.81 (Android convention) | x,y,z m/s² |
| gyr | TYPE_GYROSCOPE (calibrated) | CMGyroData (raw) | x,y,z rad/s |
| mag | TYPE_MAGNETIC_FIELD (calibrated) | CMMagnetometerData (raw, uncalibrated) | x,y,z µT |
| bar | TYPE_PRESSURE | CMAltimeter relative altitude updates (pressure kPa × 10) | x = pressure hPa; y = relative altitude m (iOS only, empty on Android) |
| loc | FusedLocation HIGH_ACCURACY at locationIntervalMs | the shared CLLocationManager, best accuracy, distanceFilter none | x = lat, y = lon, z = altitude m, v4 = speed m/s, v5 = horizontal accuracy m (empty when unknown) |

Use the sensor's own event timestamp converted to epoch ms (Android: elapsedRealtimeNanos offset computed once per registration; iOS: `timestamp` + (Date().timeIntervalSince1970 - systemUptime) computed once). Location uses the fix time. Requested rates are hints; record whatever the platform delivers (no resampling). A sensor missing on the device is skipped and reported `available.<s> = false`. sensorInfo records name/vendor and calibrated/raw per sensor.

Android: register with `samplingPeriodUs = 1_000_000 / hz` (100 Hz needs no HIGH_SAMPLING_RATE_SENSORS). iOS: one CMMotionManager per app: the plugin's existing manager must be used; raw accelerometer/gyro/magnetometer updates run alongside device-motion updates used by trip tracking.

### Recording CSV format (csv-v1)

UTF-8, `\n` line endings, header line then one sample per line, chronological per sensor (sensors interleaved in arrival order):

```
t_ms,sensor,x,y,z,v4,v5
1793367101003,acc,0.123,-0.045,9.812,,
1793367101005,gyr,0.0012,-0.0004,0.0001,,
1793367101010,mag,21.5,-3.2,-40.1,,
1793367101050,bar,1013.25,,,,
1793367101000,loc,52.09073741,5.12142212,4.2,13.4,4.8
```

Numbers: up to 6 decimals for acc/gyr/mag/bar (Locale.ROOT / POSIX formatting, `.` decimal separator), 8 for lat/lon. Empty field = unknown.

### Files and durability

- Directory: Android `filesDir/driving_sensors/recordings/`, iOS Application Support `driving_sensors/recordings/` (excluded from backup).
- Samples are buffered in memory and appended to `<chunkId>.csv.part` (plain CSV) once per second on a background thread (never on the sensor callback thread).
- Every `chunkSeconds` (by wall clock since chunk start) or on stop: close the part file, gzip it to `<chunkId>.csv.gz`, write `<chunkId>.json` with the RecordingChunkInfo fields, delete the part file. Only then is the chunk "finished" and listed.
- On plugin load / service start: any leftover `.part` files are finalized the same way (data up to the last 1-s flush survives a crash).
- Persist `{recording, recordingId, options, nextSeq, startedAt}` so recording resumes after a process restart (continuing seq numbers, same recordingId), like tracking.
- Storage check every flush: if total bytes in the directory > maxStorageMb, stop with stopReason STORAGE_FULL. stopReason is cleared on the next startRecording.

### Methods

- `startRecording`: validate options (positive numbers, else INVALID_ARGUMENT; clamp accel/gyro/mag Hz to [1, 200], baro [1, 50], locationIntervalMs [500, 60000], chunkSeconds [10, 600], maxStorageMb [10, 10000]). Requires foreground location permission (PERMISSION_DENIED otherwise) because GPS is part of the recording and keeps the app alive in the background. recordingId = UUID v4.
- `listRecordingChunks({limit = 100})`: finished chunks by seq/creation order, from the .json metadata files.
- `getRecordingChunk({chunkId})`: read metadata + base64 of the .gz file; reject INVALID_ARGUMENT if unknown. Do this off the main thread.
- `ackRecordingChunk({chunkId})`: delete .gz + .json.
- `getRecordingStatus`: measuredHz from a rolling 5-s window of per-sensor sample counts kept in memory (omit when not recording).
- All file IO off the main/UI thread.

## v0.3.0: native upload to your own endpoint

Goal: data reaches the backend without depending on the WebView running. The backend (an ODC exposed REST API) authenticates the device and forwards data to external APIs.

### Configuration and secrets

- `configureUpload`: validate `endpointBaseUrl` is `https://` (else INVALID_ARGUMENT), deviceId and token non-empty, eventBatchSize clamp [1, 1000]. Strip trailing `/`.
- Token storage: Android: AES-256-GCM key in the AndroidKeyStore (alias `driving_sensors_upload`), ciphertext + IV in SharedPreferences `driving_sensors_upload`; iOS: Keychain generic password (service `nl.flexlogic.drivingsensors.upload`, account = deviceId) with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` so it is readable in the background after the first unlock. Never log the token; never return it.
- Non-secret config (base URL, deviceId, flags, counters, state) in SharedPreferences / UserDefaults; it survives restarts.
- `clearUpload`: delete token + config, state `disabled`. Data on the device is kept.

### HTTP

Common headers: `Authorization: Bearer <token>`, `X-Device-Id: <deviceId>`, `X-Plugin-Version: <PLUGIN_VERSION>`, `X-Platform: android|ios`, `Content-Type: application/json; charset=utf-8`. Timeouts: connect 15 s, request 60 s. No redirects followed to non-https.

- `POST {base}/chunks`, body: the exact `RecordingChunk` object returned by `getRecordingChunk` (incl. `data` base64, `sensorInfo`), plus `"deviceId"`. One chunk per request, oldest first.
- `POST {base}/events`, body: `{"deviceId": "...", "platform": "...", "pluginVersion": "...", "events": [TelemetryEvent, ...]}` with up to eventBatchSize events in ascending id order (same objects as `getBufferedEvents`, nulls omitted).

Response handling (per request):
- 2xx or 409 (duplicate): success. Chunk: delete it (same as ackRecordingChunk). Events: ack up to the highest id sent.
- 401 or 403: state `auth_failed`, stop all uploading until the next `configureUpload`. Data kept.
- 400, 413, 422 for a chunk: mark it rejected (rename `.json` → `.rejected.json`, keep the `.gz`; listRecordingChunks no longer returns it), continue with the next one. For an events batch: halve the batch size and retry once; if a single event is rejected, record lastError and go to backoff (never drop events silently).
- Anything else (5xx, timeouts, DNS, no network): backoff: 30 s, doubling to max 30 min, reset after a success. `nextAttemptAt` reflects it.
- Record lastHttpStatus / lastError / lastErrorAt / lastSuccessAt.

Order per run: events first (small, important), then chunks. A run ends when nothing is pending, on auth failure, or on an error leading to backoff. Only one run at a time per process.

### Scheduling

- Triggers: `configureUpload`, `uploadNow`, plugin load, a recording chunk being finalized, every 60 s while tracking or recording is active (if anything is pending), connectivity regained, and a periodic catch-up.
- Android: WorkManager. Unique one-time work `driving_sensors_upload` (ExistingWorkPolicy.KEEP, or APPEND_OR_REPLACE when a run is active so a new trigger isn't lost) with constraint NetworkType.CONNECTED or UNMETERED (wifiOnly); plus unique periodic work every 15 min (KEEP). The worker performs the run synchronously (HttpURLConnection; no new HTTP library). Backoff is handled by the plugin's own `nextAttemptAt` (the worker re-enqueues itself with an initial delay) rather than WorkManager's retry policy, so status stays accurate. Add the `androidx.work:work-runtime-ktx` dependency.
- iOS: URLSession (default configuration, `waitsForConnectivity = true`, `allowsExpensiveNetworkAccess`/`allowsConstrainedNetworkAccess` = !wifiOnly) on a dedicated serial queue; each run wrapped in `UIApplication.beginBackgroundTask`. While tracking/recording keeps the app alive in the background, runs happen on the triggers above; when the app is suspended, uploads resume at the next launch/foreground (document this limitation).

### Interplay with the app-side flow

- If `uploadEvents` is true the native uploader acks events; an app that also calls getBufferedEvents/ackEvents may see events disappear: use one of the two flows. Same for recording chunks and `uploadRecordings`.
- `getUploadStatus.pendingChunks/pendingEvents` count what is on the device right now.

### Heartbeat (v0.3.0)

- `startHeartbeat({intervalSec = 30})`: clamp intervalSec to [10, 3600]. Requires a configured upload (else INVALID_ARGUMENT "configureUpload first") and foreground location permission (PERMISSION_DENIED) because of the keep-alive mechanism below. Persist `{running, intervalSec, seq}` so it resumes after a process restart (seq continues).
- Every intervalSec: build a `HeartbeatPayload` and `POST {base}/heartbeat` with the common headers. Send directly from the native runtime (not WorkManager). Heartbeats are **not queued**: a failed beat is counted and recorded (lastError/lastHttpStatus) and the next one is sent on schedule; gaps on the server are the signal. The first beat is sent immediately on start.
- 2xx/409 = success. 401/403 sets the upload state to `auth_failed` (as for other uploads) and the heartbeat keeps running but skips sending until configureUpload is called again (counted as failed with lastError "auth_failed").
- Android: hosted by the existing foreground service: the service runs while tracking OR recording OR heartbeat is active; notification text reflects it ("Heartbeat active" when only the heartbeat runs). Timer on the service's handler thread; the HTTP call on a background executor. No sensors, no location requests.
- iOS: if neither tracking nor recording runs, start location updates on the shared CLLocationManager with `desiredAccuracy = kCLLocationAccuracyThreeKilometers`, `distanceFilter = 3000`, background settings as for tracking (guarded by UIBackgroundModes). Fixes are ignored (not recorded, not cached). Tracking/recording settings win while they run; stopping the heartbeat must not stop their location updates and vice versa. Timer: DispatchSourceTimer on a background queue; each send wrapped in a background task.
- Payload fields: processUptimeSec from process start (Android: `SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()`; iOS: `ProcessInfo.processInfo.systemUptime - <uptime captured at plugin class load>`). appState from the Activity/UIApplication state (Android: ProcessLifecycleOwner or a simple started-activities counter is fine; iOS: `UIApplication.shared.applicationState` read on main). batteryLevel/charging from BatteryManager / UIDevice (enable battery monitoring). network from ConnectivityManager / NWPathMonitor.
- `stopHeartbeat`: stop the timer, release the keep-alive (iOS: stop location updates if nothing else needs them; Android: stop the service if nothing else runs), clear persisted running flag.
- `getHeartbeatStatus`: counters since startHeartbeat.

## v0.3.1: resume after process stop and device reboot

No API changes. Persisted state (tracking, recording, heartbeat, upload config) already exists; this release makes resuming reliable and automatic where the OS allows it.

### Resume rules (both platforms)
- Every consumer (tracking, recording, heartbeat) resumes from its persisted state whenever the native runtime starts: plugin load, Android service (re)start, boot, iOS background relaunch. Recording resumes with the same recordingId and the next seq; leftover `.part` files are finalized first.
- Persisted state is only cleared by the matching stop method or by an explicit stopReason (STORAGE_FULL, PERMISSION_LOST, ERROR). A process kill, force-stop, service destroy, OEM "stop" button or `onTimeout` must NOT clear it. Audit every code path that clears persisted state and add tests proving that destroying/tearing down the service (without a stop call) keeps the state.
- Resume requires foreground location permission; without it the consumer stays persisted but idle (no stopReason) and resumes once permission is back and the runtime starts again.

### Android: boot
- Add a `BOOT_COMPLETED` (and `MY_PACKAGE_REPLACED`) BroadcastReceiver in the plugin manifest (`RECEIVE_BOOT_COMPLETED` permission, `exported="false"` is not allowed for system broadcasts: use `exported="true"` with no data, standard pattern). On receive: if any consumer is persisted AND fine location AND background location (`ACCESS_BACKGROUND_LOCATION`, API 29+) are granted, start the foreground service (it resumes everything). Without background location Android 14+ forbids starting a location foreground service from the background: then do not start, only schedule the upload worker, and log why.
- Wrap `startForegroundService` in try/catch for `ForegroundServiceStartNotAllowedException` (API 31+) everywhere it is called from a background context; on failure schedule the upload worker and retry the start on the next plugin load.
- Implement `Service.onTimeout(int, int)` (API 35) defensively: stopSelf without clearing state.
- Also re-ensure the periodic upload work on boot.

### iOS: relaunch
- While any consumer is persisted and authorization is `.authorizedAlways`, also call `startMonitoringSignificantLocationChanges()` on the shared CLLocationManager (stop it when no consumer remains). iOS then relaunches a terminated app (after swipe-away or reboot, once unlocked) in the background on a significant location change.
- On such a launch the plugin's `load()` runs when the Capacitor bridge is created; resume all persisted consumers there (existing `resumeIfNeeded`), including the heartbeat keep-alive. If the bridge is not created on background launch, document it: the plugin cannot fix that alone (needs an AppDelegate hook), so add a public static `DrivingSensors.resumeFromLaunch()` entry point that an app delegate can call, and note it in the README.
- Without "Always" nothing changes: resume on next app open.
