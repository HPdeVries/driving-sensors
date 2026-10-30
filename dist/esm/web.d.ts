import { WebPlugin } from '@capacitor/core';
import type { DrivingSensorsPlugin, PermissionStatus, SensorReadings, TelemetryEvent, TrackingStatus } from './definitions';
export declare class DrivingSensorsWeb extends WebPlugin implements DrivingSensorsPlugin {
    checkPermissions(): Promise<PermissionStatus>;
    requestPermissions(): Promise<PermissionStatus>;
    startTracking(): Promise<{
        sessionId: string;
    }>;
    stopTracking(): Promise<void>;
    getStatus(): Promise<TrackingStatus>;
    getBufferedEvents(): Promise<{
        events: TelemetryEvent[];
    }>;
    ackEvents(): Promise<void>;
    getLastReadings(): Promise<SensorReadings>;
    getCurrentReadings(): Promise<SensorReadings>;
}
