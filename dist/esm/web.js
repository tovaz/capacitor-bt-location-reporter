import { WebPlugin } from '@capacitor/core';
export class BtLocationReporterWeb extends WebPlugin {
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
}
