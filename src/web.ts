import { WebPlugin } from '@capacitor/core';

import type { DrivingSensorsPlugin } from './definitions';

export class DrivingSensorsWeb extends WebPlugin implements DrivingSensorsPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
