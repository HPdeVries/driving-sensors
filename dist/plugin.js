var capacitorDrivingSensors = (function (exports, core) {
    'use strict';

    const DrivingSensors = core.registerPlugin('DrivingSensors', {
        web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.DrivingSensorsWeb()),
    });

    // Kept in sync with package.json by scripts/sync-version.mjs. Do not edit by hand.
    const PLUGIN_VERSION = '0.2.0';
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
        async getPluginVersion() {
            return { version: PLUGIN_VERSION, platform: 'web' };
        }
        async startRecording() {
            throw this.unavailable('DrivingSensors is only available on Android and iOS.');
        }
        async stopRecording() {
            throw this.unavailable('DrivingSensors is only available on Android and iOS.');
        }
        async getRecordingStatus() {
            return {
                recording: false,
                chunksPending: 0,
                bytesPending: 0,
                available: { acc: false, gyr: false, mag: false, bar: false, loc: false },
            };
        }
        async listRecordingChunks() {
            return { chunks: [] };
        }
        async getRecordingChunk() {
            throw this.unavailable('DrivingSensors is only available on Android and iOS.');
        }
        async ackRecordingChunk() {
            return;
        }
        async configureUpload() {
            throw this.unavailable('DrivingSensors is only available on Android and iOS.');
        }
        async clearUpload() {
            return;
        }
        async getUploadStatus() {
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
        async uploadNow() {
            throw this.unavailable('DrivingSensors is only available on Android and iOS.');
        }
        async startHeartbeat() {
            throw this.unavailable('DrivingSensors is only available on Android and iOS.');
        }
        async stopHeartbeat() {
            return;
        }
        async getHeartbeatStatus() {
            return { running: false, sent: 0, succeeded: 0 };
        }
    }

    var web = /*#__PURE__*/Object.freeze({
        __proto__: null,
        DrivingSensorsWeb: DrivingSensorsWeb
    });

    exports.DrivingSensors = DrivingSensors;

    return exports;

})({}, capacitorExports);
//# sourceMappingURL=plugin.js.map
