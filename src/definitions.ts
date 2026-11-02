import type { PermissionState } from '@capacitor/core';

export interface DrivingSensorsPlugin {
  /**
   * Check the current state of the permissions the plugin needs.
   */
  checkPermissions(): Promise<PermissionStatus>;

  /**
   * Request permissions. On Android, `backgroundLocation` can only be granted
   * after `location` is granted, so request them in two separate calls.
   * Omit `permissions` to request `location`, `motion` and `notifications`.
   */
  requestPermissions(options?: { permissions?: DrivingSensorsPermissionType[] }): Promise<PermissionStatus>;

  /**
   * Start background tracking. Idempotent: if tracking is already active,
   * resolves with the current session id and ignores the new options.
   */
  startTracking(options?: TrackingOptions): Promise<{ sessionId: string }>;

  /**
   * Stop tracking. Emits TRIP_END if a trip is in progress. Buffered events are kept.
   */
  stopTracking(): Promise<void>;

  /**
   * Current tracking state and number of events waiting in the native buffer.
   */
  getStatus(): Promise<TrackingStatus>;

  /**
   * Read buffered events in ascending id order. Does not remove them; call `ackEvents` afterwards.
   */
  getBufferedEvents(options?: { afterId?: number; limit?: number }): Promise<{ events: TelemetryEvent[] }>;

  /**
   * Delete all buffered events with `id <= upToId`.
   */
  ackEvents(options: { upToId: number }): Promise<void>;

  /**
   * Last readings the tracking engine received. Instant; never switches sensors on.
   * Location updates arrive whenever tracking is active; motion only while a trip is in progress.
   */
  getLastReadings(): Promise<SensorReadings>;

  /**
   * Take a fresh reading: one high-accuracy location fix and a short motion-sensor sample.
   * Works whether or not tracking is active. Requires foreground location permission
   * (rejects with `PERMISSION_DENIED`). If no fix arrives within `timeoutMs`, resolves with
   * `hasLocation: false`.
   */
  getCurrentReadings(options?: { timeoutMs?: number; motionSampleMs?: number }): Promise<SensorReadings>;

  /** Version of the native plugin code compiled into the app (not the JS bundle), plus the platform. */
  getPluginVersion(): Promise<{ version: string; platform: 'android' | 'ios' | 'web' }>;

  /**
   * Start raw sensor recording (debug / data-collection mode), independent of trip tracking.
   * Samples accelerometer, gyroscope, magnetometer, barometer and GPS continuously and writes
   * them to compressed chunk files on the device. Runs in the background like tracking.
   * Idempotent: if already recording, resolves with the current recording id and ignores options.
   */
  startRecording(options?: RecordingOptions): Promise<{ recordingId: string }>;

  /** Stop recording and finalize the current chunk. Finished chunks are kept until acked. */
  stopRecording(): Promise<void>;

  getRecordingStatus(): Promise<RecordingStatus>;

  /** Finished chunks waiting to be fetched, oldest first. The chunk being written is not listed. */
  listRecordingChunks(options?: { limit?: number }): Promise<{ chunks: RecordingChunkInfo[] }>;

  /** One finished chunk including its gzip+base64 CSV payload. Does not delete it. */
  getRecordingChunk(options: { chunkId: string }): Promise<RecordingChunk>;

  /** Delete a finished chunk after it has been delivered. Unknown ids resolve without error. */
  ackRecordingChunk(options: { chunkId: string }): Promise<void>;

  /**
   * Configure native upload to your own HTTPS endpoint (e.g. an ODC exposed REST API).
   * The token is stored in the Android Keystore / iOS Keychain and never returned to JS.
   * Replaces any previous configuration and clears an AUTH_FAILED state.
   */
  configureUpload(options: UploadConfig): Promise<void>;

  /** Remove the upload configuration and token (e.g. on logout). Pending data stays on the device. */
  clearUpload(): Promise<void>;

  getUploadStatus(): Promise<UploadStatus>;

  /** Ask for an upload attempt now (ignores backoff, still respects wifiOnly and AUTH_FAILED). */
  uploadNow(): Promise<void>;

  /**
   * Send a small heartbeat to POST {base}/heartbeat every `intervalSec` seconds, to verify that the
   * native background runtime keeps working. Needs `configureUpload` first. Logs no sensor data.
   * Android: runs in the foreground service (notification shown). iOS: keeps the app alive with
   * lowest-power location updates (nothing is recorded; the location indicator is shown).
   * Idempotent: if already running, the new interval is applied.
   */
  startHeartbeat(options?: { intervalSec?: number }): Promise<void>;

  stopHeartbeat(): Promise<void>;

  getHeartbeatStatus(): Promise<HeartbeatStatus>;
}

