export * from './definitions';

import { registerPlugin } from '@capacitor/core';
import type { BtLocationReporterPlugin } from './definitions';

/**
 * Capacitor plugin instance.
 * On native platforms the native implementation is used automatically.
 * On web a stub is used (operations are no-ops with a console warning).
 */
const BtLocationReporter = registerPlugin<BtLocationReporterPlugin>(
  'BtLocationReporter',
  {
    web: () => import('./web').then(m => new m.BtLocationReporterWeb()),
  },
);

export { BtLocationReporter };
