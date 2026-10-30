export interface DrivingSensorsPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