export interface LocationReading {
  /** Epoch ms of the fix. */
  ts: number;
  lat: number;
  lon: number;
  speedMps?: number;
  accuracyM?: number;
}

export interface MotionReading {
  /** Epoch ms of the (last) sample. */
  ts: number;
  /** Linear acceleration (gravity removed), m/s², device axes. */
  ax: number;
  ay: number;
  az: number;
  /** Gravity vector, m/s², device axes. */
  gx: number;
  gy: number;
  gz: number;
  /** Horizontal acceleration magnitude of this sample, m/s² (same projection as harsh detection). */
  horizontalMps2: number;
  /** Highest horizontal magnitude in the sampled window. `getCurrentReadings` only. */
  peakHorizontalMps2?: number;
  /** Number of samples in the window. `getCurrentReadings` only. */
  samples?: number;
}

export interface SensorReadings {
  hasLocation: boolean;
  location?: LocationReading;
  hasMotion: boolean;
  motion?: MotionReading;
}

export type DrivingSensorsPermissionType = 'location' | 'backgroundLocation' | 'motion' | 'notifications';

export interface PermissionStatus {
  /** Foreground (when-in-use) location. */
  location: PermissionState;
  /** Background location (Android ACCESS_BACKGROUND_LOCATION, iOS "Always"). */
  backgroundLocation: PermissionState;
  /** Motion sensors. Android has no runtime permission for these and always reports `granted`. */
  motion: PermissionState;
  /** Notifications (Android 13+, needed for the foreground-service notification). Always `granted` on iOS. */
  notifications: PermissionState;
}

export interface TrackingOptions {
  /** Horizontal deceleration threshold for HARSH_BRAKE, m/s², positive. Default 3.0. */
  harshBrakeMps2?: number;
  /** Horizontal acceleration threshold for HARSH_ACCEL, m/s², positive. Default 3.0. */
  harshAccelMps2?: number;
  /** Location interval while a trip is in progress, ms. Default 1000. */
  locationIntervalMs?: number;
  /** Accelerometer sample rate while a trip is in progress, Hz. Default 50. */
  accelSampleHz?: number;
  /** Speed that starts a trip, m/s. Default 4.2 (~15 km/h). */
  tripStartSpeedMps?: number;
  /** Time below `tripStartSpeedMps` after which the trip ends, ms. Default 180000. */
  tripEndIdleMs?: number;
  /** Minimum time between two harsh events, ms. Default 3000. */
  minEventGapMs?: number;
  /** Android foreground-service notification title. Default "Trip recording active". */
  notificationTitle?: string;
  /** Android foreground-service notification text. Default "Recording driving data". */
  notificationText?: string;
}

export interface TrackingStatus {
  /** Tracking was requested (persisted state). See `serviceRunning` for whether it actually runs. */
  active: boolean;
  /** The native tracking engine is running right now (Android foreground service / iOS location updates). */
  serviceRunning: boolean;
  /** Last location fix the tracking engine received, if any since it started. */
  lastLocation?: LocationReading;
  sessionId?: string;
  inTrip: boolean;
  /** Number of events currently in the native buffer. */
  buffered: number;
}

export type TelemetryEventType = 'LOCATION' | 'HARSH_BRAKE' | 'HARSH_ACCEL' | 'TRIP_START' | 'TRIP_END';

export interface TelemetryEvent {
  /** Monotonically increasing, assigned by the native buffer. */
  id: number;
  /** Epoch milliseconds, native timestamp. */
  ts: number;
  type: TelemetryEventType;
  sessionId: string;
  lat?: number;
  lon?: number;
  speedMps?: number;
  accuracyM?: number;
  /** Peak horizontal acceleration magnitude for HARSH_* events, m/s², positive. */
  peakMps2?: number;
}

export type RecordingSensor = 'acc' | 'gyr' | 'mag' | 'bar' | 'loc';

export interface RecordingOptions {
  /** Accelerometer rate, Hz. Default 100. */
  accelHz?: number;
  /** Gyroscope rate, Hz. Default 100. */
  gyroHz?: number;
  /** Magnetometer rate, Hz. Default 50. */
  magHz?: number;
  /** Barometer rate, Hz. Default 10 (iOS delivers ~1 Hz regardless). */
  baroHz?: number;
  /** GPS interval, ms. Default 1000. */
  locationIntervalMs?: number;
  /** Chunk length, seconds. Default 60. */
  chunkSeconds?: number;
  /** Stop recording (stopReason STORAGE_FULL) when finished + current chunks exceed this, MB. Default 500. */
  maxStorageMb?: number;
  /** Android foreground-service notification title. Default "Sensor recording active". */
  notificationTitle?: string;
  /** Android foreground-service notification text. Default "Recording raw sensor data". */
  notificationText?: string;
}

export interface RecordingSensorStats {
  acc: number;
  gyr: number;
  mag: number;
  bar: number;
  loc: number;
}

