import { WebPlugin } from '@capacitor/core';
import type {
  BtLocationConfig,
  BtLocationReporterPlugin,
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

  async addDevices(_options: { deviceIds: string[] }): Promise<void> {
    this.warn();
  }

  async removeDevices(_options: { deviceIds: string[] }): Promise<void> {
    this.warn();
  }
}
