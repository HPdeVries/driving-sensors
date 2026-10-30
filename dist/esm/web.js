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
        return { active: false, inTrip: false, buffered: 0 };
    }
    async getBufferedEvents() {
        return { events: [] };
    }
    async ackEvents() {
        return;
    }
}
//# sourceMappingURL=web.js.map