# driving-sensors

Background driving telemetry (speed, harsh accel/brake, position) buffered natively for OutSystems ODC apps

## Install

To use npm

```bash
npm install driving-sensors
````

To use yarn

```bash
yarn add driving-sensors
```

Sync native files

```bash
npx cap sync
```

## iOS background relaunch (v0.3.1)

Tracking, recording and the heartbeat resume from their persisted state whenever the plugin loads. With "Always" location authorization the plugin also monitors significant location changes while any of them is persisted, so iOS relaunches a terminated app (swipe-away, or after a reboot once the device is unlocked) in the background on the next significant move. Without "Always", nothing is relaunched: everything resumes the next time the user opens the app.

On a background relaunch the plugin resumes only if the Capacitor bridge (and so the plugin's `load()`) is created. If your host app does not create the bridge on a background launch, call `DrivingSensors.resumeFromLaunch()` from the app delegate. It is idempotent, so it is safe alongside the normal plugin load:

```swift
import DrivingSensorsPlugin

func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    if launchOptions?[.location] != nil {
        DrivingSensors.resumeFromLaunch()
    }
    return true
}
```

ODC/MABS-generated apps may not offer an AppDelegate hook. Whether the bridge is created on a background relaunch there is unverified: test on a device (swipe away the app while tracking with "Always", then drive or move) before relying on it.

## API

<docgen-index>

* [`checkPermissions()`](#checkpermissions)
* [`requestPermissions(...)`](#requestpermissions)
* [`startTracking(...)`](#starttracking)
* [`stopTracking()`](#stoptracking)
* [`getStatus()`](#getstatus)
* [`getBufferedEvents(...)`](#getbufferedevents)
* [`ackEvents(...)`](#ackevents)
* [`getLastReadings()`](#getlastreadings)
* [`getCurrentReadings(...)`](#getcurrentreadings)
* [`getPluginVersion()`](#getpluginversion)
* [`startRecording(...)`](#startrecording)
* [`stopRecording()`](#stoprecording)
* [`getRecordingStatus()`](#getrecordingstatus)
* [`listRecordingChunks(...)`](#listrecordingchunks)
* [`getRecordingChunk(...)`](#getrecordingchunk)
* [`ackRecordingChunk(...)`](#ackrecordingchunk)
* [`configureUpload(...)`](#configureupload)
* [`clearUpload()`](#clearupload)
* [`getUploadStatus()`](#getuploadstatus)
* [`uploadNow()`](#uploadnow)
* [`startHeartbeat(...)`](#startheartbeat)
* [`stopHeartbeat()`](#stopheartbeat)
* [`getHeartbeatStatus()`](#getheartbeatstatus)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

### checkPermissions()

```typescript
checkPermissions() => Promise<PermissionStatus>
```

Check the current state of the permissions the plugin needs.

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### requestPermissions(...)

```typescript
requestPermissions(options?: { permissions?: DrivingSensorsPermissionType[] | undefined; } | undefined) => Promise<PermissionStatus>
```

Request permissions. On Android, `backgroundLocation` can only be granted
after `location` is granted, so request them in two separate calls.
Omit `permissions` to request `location`, `motion` and `notifications`.

| Param         | Type                                                           |
| ------------- | -------------------------------------------------------------- |
| **`options`** | <code>{ permissions?: DrivingSensorsPermissionType[]; }</code> |

**Returns:** <code>Promise&lt;<a href="#permissionstatus">PermissionStatus</a>&gt;</code>

--------------------


### startTracking(...)

```typescript
startTracking(options?: TrackingOptions | undefined) => Promise<{ sessionId: string; }>
```

Start background tracking. Idempotent: if tracking is already active,
resolves with the current session id and ignores the new options.

| Param         | Type                                                        |
| ------------- | ----------------------------------------------------------- |
| **`options`** | <code><a href="#trackingoptions">TrackingOptions</a></code> |

**Returns:** <code>Promise&lt;{ sessionId: string; }&gt;</code>

--------------------


### stopTracking()

```typescript
stopTracking() => Promise<void>
```

Stop tracking. Emits TRIP_END if a trip is in progress. Buffered events are kept.

--------------------


### getStatus()

```typescript
getStatus() => Promise<TrackingStatus>
```

Current tracking state and number of events waiting in the native buffer.

**Returns:** <code>Promise&lt;<a href="#trackingstatus">TrackingStatus</a>&gt;</code>

--------------------


### getBufferedEvents(...)

```typescript
getBufferedEvents(options?: { afterId?: number | undefined; limit?: number | undefined; } | undefined) => Promise<{ events: TelemetryEvent[]; }>
```

Read buffered events in ascending id order. Does not remove them; call `ackEvents` afterwards.

| Param         | Type                                               |
| ------------- | -------------------------------------------------- |
| **`options`** | <code>{ afterId?: number; limit?: number; }</code> |

**Returns:** <code>Promise&lt;{ events: TelemetryEvent[]; }&gt;</code>

--------------------


### ackEvents(...)

```typescript
ackEvents(options: { upToId: number; }) => Promise<void>
```

Delete all buffered events with `id &lt;= upToId`.

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ upToId: number; }</code> |

--------------------


### getLastReadings()

```typescript
getLastReadings() => Promise<SensorReadings>
```

Last readings the tracking engine received. Instant; never switches sensors on.
Location updates arrive whenever tracking is active; motion only while a trip is in progress.

**Returns:** <code>Promise&lt;<a href="#sensorreadings">SensorReadings</a>&gt;</code>

--------------------


### getCurrentReadings(...)

```typescript
getCurrentReadings(options?: { timeoutMs?: number | undefined; motionSampleMs?: number | undefined; } | undefined) => Promise<SensorReadings>
```

Take a fresh reading: one high-accuracy location fix and a short motion-sensor sample.
Works whether or not tracking is active. Requires foreground location permission
(rejects with `PERMISSION_DENIED`). If no fix arrives within `timeoutMs`, resolves with
`hasLocation: false`.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code>{ timeoutMs?: number; motionSampleMs?: number; }</code> |

**Returns:** <code>Promise&lt;<a href="#sensorreadings">SensorReadings</a>&gt;</code>

--------------------


### getPluginVersion()

```typescript
getPluginVersion() => Promise<{ version: string; platform: 'android' | 'ios' | 'web'; }>
```

Version of the native plugin code compiled into the app (not the JS bundle), plus the platform.

**Returns:** <code>Promise&lt;{ version: string; platform: 'android' | 'ios' | 'web'; }&gt;</code>

--------------------


### startRecording(...)

```typescript
startRecording(options?: RecordingOptions | undefined) => Promise<{ recordingId: string; }>
```

Start raw sensor recording (debug / data-collection mode), independent of trip tracking.
Samples accelerometer, gyroscope, magnetometer, barometer and GPS continuously and writes
them to compressed chunk files on the device. Runs in the background like tracking.
Idempotent: if already recording, resolves with the current recording id and ignores options.

| Param         | Type                                                          |
| ------------- | ------------------------------------------------------------- |
| **`options`** | <code><a href="#recordingoptions">RecordingOptions</a></code> |

**Returns:** <code>Promise&lt;{ recordingId: string; }&gt;</code>

--------------------


### stopRecording()

```typescript
stopRecording() => Promise<void>
```

Stop recording and finalize the current chunk. Finished chunks are kept until acked.

--------------------


### getRecordingStatus()

```typescript
getRecordingStatus() => Promise<RecordingStatus>
```

**Returns:** <code>Promise&lt;<a href="#recordingstatus">RecordingStatus</a>&gt;</code>

--------------------


### listRecordingChunks(...)

```typescript
listRecordingChunks(options?: { limit?: number | undefined; } | undefined) => Promise<{ chunks: RecordingChunkInfo[]; }>
```

Finished chunks waiting to be fetched, oldest first. The chunk being written is not listed.

| Param         | Type                             |
| ------------- | -------------------------------- |
| **`options`** | <code>{ limit?: number; }</code> |

**Returns:** <code>Promise&lt;{ chunks: RecordingChunkInfo[]; }&gt;</code>

--------------------


### getRecordingChunk(...)

```typescript
getRecordingChunk(options: { chunkId: string; }) => Promise<RecordingChunk>
```

One finished chunk including its gzip+base64 CSV payload. Does not delete it.

| Param         | Type                              |
| ------------- | --------------------------------- |
| **`options`** | <code>{ chunkId: string; }</code> |

**Returns:** <code>Promise&lt;<a href="#recordingchunk">RecordingChunk</a>&gt;</code>

--------------------


### ackRecordingChunk(...)

```typescript
ackRecordingChunk(options: { chunkId: string; }) => Promise<void>
```

Delete a finished chunk after it has been delivered. Unknown ids resolve without error.

| Param         | Type                              |
| ------------- | --------------------------------- |
| **`options`** | <code>{ chunkId: string; }</code> |

--------------------


### configureUpload(...)

```typescript
configureUpload(options: UploadConfig) => Promise<void>
```

Configure native upload to your own HTTPS endpoint (e.g. an ODC exposed REST API).
The token is stored in the Android Keystore / iOS Keychain and never returned to JS.
Replaces any previous configuration and clears an AUTH_FAILED state.

| Param         | Type                                                  |
| ------------- | ----------------------------------------------------- |
| **`options`** | <code><a href="#uploadconfig">UploadConfig</a></code> |

--------------------


### clearUpload()

```typescript
clearUpload() => Promise<void>
```

Remove the upload configuration and token (e.g. on logout). Pending data stays on the device.

--------------------


### getUploadStatus()

```typescript
getUploadStatus() => Promise<UploadStatus>
```

**Returns:** <code>Promise&lt;<a href="#uploadstatus">UploadStatus</a>&gt;</code>

--------------------


### uploadNow()

```typescript
uploadNow() => Promise<void>
```

Ask for an upload attempt now (ignores backoff, still respects wifiOnly and AUTH_FAILED).

--------------------


### startHeartbeat(...)

```typescript
startHeartbeat(options?: { intervalSec?: number | undefined; } | undefined) => Promise<void>
```

Send a small heartbeat to POST {base}/heartbeat every `intervalSec` seconds, to verify that the
native background runtime keeps working. Needs `configureUpload` first. Logs no sensor data.
Android: runs in the foreground service (notification shown). iOS: keeps the app alive with
lowest-power location updates (nothing is recorded; the location indicator is shown).
Idempotent: if already running, the new interval is applied.

| Param         | Type                                   |
| ------------- | -------------------------------------- |
| **`options`** | <code>{ intervalSec?: number; }</code> |

--------------------


### stopHeartbeat()

```typescript
stopHeartbeat() => Promise<void>
```

--------------------


### getHeartbeatStatus()

```typescript
getHeartbeatStatus() => Promise<HeartbeatStatus>
```

**Returns:** <code>Promise&lt;<a href="#heartbeatstatus">HeartbeatStatus</a>&gt;</code>

--------------------


### Interfaces


#### PermissionStatus

| Prop                     | Type                                                        | Description                                                                                           |
| ------------------------ | ----------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| **`location`**           | <code><a href="#permissionstate">PermissionState</a></code> | Foreground (when-in-use) location.                                                                    |
| **`backgroundLocation`** | <code><a href="#permissionstate">PermissionState</a></code> | Background location (Android ACCESS_BACKGROUND_LOCATION, iOS "Always").                               |
| **`motion`**             | <code><a href="#permissionstate">PermissionState</a></code> | Motion sensors. Android has no runtime permission for these and always reports `granted`.             |
| **`notifications`**      | <code><a href="#permissionstate">PermissionState</a></code> | Notifications (Android 13+, needed for the foreground-service notification). Always `granted` on iOS. |


#### TrackingOptions

| Prop                     | Type                | Description                                                                     |
| ------------------------ | ------------------- | ------------------------------------------------------------------------------- |
| **`harshBrakeMps2`**     | <code>number</code> | Horizontal deceleration threshold for HARSH_BRAKE, m/s², positive. Default 3.0. |
| **`harshAccelMps2`**     | <code>number</code> | Horizontal acceleration threshold for HARSH_ACCEL, m/s², positive. Default 3.0. |
| **`locationIntervalMs`** | <code>number</code> | Location interval while a trip is in progress, ms. Default 1000.                |
| **`accelSampleHz`**      | <code>number</code> | Accelerometer sample rate while a trip is in progress, Hz. Default 50.          |
| **`tripStartSpeedMps`**  | <code>number</code> | Speed that starts a trip, m/s. Default 4.2 (~15 km/h).                          |
| **`tripEndIdleMs`**      | <code>number</code> | Time below `tripStartSpeedMps` after which the trip ends, ms. Default 180000.   |
| **`minEventGapMs`**      | <code>number</code> | Minimum time between two harsh events, ms. Default 3000.                        |
| **`notificationTitle`**  | <code>string</code> | Android foreground-service notification title. Default "Trip recording active". |
| **`notificationText`**   | <code>string</code> | Android foreground-service notification text. Default "Recording driving data". |


#### TrackingStatus

| Prop                 | Type                                                        | Description                                                                                          |
| -------------------- | ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| **`active`**         | <code>boolean</code>                                        | Tracking was requested (persisted state). See `serviceRunning` for whether it actually runs.         |
| **`serviceRunning`** | <code>boolean</code>                                        | The native tracking engine is running right now (Android foreground service / iOS location updates). |
| **`lastLocation`**   | <code><a href="#locationreading">LocationReading</a></code> | Last location fix the tracking engine received, if any since it started.                             |
| **`sessionId`**      | <code>string</code>                                         |                                                                                                      |
| **`inTrip`**         | <code>boolean</code>                                        |                                                                                                      |
| **`buffered`**       | <code>number</code>                                         | Number of events currently in the native buffer.                                                     |


#### LocationReading

| Prop            | Type                | Description          |
| --------------- | ------------------- | -------------------- |
| **`ts`**        | <code>number</code> | Epoch ms of the fix. |
| **`lat`**       | <code>number</code> |                      |
| **`lon`**       | <code>number</code> |                      |
| **`speedMps`**  | <code>number</code> |                      |
| **`accuracyM`** | <code>number</code> |                      |


#### TelemetryEvent

| Prop            | Type                                                              | Description                                                                |
| --------------- | ----------------------------------------------------------------- | -------------------------------------------------------------------------- |
| **`id`**        | <code>number</code>                                               | Monotonically increasing, assigned by the native buffer.                   |
| **`ts`**        | <code>number</code>                                               | Epoch milliseconds, native timestamp.                                      |
| **`type`**      | <code><a href="#telemetryeventtype">TelemetryEventType</a></code> |                                                                            |
| **`sessionId`** | <code>string</code>                                               |                                                                            |
| **`lat`**       | <code>number</code>                                               |                                                                            |
| **`lon`**       | <code>number</code>                                               |                                                                            |
| **`speedMps`**  | <code>number</code>                                               |                                                                            |
| **`accuracyM`** | <code>number</code>                                               |                                                                            |
| **`peakMps2`**  | <code>number</code>                                               | Peak horizontal acceleration magnitude for HARSH_* events, m/s², positive. |


#### SensorReadings

| Prop              | Type                                                        |
| ----------------- | ----------------------------------------------------------- |
| **`hasLocation`** | <code>boolean</code>                                        |
| **`location`**    | <code><a href="#locationreading">LocationReading</a></code> |
| **`hasMotion`**   | <code>boolean</code>                                        |
| **`motion`**      | <code><a href="#motionreading">MotionReading</a></code>     |


#### MotionReading

| Prop                     | Type                | Description                                                                                  |
| ------------------------ | ------------------- | -------------------------------------------------------------------------------------------- |
| **`ts`**                 | <code>number</code> | Epoch ms of the (last) sample.                                                               |
| **`ax`**                 | <code>number</code> | Linear acceleration (gravity removed), m/s², device axes.                                    |
| **`ay`**                 | <code>number</code> |                                                                                              |
| **`az`**                 | <code>number</code> |                                                                                              |
| **`gx`**                 | <code>number</code> | Gravity vector, m/s², device axes.                                                           |
| **`gy`**                 | <code>number</code> |                                                                                              |
| **`gz`**                 | <code>number</code> |                                                                                              |
| **`horizontalMps2`**     | <code>number</code> | Horizontal acceleration magnitude of this sample, m/s² (same projection as harsh detection). |
| **`peakHorizontalMps2`** | <code>number</code> | Highest horizontal magnitude in the sampled window. `getCurrentReadings` only.               |
| **`samples`**            | <code>number</code> | Number of samples in the window. `getCurrentReadings` only.                                  |


#### RecordingOptions

| Prop                     | Type                | Description                                                                                           |
| ------------------------ | ------------------- | ----------------------------------------------------------------------------------------------------- |
| **`accelHz`**            | <code>number</code> | Accelerometer rate, Hz. Default 100.                                                                  |
| **`gyroHz`**             | <code>number</code> | Gyroscope rate, Hz. Default 100.                                                                      |
| **`magHz`**              | <code>number</code> | Magnetometer rate, Hz. Default 50.                                                                    |
| **`baroHz`**             | <code>number</code> | Barometer rate, Hz. Default 10 (iOS delivers ~1 Hz regardless).                                       |
| **`locationIntervalMs`** | <code>number</code> | GPS interval, ms. Default 1000.                                                                       |
| **`chunkSeconds`**       | <code>number</code> | Chunk length, seconds. Default 60.                                                                    |
| **`maxStorageMb`**       | <code>number</code> | Stop recording (stopReason STORAGE_FULL) when finished + current chunks exceed this, MB. Default 500. |
| **`notificationTitle`**  | <code>string</code> | Android foreground-service notification title. Default "Sensor recording active".                     |
| **`notificationText`**   | <code>string</code> | Android foreground-service notification text. Default "Recording raw sensor data".                    |


#### RecordingStatus

| Prop                | Type                                                                                   | Description                                                                           |
| ------------------- | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| **`recording`**     | <code>boolean</code>                                                                   |                                                                                       |
| **`recordingId`**   | <code>string</code>                                                                    |                                                                                       |
| **`startedAt`**     | <code>number</code>                                                                    | Epoch ms when the current recording started.                                          |
| **`chunksPending`** | <code>number</code>                                                                    | Finished chunks waiting to be fetched.                                                |
| **`bytesPending`**  | <code>number</code>                                                                    | Bytes on disk for finished + current chunks.                                          |
| **`available`**     | <code>{ acc: boolean; gyr: boolean; mag: boolean; bar: boolean; loc: boolean; }</code> | Which sensors exist on this device.                                                   |
| **`measuredHz`**    | <code><a href="#recordingsensorstats">RecordingSensorStats</a></code>                  | Samples per sensor in the last ~5 seconds divided by the window length (actual rate). |
| **`stopReason`**    | <code>string</code>                                                                    | Set when recording stopped by itself: STORAGE_FULL, PERMISSION_LOST, or ERROR.        |


#### RecordingSensorStats

| Prop      | Type                |
| --------- | ------------------- |
| **`acc`** | <code>number</code> |
| **`gyr`** | <code>number</code> |
| **`mag`** | <code>number</code> |
| **`bar`** | <code>number</code> |
| **`loc`** | <code>number</code> |


#### RecordingChunkInfo

| Prop              | Type                                                                  | Description                                                         |
| ----------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------- |
| **`chunkId`**     | <code>string</code>                                                   | `&lt;recordingId&gt;-&lt;seq&gt;` with seq zero-padded to 6 digits. |
| **`recordingId`** | <code>string</code>                                                   |                                                                     |
| **`seq`**         | <code>number</code>                                                   |                                                                     |
| **`startTs`**     | <code>number</code>                                                   | Epoch ms of the first and last sample in the chunk.                 |
| **`endTs`**       | <code>number</code>                                                   |                                                                     |
| **`bytes`**       | <code>number</code>                                                   | Size of the gzip payload, bytes.                                    |
| **`counts`**      | <code><a href="#recordingsensorstats">RecordingSensorStats</a></code> | Samples per sensor in this chunk.                                   |


#### RecordingChunk

| Prop                | Type                                                            | Description                                                   |
| ------------------- | --------------------------------------------------------------- | ------------------------------------------------------------- |
| **`format`**        | <code>'csv-v1'</code>                                           | Always "csv-v1": see SPEC.md "Recording CSV format".          |
| **`encoding`**      | <code>'gzip+base64'</code>                                      | Always "gzip+base64".                                         |
| **`platform`**      | <code>'android' \| 'ios'</code>                                 |                                                               |
| **`deviceModel`**   | <code>string</code>                                             |                                                               |
| **`osVersion`**     | <code>string</code>                                             |                                                               |
| **`pluginVersion`** | <code>string</code>                                             |                                                               |
| **`sensorInfo`**    | <code><a href="#record">Record</a>&lt;string, string&gt;</code> | Sensor descriptions, e.g. vendor/name and whether calibrated. |
| **`data`**          | <code>string</code>                                             | Base64 of the gzip-compressed CSV.                            |


#### UploadConfig

| Prop                   | Type                 | Description                                                                                                        |
| ---------------------- | -------------------- | ------------------------------------------------------------------------------------------------------------------ |
| **`endpointBaseUrl`**  | <code>string</code>  | HTTPS base URL, e.g. "https://&lt;tenant&gt;-dev.outsystems.app/DriveMe/rest/Telemetry". No trailing slash needed. |
| **`deviceId`**         | <code>string</code>  | Device id issued by your server at registration. Sent as header X-Device-Id.                                       |
| **`token`**            | <code>string</code>  | Secret device token issued by your server. Sent as "Authorization: Bearer &lt;token&gt;".                          |
| **`uploadRecordings`** | <code>boolean</code> | Upload finished recording chunks to POST {base}/chunks. Default true.                                              |
| **`uploadEvents`**     | <code>boolean</code> | Upload trip events from the buffer to POST {base}/events and ack them natively. Default true.                      |
| **`wifiOnly`**         | <code>boolean</code> | Only upload on unmetered networks (Wi-Fi). Default false.                                                          |
| **`eventBatchSize`**   | <code>number</code>  | Max events per POST /events. Default 500, max 1000.                                                                |


#### UploadStatus

| Prop                   | Type                                                | Description                                                                  |
| ---------------------- | --------------------------------------------------- | ---------------------------------------------------------------------------- |
| **`configured`**       | <code>boolean</code>                                |                                                                              |
| **`state`**            | <code><a href="#uploadstate">UploadState</a></code> |                                                                              |
| **`endpointBaseUrl`**  | <code>string</code>                                 |                                                                              |
| **`deviceId`**         | <code>string</code>                                 |                                                                              |
| **`uploadRecordings`** | <code>boolean</code>                                |                                                                              |
| **`uploadEvents`**     | <code>boolean</code>                                |                                                                              |
| **`wifiOnly`**         | <code>boolean</code>                                |                                                                              |
| **`pendingChunks`**    | <code>number</code>                                 | Finished recording chunks still on the device (excluding rejected ones).     |
| **`pendingEvents`**    | <code>number</code>                                 | Trip events still in the buffer.                                             |
| **`rejectedChunks`**   | <code>number</code>                                 | Chunks the server refused with 400/413/422; kept on the device, not retried. |
| **`uploadedChunks`**   | <code>number</code>                                 | Counters since the last configureUpload.                                     |
| **`uploadedEvents`**   | <code>number</code>                                 |                                                                              |
| **`lastSuccessAt`**    | <code>number</code>                                 |                                                                              |
| **`lastErrorAt`**      | <code>number</code>                                 |                                                                              |
| **`lastError`**        | <code>string</code>                                 |                                                                              |
| **`lastHttpStatus`**   | <code>number</code>                                 |                                                                              |
| **`nextAttemptAt`**    | <code>number</code>                                 | Epoch ms of the next scheduled attempt while in backoff.                     |


#### HeartbeatStatus

| Prop                 | Type                 | Description                                            |
| -------------------- | -------------------- | ------------------------------------------------------ |
| **`running`**        | <code>boolean</code> |                                                        |
| **`intervalSec`**    | <code>number</code>  |                                                        |
| **`sent`**           | <code>number</code>  | Heartbeats attempted / succeeded since startHeartbeat. |
| **`succeeded`**      | <code>number</code>  |                                                        |
| **`lastSentAt`**     | <code>number</code>  |                                                        |
| **`lastSuccessAt`**  | <code>number</code>  |                                                        |
| **`lastError`**      | <code>string</code>  |                                                        |
| **`lastHttpStatus`** | <code>number</code>  |                                                        |


### Type Aliases


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>


#### DrivingSensorsPermissionType

<code>'location' | 'backgroundLocation' | 'motion' | 'notifications'</code>


#### TelemetryEventType

<code>'LOCATION' | 'HARSH_BRAKE' | 'HARSH_ACCEL' | 'TRIP_START' | 'TRIP_END'</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>


#### UploadState

<code>'disabled' | 'idle' | 'uploading' | 'waiting_network' | 'backoff' | 'auth_failed'</code>

</docgen-api>
