import { registerPlugin } from '@capacitor/core';
const DrivingSensors = registerPlugin('DrivingSensors', {
    web: () => import('./web').then((m) => new m.DrivingSensorsWeb()),
});
export * from './definitions';
export { DrivingSensors };
//# sourceMappingURL=index.js.map