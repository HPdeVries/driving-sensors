import { WebPlugin } from '@capacitor/core';
export class DrivingSensorsWeb extends WebPlugin {
    async checkPermissions() {
        throw this.unavailable('DrivingSensors is only available on Android and iOS.');
    }
    async requestPermissions() {
        throw this.unavailable('DrivingSensors is only available on Android and iOS.');
    }
    async startTracking() {
        throw this.unavailable('DrivingSensors is only available on Android and iOS.');
    }
    async stopTracking() {
        throw this.unavailable('DrivingSensors is only available on Android and iOS.');
    }
    async getStatus() {
        return { active: false, serviceRunning: false, inTrip: false, buffered: 0 };
    }
    async getBufferedEvents() {
        return { events: [] };
    }
    async ackEvents() {
        return;
    }
    async getLastReadings() {
        return { hasLocation: false, hasMotion: false };
    }
    async getCurrentReadings() {
        throw this.unavailable('DrivingSensors is only available on Android and iOS.');
    }
}
//# sourceMappingURL=web.js.map