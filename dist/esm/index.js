export * from './definitions';
import { registerPlugin } from '@capacitor/core';
/**
 * Capacitor plugin instance.
 * On native platforms the native implementation is used automatically.
 * On web a stub is used (operations are no-ops with a console warning).
 */
const BtLocationReporter = registerPlugin('BtLocationReporter', {
    web: () => import('./web').then(m => new m.BtLocationReporterWeb()),
});
export { BtLocationReporter };
