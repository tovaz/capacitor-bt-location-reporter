import { WebPlugin } from '@capacitor/core';
import type { BtDeviceEntry, BtLocationConfig, BtLocationReporterPlugin } from './definitions';
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
}
