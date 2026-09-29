export type ShifterMode = 'sequential' | 'h-shifter' | 'range-splitter' | 'automatic';

export type LightState = 0 | 1 | 2 | 3; // 0=Off, 1=Parking, 2=Low Beam, 3=High Beam
export type WiperState = 0 | 1 | 2 | 3; // 0=Off, 1=Intermittent, 2=Slow, 3=Fast
export type TurnSignalState = 'off' | 'left' | 'right';
export type ConnectionState = 'offline' | 'pairing' | 'connected';

export interface CbRadioState {
  active: boolean;
  channel: number;
  channelName: string;
  frequency: string;
  volume: number;
  isMuted: boolean;
  isTransmitting: boolean;
  rssi: number;
}

export interface ControllerState {
  // Steering
  steeringAngle: number; // in degrees, e.g. -450 to +450
  steeringNormalized: number; // -1.0 to +1.0
  wheelMaxAngle: number; // 360, 540, 900, 1080, 1800

  // Pedals
  gas: number; // 0 - 100 %
  brake: number; // 0 - 100 %

  // Shifter
  gear: number; // -2 = R2, -1 = R1, 0 = N, 1 to 12
  shifterMode: ShifterMode;
  range: 'low' | 'high';
  splitter: 'low' | 'high';

  // Engine & Air Brakes
  engine: boolean;
  engineRpm: number;
  parkingBrake: boolean;
  trailerAttached: boolean;
  retarderLevel: number; // 0 - 5

  // Lights & Cabin
  lights: LightState;
  beacon: boolean;
  interiorLight: boolean;
  hazardLights: boolean;
  turnSignal: TurnSignalState;
  wipers: WiperState;

  // Truck functions
  diffLock: boolean;
  axleLift: boolean;
  cameraView: number; // 1 to 8
  cruiseControlActive: boolean;
  cruiseControlSpeed: number; // km/h

  // Horn & Look
  horn: boolean;
  lookPan: { x: number; y: number }; // -1 to +1

  // CB Radio
  cbRadio: CbRadioState;

  // Diagnostic / Connection
  connection: ConnectionState;
  connectedDeviceName: string | null;
  latencyMs: number;
  pollingHz: number;
}

export interface ControllerSettings {
  wheelMaxAngle: number;
  autoCenterSpring: number; // 0 to 100
  deadzone: number; // 0 to 15%
  nonLinearity: number; // 1.0 to 2.5
  gyroscopeEnabled: boolean;
  hapticsEnabled: boolean;
  hapticIntensity: number; // 0 to 100
  soundEnabled: boolean;
  soundVolume: number; // 0 to 100
  shifterMode: ShifterMode;
  transmissionGears: number; // 6, 12, 16, 18
  ultraLowLatencyMode: boolean;
}

export interface ButtonMapping {
  id: string;
  name: string;
  category: 'Drive' | 'Transmission' | 'Cabin' | 'Lighting' | 'Camera';
  defaultKey: string;
  hidButton: number;
  description: string;
}
