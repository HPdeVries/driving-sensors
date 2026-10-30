import { WebPlugin } from '@capacitor/core';

import type {
  DrivingSensorsPlugin,
  PermissionStatus,
  SensorReadings,
  TelemetryEvent,
  TrackingStatus,
} from './definitions';

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
}
