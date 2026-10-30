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
