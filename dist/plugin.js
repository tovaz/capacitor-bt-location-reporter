var capacitorBtLocationReporter = (function (exports, core) {
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
    }

    var web = /*#__PURE__*/Object.freeze({
        __proto__: null,
        BtLocationReporterWeb: BtLocationReporterWeb
    });

    exports.BtLocationReporter = BtLocationReporter;

    return exports;

})({}, capacitorExports);
