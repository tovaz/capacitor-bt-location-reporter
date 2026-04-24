var TovazCapacitorBtLocationReporter = (function (exports, core) {
    'use strict';

    /**
     * Capacitor plugin instance.
     * On native platforms the native implementation is used automatically.
     * On web a stub is used (operations are no-ops with a console warning).
     */
    const BtLocationReporter = core.registerPlugin('BtLocationReporter', {
        web: () => Promise.resolve().then(function () { return web; }).then(m => new m.BtLocationReporterWeb()),
    });

    class BtLocationReporterWeb extends core.WebPlugin {
        warn() {
            console.warn('[BtLocationReporter] Background BLE+GPS reporting is not supported on web.');
        }
        async start(_options) {
            this.warn();
        }
        async stop() {
            this.warn();
        }
        async isRunning() {
            return { running: false };
        }
        async addDevices(_options) {
            this.warn();
        }
        async removeDevices(_options) {
            this.warn();
        }
        async getLogPath() {
            return { path: 'Not available on web' };
        }
        async getLogs() {
            return { logs: 'Logging not available on web' };
        }
        async requestLocationPermission() {
            this.warn();
            return { granted: false };
        }
        async hasLocationPermission() {
            this.warn();
            return { granted: false };
        }
        async writeWithoutResponse(_options) {
            this.warn();
        }
        async startLiveTracking(_options) {
            this.warn();
        }
        async stopLiveTracking(_options) {
            this.warn();
        }
        async getLiveTrackingDevices() {
            return { devices: [] };
        }
    }

    var web = /*#__PURE__*/Object.freeze({
        __proto__: null,
        BtLocationReporterWeb: BtLocationReporterWeb
    });

    exports.BtLocationReporter = BtLocationReporter;

    return exports;

})({}, capacitorExports);
