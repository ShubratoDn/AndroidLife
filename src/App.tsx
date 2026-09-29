/**
 * TruckController Pro - Euro Truck Simulator 2 Controller
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { ControllerState, ControllerSettings, ShifterMode, ButtonMapping } from './types/controller';
import { DEFAULT_MAPPINGS } from './data/defaultMappings';
import { HeaderBar } from './components/HeaderBar';
import { LookPanWidget } from './components/LookPanWidget';
import { SteeringWheel } from './components/SteeringWheel';
import { CenterConsole } from './components/CenterConsole';
import { Pedals } from './components/Pedals';
import { StatusBar } from './components/StatusBar';
import { AndroidProjectModal } from './components/AndroidProjectModal';
import { ButtonMappingModal } from './components/ButtonMappingModal';
import { BluetoothModal } from './components/BluetoothModal';
import { SettingsModal } from './components/SettingsModal';
import { sounds } from './services/soundEffects';
import { haptics } from './services/haptics';
import { bluetoothHid } from './services/bluetoothHid';

export default function App() {
  // Settings
  const [settings, setSettings] = useState<ControllerSettings>({
    wheelMaxAngle: 900,
    autoCenterSpring: 75,
    deadzone: 2,
    nonLinearity: 1.2,
    gyroscopeEnabled: false,
    hapticsEnabled: true,
    hapticIntensity: 85,
    soundEnabled: true,
    soundVolume: 80,
    shifterMode: 'sequential',
    transmissionGears: 12,
    ultraLowLatencyMode: true,
  });

  // Controller Live State
  const [controllerState, setControllerState] = useState<ControllerState>({
    steeringAngle: 0,
    steeringNormalized: 0,
    wheelMaxAngle: 900,
    gas: 0,
    brake: 0,
    gear: 0, // Neutral
    shifterMode: 'sequential',
    range: 'low',
    splitter: 'low',
    engine: false,
    engineRpm: 0,
    parkingBrake: true,
    trailerAttached: true,
    retarderLevel: 0,
    lights: 0,
    beacon: false,
    interiorLight: false,
    hazardLights: false,
    turnSignal: 'off',
    wipers: 0,
    diffLock: false,
    axleLift: false,
    cameraView: 1,
    cruiseControlActive: false,
    cruiseControlSpeed: 80,
    horn: false,
    lookPan: { x: 0, y: 0 },
    cbRadio: {
      active: true,
      channel: 19,
      channelName: 'HIGHWAY',
      frequency: '27.185 MHz',
      volume: 80,
      isMuted: false,
      isTransmitting: false,
      rssi: 5,
    },
    connection: 'offline',
    connectedDeviceName: null,
    latencyMs: 0.8,
    pollingHz: 120,
  });

  // Mappings
  const [mappings, setMappings] = useState<ButtonMapping[]>(DEFAULT_MAPPINGS);

  // Modals
  const [androidModalOpen, setAndroidModalOpen] = useState(false);
  const [bluetoothModalOpen, setBluetoothModalOpen] = useState(false);
  const [mappingModalOpen, setMappingModalOpen] = useState(false);
  const [settingsModalOpen, setSettingsModalOpen] = useState(false);

  // Subscribe to Bluetooth service status
  useEffect(() => {
    return bluetoothHid.subscribe((connected, name) => {
      setControllerState((prev) => ({
        ...prev,
        connection: connected ? 'connected' : 'offline',
        connectedDeviceName: name,
      }));
    });
  }, []);

  // 120Hz Telemetry Polling Loop
  const stateRef = useRef(controllerState);
  useEffect(() => {
    stateRef.current = controllerState;
  }, [controllerState]);

  useEffect(() => {
    const interval = setInterval(() => {
      const report = bluetoothHid.generateHidReport(stateRef.current);
      setControllerState((prev) => ({
        ...prev,
        latencyMs: bluetoothHid.getConnectionState().latency,
      }));
    }, 1000 / 120);

    return () => clearInterval(interval);
  }, []);

  // Update engine RPM based on throttle
  useEffect(() => {
    if (controllerState.engine) {
      const rpm = 650 + (controllerState.gas / 100) * 1500;
      sounds.updateEngineRpm(rpm);
    }
  }, [controllerState.engine, controllerState.gas]);

  // Handlers
  const handleAngleChange = useCallback((angle: number, normalized: number) => {
    setControllerState((prev) => ({
      ...prev,
      steeringAngle: angle,
      steeringNormalized: normalized,
    }));
  }, []);

  const handleResetCenter = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      steeringAngle: 0,
      steeringNormalized: 0,
    }));
  }, []);

  const handleGasChange = useCallback((val: number) => {
    setControllerState((prev) => ({ ...prev, gas: val }));
  }, []);

  const handleBrakeChange = useCallback((val: number) => {
    setControllerState((prev) => ({ ...prev, brake: val }));
  }, []);

  const handleGearUp = useCallback(() => {
    setControllerState((prev) => {
      if (prev.gear < 12) {
        return { ...prev, gear: prev.gear + 1 };
      }
      return prev;
    });
  }, []);

  const handleGearDown = useCallback(() => {
    setControllerState((prev) => {
      if (prev.gear > -2) {
        return { ...prev, gear: prev.gear - 1 };
      }
      return prev;
    });
  }, []);

  const handleCycleShifterMode = useCallback(() => {
    const modes: ShifterMode[] = ['sequential', 'range-splitter', 'h-shifter', 'automatic'];
    setControllerState((prev) => {
      const nextIdx = (modes.indexOf(prev.shifterMode) + 1) % modes.length;
      return { ...prev, shifterMode: modes[nextIdx] };
    });
  }, []);

  const handleRetarderChange = useCallback((delta: number) => {
    sounds.playRetarderClick();
    haptics.click();
    setControllerState((prev) => ({
      ...prev,
      retarderLevel: Math.max(0, Math.min(5, prev.retarderLevel + delta)),
    }));
  }, []);

  const handleCycleLights = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      lights: ((prev.lights + 1) % 4) as 0 | 1 | 2 | 3,
    }));
  }, []);

  const handleToggleBeacon = useCallback(() => {
    setControllerState((prev) => ({ ...prev, beacon: !prev.beacon }));
  }, []);

  const handleCycleCamera = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      cameraView: prev.cameraView >= 5 ? 1 : prev.cameraView + 1,
    }));
  }, []);

  const handleToggleDiffLock = useCallback(() => {
    setControllerState((prev) => ({ ...prev, diffLock: !prev.diffLock }));
  }, []);

  const handleToggleAxleLift = useCallback(() => {
    setControllerState((prev) => ({ ...prev, axleLift: !prev.axleLift }));
  }, []);

  const handleToggleCruise = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      cruiseControlActive: !prev.cruiseControlActive,
    }));
  }, []);

  const handleAdjustCruiseSpeed = useCallback((delta: number) => {
    sounds.playClick();
    haptics.click();
    setControllerState((prev) => ({
      ...prev,
      cruiseControlSpeed: Math.max(30, Math.min(130, prev.cruiseControlSpeed + delta)),
    }));
  }, []);

  const handleToggleEngine = useCallback(() => {
    setControllerState((prev) => {
      const next = !prev.engine;
      if (next) {
        sounds.startEngine();
      } else {
        sounds.stopEngine();
      }
      return { ...prev, engine: next };
    });
  }, []);

  const handleQuickLook = useCallback((dir: 'left' | 'right') => {
    setControllerState((prev) => ({
      ...prev,
      lookPan: { x: dir === 'left' ? -0.9 : 0.9, y: 0 },
    }));
    setTimeout(() => {
      setControllerState((prev) => ({
        ...prev,
        lookPan: { x: 0, y: 0 },
      }));
    }, 450);
  }, []);

  const handleToggleTrailer = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      trailerAttached: !prev.trailerAttached,
    }));
  }, []);

  const handleToggleInteriorLight = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      interiorLight: !prev.interiorLight,
    }));
  }, []);

  const handleCycleWipers = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      wipers: ((prev.wipers + 1) % 4) as 0 | 1 | 2 | 3,
    }));
  }, []);

  const handleToggleParkingBrake = useCallback(() => {
    sounds.playAirBrake();
    setControllerState((prev) => ({
      ...prev,
      parkingBrake: !prev.parkingBrake,
    }));
  }, []);

  const handleSelectCbChannel = useCallback((ch: number) => {
    const freqs: Record<number, { name: string; freq: string }> = {
      1: { name: 'HIGHWAY', freq: '27.185 MHz' },
      2: { name: 'EMERGENCY', freq: '27.065 MHz' },
      3: { name: 'CONVOY', freq: '27.085 MHz' },
      4: { name: 'LOGISTICS', freq: '27.125 MHz' },
      5: { name: 'FERRY/PORT', freq: '27.165 MHz' },
      6: { name: 'CHATTER', freq: '27.205 MHz' },
    };
    const info = freqs[ch] || freqs[1];
    setControllerState((prev) => ({
      ...prev,
      cbRadio: {
        ...prev.cbRadio,
        channel: ch === 1 ? 19 : ch === 2 ? 9 : ch * 3,
        channelName: info.name,
        frequency: info.freq,
      },
    }));
  }, []);

  const handleToggleCbMute = useCallback(() => {
    setControllerState((prev) => {
      const nextMute = !prev.cbRadio.isMuted;
      sounds.setMute(nextMute);
      return {
        ...prev,
        cbRadio: { ...prev.cbRadio, isMuted: nextMute },
      };
    });
  }, []);

  const handleCbPttDown = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      cbRadio: { ...prev.cbRadio, isTransmitting: true },
    }));
  }, []);

  const handleCbPttUp = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      cbRadio: { ...prev.cbRadio, isTransmitting: false },
    }));
  }, []);

  const handleToggleHazard = useCallback(() => {
    setControllerState((prev) => ({
      ...prev,
      hazardLights: !prev.hazardLights,
      turnSignal: !prev.hazardLights ? 'off' : prev.turnSignal,
    }));
  }, []);

  const handleToggleTurnSignal = useCallback((signal: 'left' | 'right') => {
    setControllerState((prev) => ({
      ...prev,
      turnSignal: prev.turnSignal === signal ? 'off' : signal,
      hazardLights: false,
    }));
  }, []);

  const handleLookChange = useCallback((pos: { x: number; y: number }) => {
    setControllerState((prev) => ({ ...prev, lookPan: pos }));
  }, []);

  const handleUpdateSettings = useCallback((newSettings: Partial<ControllerSettings>) => {
    setSettings((prev) => ({ ...prev, ...newSettings }));
  }, []);

  return (
    <div className="h-screen w-screen flex flex-col bg-[#0b0e14] text-slate-100 overflow-hidden select-none touch-none">
      {/* 1. Header Bar */}
      <HeaderBar
        state={controllerState}
        settings={settings}
        onOpenAndroidModal={() => setAndroidModalOpen(true)}
        onOpenBluetoothModal={() => setBluetoothModalOpen(true)}
        onOpenMappingModal={() => setMappingModalOpen(true)}
        onOpenSettingsModal={() => setSettingsModalOpen(true)}
        onCycleShifterMode={handleCycleShifterMode}
      />

      {/* 2. Main Cockpit Frame */}
      <main className="flex-1 min-h-0 relative px-4 sm:px-8 py-2 sm:py-4 flex items-center justify-between overflow-hidden">
        {/* Top-Right Look / Pan Radar Touch Control */}
        <div className="absolute top-3 right-4 sm:right-8 z-10">
          <LookPanWidget
            lookPan={controllerState.lookPan}
            onLookChange={handleLookChange}
          />
        </div>

        {/* Left Column: Steering Wheel Assembly */}
        <div className="flex-1 flex items-center justify-start max-w-sm sm:max-w-md">
          <SteeringWheel
            state={controllerState}
            settings={settings}
            onAngleChange={handleAngleChange}
            onResetCenter={handleResetCenter}
            onToggleHazard={handleToggleHazard}
            onToggleTurnSignal={handleToggleTurnSignal}
            onHornDown={() => setControllerState((prev) => ({ ...prev, horn: true }))}
            onHornUp={() => setControllerState((prev) => ({ ...prev, horn: false }))}
          />
        </div>

        {/* Center Column: Center Console */}
        <div className="flex-[1.5] flex items-center justify-center px-2">
          <CenterConsole
            state={controllerState}
            settings={settings}
            onGearUp={handleGearUp}
            onGearDown={handleGearDown}
            onCycleShifterMode={handleCycleShifterMode}
            onRetarderChange={handleRetarderChange}
            onCycleLights={handleCycleLights}
            onToggleBeacon={handleToggleBeacon}
            onCycleCamera={handleCycleCamera}
            onToggleDiffLock={handleToggleDiffLock}
            onToggleAxleLift={handleToggleAxleLift}
            onToggleCruise={handleToggleCruise}
            onAdjustCruiseSpeed={handleAdjustCruiseSpeed}
            onToggleEngine={handleToggleEngine}
            onQuickLook={handleQuickLook}
            onToggleTrailer={handleToggleTrailer}
            onToggleInteriorLight={handleToggleInteriorLight}
            onCycleWipers={handleCycleWipers}
            onToggleParkingBrake={handleToggleParkingBrake}
            onSelectCbChannel={handleSelectCbChannel}
            onToggleCbMute={handleToggleCbMute}
            onCbPttDown={handleCbPttDown}
            onCbPttUp={handleCbPttUp}
          />
        </div>

        {/* Right Column: Dual Vertical Pedals (Brake & Gas) */}
        <div className="flex-1 flex items-end justify-end max-w-xs pb-2 sm:pb-4">
          <Pedals
            gas={controllerState.gas}
            brake={controllerState.brake}
            onGasChange={handleGasChange}
            onBrakeChange={handleBrakeChange}
          />
        </div>
      </main>

      {/* 3. Bottom Status Bar */}
      <StatusBar state={controllerState} />

      {/* Modals */}
      <AndroidProjectModal
        isOpen={androidModalOpen}
        onClose={() => setAndroidModalOpen(false)}
      />

      <BluetoothModal
        isOpen={bluetoothModalOpen}
        onClose={() => setBluetoothModalOpen(false)}
        state={controllerState}
        onToggleVirtualBt={() => {
          bluetoothHid.toggleVirtualBluetooth();
        }}
      />

      <ButtonMappingModal
        isOpen={mappingModalOpen}
        onClose={() => setMappingModalOpen(false)}
        mappings={mappings}
        onSaveMappings={setMappings}
      />

      <SettingsModal
        isOpen={settingsModalOpen}
        onClose={() => setSettingsModalOpen(false)}
        settings={settings}
        onUpdateSettings={handleUpdateSettings}
      />
    </div>
  );
}
