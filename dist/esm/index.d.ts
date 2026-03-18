export * from './definitions';
import type { BtLocationReporterPlugin } from './definitions';
/**
 * Capacitor plugin instance.
 * On native platforms the native implementation is used automatically.
 * On web a stub is used (operations are no-ops with a console warning).
 */
declare const BtLocationReporter: BtLocationReporterPlugin;
export { BtLocationReporter };
