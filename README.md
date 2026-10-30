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


### Type Aliases


#### PermissionState

<code>'prompt' | 'prompt-with-rationale' | 'granted' | 'denied'</code>


#### DrivingSensorsPermissionType

<code>'location' | 'backgroundLocation' | 'motion' | 'notifications'</code>


#### TelemetryEventType

<code>'LOCATION' | 'HARSH_BRAKE' | 'HARSH_ACCEL' | 'TRIP_START' | 'TRIP_END'</code>

</docgen-api>
