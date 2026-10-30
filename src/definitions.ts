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
  active: boolean;
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
