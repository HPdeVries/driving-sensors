import { registerPlugin } from '@capacitor/core';

import type { DrivingSensorsPlugin } from './definitions';

const DrivingSensors = registerPlugin<DrivingSensorsPlugin>('DrivingSensors', {
  web: () => import('./web').then((m) => new m.DrivingSensorsWeb()),
});

export * from './definitions';
export { DrivingSensors };
