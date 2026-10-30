'use strict';

var core = require('@capacitor/core');

const DrivingSensors = core.registerPlugin('DrivingSensors', {
    web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.DrivingSensorsWeb()),
});

class DrivingSensorsWeb extends core.WebPlugin {
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

var web = /*#__PURE__*/Object.freeze({
    __proto__: null,
    DrivingSensorsWeb: DrivingSensorsWeb
});

exports.DrivingSensors = DrivingSensors;
//# sourceMappingURL=plugin.cjs.js.map
