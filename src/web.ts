import { WebPlugin } from '@capacitor/core';

import type {
  DrivingSensorsPlugin,
  HeartbeatStatus,
  RecordingChunk,
  RecordingChunkInfo,
  RecordingStatus,
  UploadStatus,
  PermissionStatus,
  SensorReadings,
  TelemetryEvent,
  TrackingStatus,
} from './definitions';

// Kept in sync with package.json by scripts/sync-version.mjs. Do not edit by hand.
const PLUGIN_VERSION = '0.2.0';

export class DrivingSensorsWeb extends WebPlugin implements DrivingSensorsPlugin {
  async checkPermissions(): Promise<PermissionStatus> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async requestPermissions(): Promise<PermissionStatus> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async startTracking(): Promise<{ sessionId: string }> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async stopTracking(): Promise<void> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async getStatus(): Promise<TrackingStatus> {
    return { active: false, serviceRunning: false, inTrip: false, buffered: 0 };
  }

  async getBufferedEvents(): Promise<{ events: TelemetryEvent[] }> {
    return { events: [] };
  }

  async ackEvents(): Promise<void> {
    return;
  }

  async getLastReadings(): Promise<SensorReadings> {
    return { hasLocation: false, hasMotion: false };
  }

  async getCurrentReadings(): Promise<SensorReadings> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async getPluginVersion(): Promise<{ version: string; platform: 'android' | 'ios' | 'web' }> {
    return { version: PLUGIN_VERSION, platform: 'web' };
  }

  async startRecording(): Promise<{ recordingId: string }> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async stopRecording(): Promise<void> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async getRecordingStatus(): Promise<RecordingStatus> {
    return {
      recording: false,
      chunksPending: 0,
      bytesPending: 0,
      available: { acc: false, gyr: false, mag: false, bar: false, loc: false },
    };
  }

  async listRecordingChunks(): Promise<{ chunks: RecordingChunkInfo[] }> {
    return { chunks: [] };
  }

  async getRecordingChunk(): Promise<RecordingChunk> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async ackRecordingChunk(): Promise<void> {
    return;
  }

  async configureUpload(): Promise<void> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async clearUpload(): Promise<void> {
    return;
  }

  async getUploadStatus(): Promise<UploadStatus> {
    return {
      configured: false,
      state: 'disabled',
      pendingChunks: 0,
      pendingEvents: 0,
      rejectedChunks: 0,
      uploadedChunks: 0,
      uploadedEvents: 0,
    };
  }

  async uploadNow(): Promise<void> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async startHeartbeat(): Promise<void> {
    throw this.unavailable('DrivingSensors is only available on Android and iOS.');
  }

  async stopHeartbeat(): Promise<void> {
    return;
  }

  async getHeartbeatStatus(): Promise<HeartbeatStatus> {
    return { running: false, sent: 0, succeeded: 0 };
  }
}
