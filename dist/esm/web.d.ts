import { WebPlugin } from '@capacitor/core';
import type { BtDeviceEntry, BtLocationConfig, BtLocationReporterPlugin, LiveTrackingSession, PermissionsStatus } from './definitions';
export declare class BtLocationReporterWeb extends WebPlugin implements BtLocationReporterPlugin {
    private warn;
    start(_options: BtLocationConfig): Promise<void>;
    stop(): Promise<void>;
    isRunning(): Promise<{
        running: boolean;
    }>;
    addDevices(_options: {
        devices: BtDeviceEntry[];
    }): Promise<void>;
    removeDevices(_options: {
        devices: BtDeviceEntry[];
    }): Promise<void>;
    getLogPath(): Promise<{
        path: string;
    }>;
    getLogs(): Promise<{
        logs: string;
    }>;
    requestLocationPermission(): Promise<{
        granted: boolean;
    }>;
    hasLocationPermission(): Promise<{
        granted: boolean;
    }>;
    checkPermissions(): Promise<PermissionsStatus>;
    requestPermissions(_options?: {
        permissions?: ('bluetooth' | 'location' | 'bluetoothscan')[];
    }): Promise<PermissionsStatus>;
    writeWithoutResponse(_options: {
        deviceId: string;
        service: string;
        characteristic: string;
        value: number[];
    }): Promise<void>;
    startLiveTracking(_options: {
        pajDeviceId: string | number;
        intervalSec: number;
        durationSec: number;
    }): Promise<void>;
    stopLiveTracking(_options?: {
        pajDeviceId?: string | number;
    }): Promise<void>;
    getLiveTrackingDevices(): Promise<{
        devices: LiveTrackingSession[];
    }>;
}
