import { WebPlugin } from '@capacitor/core';
import type {
  BtDeviceEntry,
  BtLocationConfig,
  BtLocationReporterPlugin,
  LiveTrackingSession,
  PermissionsStatus,
} from './definitions';

export class BtLocationReporterWeb
  extends WebPlugin
  implements BtLocationReporterPlugin
{
  private warn(): void {
    console.warn('[BtLocationReporter] Background BLE+GPS reporting is not supported on web.');
  }

  async start(_options: BtLocationConfig): Promise<void> {
    this.warn();
  }

  async stop(): Promise<void> {
    this.warn();
  }

  async isRunning(): Promise<{ running: boolean }> {
    return { running: false };
  }

  async addDevices(_options: { devices: BtDeviceEntry[] }): Promise<void> {
    this.warn();
  }

  async removeDevices(_options: { devices: BtDeviceEntry[] }): Promise<void> {
    this.warn();
  }

  async getLogPath(): Promise<{ path: string }> {
    return { path: 'Not available on web' };
  }

  async getLogs(): Promise<{ logs: string }> {
    return { logs: 'Logging not available on web' };
  }

  async requestLocationPermission(): Promise<{ granted: boolean }> {
    this.warn();
    return { granted: false };
  }

  async hasLocationPermission(): Promise<{ granted: boolean }> {
    this.warn();
    return { granted: false };
  }

  async checkPermissions(): Promise<PermissionsStatus> {
    this.warn();
    return {
      locationPermission: 'denied',
      bluetoothPermission: 'denied',
      bluetoothEnabled: false,
      internetAvailable: false,
      allGranted: false,
    };
  }

  async requestPermissions(_options?: { permissions?: ('bluetooth' | 'location' | 'bluetoothscan')[] }): Promise<PermissionsStatus> {
    this.warn();
    return {
      locationPermission: 'denied',
      bluetoothPermission: 'denied',
      bluetoothEnabled: false,
      internetAvailable: false,
      allGranted: false,
    };
  }

  async writeWithoutResponse(_options: {
    deviceId: string;
    service: string;
    characteristic: string;
    value: number[];
  }): Promise<void> {
    this.warn();
  }

  async startLiveTracking(_options: {
    pajDeviceId: string | number;
    intervalSec: number;
    durationSec: number;
  }): Promise<void> {
    this.warn();
  }

  async stopLiveTracking(_options?: {
    pajDeviceId?: string | number;
  }): Promise<void> {
    this.warn();
  }

  async getLiveTrackingDevices(): Promise<{ devices: LiveTrackingSession[] }> {
    return { devices: [] };
  }
}