export interface RecordingStatus {
  recording: boolean;
  recordingId?: string;
  /** Epoch ms when the current recording started. */
  startedAt?: number;
  /** Finished chunks waiting to be fetched. */
  chunksPending: number;
  /** Bytes on disk for finished + current chunks. */
  bytesPending: number;
  /** Which sensors exist on this device. */
  available: { acc: boolean; gyr: boolean; mag: boolean; bar: boolean; loc: boolean };
  /** Samples per sensor in the last ~5 seconds divided by the window length (actual rate). */
  measuredHz?: RecordingSensorStats;
  /** Set when recording stopped by itself: STORAGE_FULL, PERMISSION_LOST, or ERROR. */
  stopReason?: string;
}

export interface RecordingChunkInfo {
  /** `<recordingId>-<seq>` with seq zero-padded to 6 digits. */
  chunkId: string;
  recordingId: string;
  seq: number;
  /** Epoch ms of the first and last sample in the chunk. */
  startTs: number;
  endTs: number;
  /** Size of the gzip payload, bytes. */
  bytes: number;
  /** Samples per sensor in this chunk. */
  counts: RecordingSensorStats;
}

export interface RecordingChunk extends RecordingChunkInfo {
  /** Always "csv-v1": see SPEC.md "Recording CSV format". */
  format: 'csv-v1';
  /** Always "gzip+base64". */
  encoding: 'gzip+base64';
  platform: 'android' | 'ios';
  deviceModel: string;
  osVersion: string;
  pluginVersion: string;
  /** Sensor descriptions, e.g. vendor/name and whether calibrated. */
  sensorInfo: Record<string, string>;
  /** Base64 of the gzip-compressed CSV. */
  data: string;
}

export interface UploadConfig {
  /** HTTPS base URL, e.g. "https://<tenant>-dev.outsystems.app/DriveMe/rest/Telemetry". No trailing slash needed. */
  endpointBaseUrl: string;
  /** Device id issued by your server at registration. Sent as header X-Device-Id. */
  deviceId: string;
  /** Secret device token issued by your server. Sent as "Authorization: Bearer <token>". */
  token: string;
  /** Upload finished recording chunks to POST {base}/chunks. Default true. */
  uploadRecordings?: boolean;
  /** Upload trip events from the buffer to POST {base}/events and ack them natively. Default true. */
  uploadEvents?: boolean;
  /** Only upload on unmetered networks (Wi-Fi). Default false. */
  wifiOnly?: boolean;
  /** Max events per POST /events. Default 500, max 1000. */
  eventBatchSize?: number;
}

export type UploadState = 'disabled' | 'idle' | 'uploading' | 'waiting_network' | 'backoff' | 'auth_failed';

export interface UploadStatus {
  configured: boolean;
  state: UploadState;
  endpointBaseUrl?: string;
  deviceId?: string;
  uploadRecordings?: boolean;
  uploadEvents?: boolean;
  wifiOnly?: boolean;
  /** Finished recording chunks still on the device (excluding rejected ones). */
  pendingChunks: number;
  /** Trip events still in the buffer. */
  pendingEvents: number;
  /** Chunks the server refused with 400/413/422; kept on the device, not retried. */
  rejectedChunks: number;
  /** Counters since the last configureUpload. */
  uploadedChunks: number;
  uploadedEvents: number;
  lastSuccessAt?: number;
  lastErrorAt?: number;
  lastError?: string;
  lastHttpStatus?: number;
  /** Epoch ms of the next scheduled attempt while in backoff. */
  nextAttemptAt?: number;
}

export interface HeartbeatStatus {
  running: boolean;
  intervalSec?: number;
  /** Heartbeats attempted / succeeded since startHeartbeat. */
  sent: number;
  succeeded: number;
  lastSentAt?: number;
  lastSuccessAt?: number;
  lastError?: string;
  lastHttpStatus?: number;
}

/** Body of POST {base}/heartbeat. */
export interface HeartbeatPayload {
  deviceId: string;
  platform: 'android' | 'ios';
  pluginVersion: string;
  /** Epoch ms when the heartbeat was created on the device. */
  ts: number;
  /** Increments per heartbeat since startHeartbeat (starts at 1); gaps on the server = missed beats. */
  seq: number;
  intervalSec: number;
  /** Seconds since the native runtime (process) started. Resets if the OS killed and restarted it. */
  processUptimeSec: number;
  appState: 'foreground' | 'background';
  /** 0..100, or -1 when unknown. */
  batteryLevel: number;
  charging: boolean;
  /** "wifi", "cellular", "other" or "none". */
  network: string;
  tracking: boolean;
  inTrip: boolean;
  recording: boolean;
  pendingEvents: number;
  pendingChunks: number;
  uploadState: UploadState;
}
