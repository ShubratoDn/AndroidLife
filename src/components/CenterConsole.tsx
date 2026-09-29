import React, { useState } from 'react';
import {
  ChevronUp,
  ChevronDown,
  SlidersHorizontal,
  Lightbulb,
  Camera,
  Lock,
  ArrowUpToLine,
  Gauge,
  Power,
  ChevronLeft,
  ChevronRight,
  Link,
  Sun,
  CloudRain,
  CircleDot,
  Radio,
  Volume2,
  VolumeX,
} from 'lucide-react';
import { ControllerState, ControllerSettings } from '../types/controller';
import { sounds } from '../services/soundEffects';
import { haptics } from '../services/haptics';

interface CenterConsoleProps {
  state: ControllerState;
  settings: ControllerSettings;
  onGearUp: () => void;
  onGearDown: () => void;
  onCycleShifterMode: () => void;
  onRetarderChange: (delta: number) => void;
  onCycleLights: () => void;
  onToggleBeacon: () => void;
  onCycleCamera: () => void;
  onToggleDiffLock: () => void;
  onToggleAxleLift: () => void;
  onToggleCruise: () => void;
  onAdjustCruiseSpeed: (delta: number) => void;
  onToggleEngine: () => void;
  onQuickLook: (dir: 'left' | 'right') => void;
  onToggleTrailer: () => void;
  onToggleInteriorLight: () => void;
  onCycleWipers: () => void;
  onToggleParkingBrake: () => void;
  onSelectCbChannel: (ch: number) => void;
  onToggleCbMute: () => void;
  onCbPttDown: () => void;
  onCbPttUp: () => void;
}

export const CenterConsole: React.FC<CenterConsoleProps> = ({
  state,
  onGearUp,
  onGearDown,
  onCycleShifterMode,
  onRetarderChange,
  onCycleLights,
  onToggleBeacon,
  onCycleCamera,
  onToggleDiffLock,
  onToggleAxleLift,
  onToggleCruise,
  onAdjustCruiseSpeed,
  onToggleEngine,
  onQuickLook,
  onToggleTrailer,
  onToggleInteriorLight,
  onCycleWipers,
  onToggleParkingBrake,
  onSelectCbChannel,
  onToggleCbMute,
  onCbPttDown,
  onCbPttUp,
}) => {
  const [signalBars] = useState([3, 4, 5, 4, 5, 4]);

  const gearText =
    state.gear === 0 ? 'N' : state.gear === -1 ? 'R1' : state.gear === -2 ? 'R2' : state.gear.toString();

  const lightLabels = ['OFF', 'PARK', 'LOW', 'HIGH'];
  const wiperLabels = ['OFF', 'INT', 'SLOW', 'FAST'];

  return (
    <div className="flex flex-col items-center gap-3 select-none touch-none max-w-xl w-full">
      {/* 1. TOP SHIFTER MODULE */}
      <div className="flex items-center gap-2">
        <div className="flex items-center bg-[#10151f] border border-white/10 rounded-2xl p-1.5 shadow-xl shadow-black/50">
          {/* Gear Up Button */}
          <button
            onClick={() => {
              sounds.playGearShift();
              haptics.gearShift();
              onGearUp();
            }}
            className="w-12 h-12 rounded-xl bg-[#171e2b] hover:bg-[#20293a] active:scale-95 border border-white/5 text-emerald-400 flex items-center justify-center transition-all cursor-pointer"
            title="Shift Up (Key: Shift)"
          >
            <ChevronUp className="w-7 h-7" />
          </button>

          {/* Central Gear Indicator Display */}
          <div className="w-20 sm:w-24 h-12 flex flex-col items-center justify-center bg-[#0a0d13] rounded-lg mx-2 border border-white/5 shadow-inner">
            <span className="text-[9px] font-mono-nums tracking-widest text-slate-500 font-semibold">GEAR</span>
            <span className="font-telemetry font-bold text-2xl sm:text-3xl text-amber-400 leading-none drop-shadow-[0_0_8px_rgba(251,191,36,0.5)]">
              {gearText}
            </span>
          </div>

          {/* Gear Down Button */}
          <button
            onClick={() => {
              sounds.playGearShift();
              haptics.gearShift();
              onGearDown();
            }}
            className="w-12 h-12 rounded-xl bg-[#171e2b] hover:bg-[#20293a] active:scale-95 border border-white/5 text-amber-500 flex items-center justify-center transition-all cursor-pointer"
            title="Shift Down (Key: Ctrl)"
          >
            <ChevronDown className="w-7 h-7" />
          </button>
        </div>

        {/* Shifter Mode Switcher Button */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onCycleShifterMode();
          }}
          className="w-12 h-12 rounded-2xl bg-[#10151f] hover:bg-[#1a2130] active:scale-95 border border-white/10 text-slate-400 hover:text-white flex items-center justify-center shadow-xl transition-all cursor-pointer"
          title={`Cycle Transmission Mode: ${state.shifterMode}`}
        >
          <SlidersHorizontal className="w-5 h-5" />
        </button>
      </div>

      {/* 2. ROW 1 FUNCTION BUTTONS */}
      <div className="grid grid-cols-7 gap-2 w-full">
        {/* Retarder */}
        <div className="bg-[#10151e] border border-white/10 rounded-xl p-1 flex flex-col justify-between items-center h-16 shadow-lg">
          <button
            onClick={() => onRetarderChange(1)}
            className="w-full text-slate-400 hover:text-white flex items-center justify-center active:scale-90 transition-transform cursor-pointer"
            title="Retarder Up (Key: ')"
          >
            <ChevronUp className="w-4 h-4" />
          </button>
          <div className="flex flex-col items-center leading-none">
            <span className="text-[8px] font-mono-nums text-slate-500 font-bold">RET</span>
            <span className={`text-xs font-mono-nums font-bold ${state.retarderLevel > 0 ? 'text-amber-400' : 'text-slate-400'}`}>
              {state.retarderLevel}
            </span>
          </div>
          <button
            onClick={() => onRetarderChange(-1)}
            className="w-full text-slate-400 hover:text-white flex items-center justify-center active:scale-90 transition-transform cursor-pointer"
            title="Retarder Down (Key: ;)"
          >
            <ChevronDown className="w-4 h-4" />
          </button>
        </div>

        {/* Headlights Switch */}
        <button
          onClick={() => {
            sounds.playClick(800);
            haptics.click();
            onCycleLights();
          }}
          className={`rounded-xl border p-1 flex flex-col items-center justify-center h-16 transition-all cursor-pointer active:scale-95 ${
            state.lights > 0
              ? 'bg-[#152433] border-cyan-500/40 text-cyan-400 shadow-md shadow-cyan-500/20'
              : 'bg-[#10151e] border-white/10 text-slate-400 hover:bg-[#161c28]'
          }`}
          title={`Headlights: ${lightLabels[state.lights]} (Key: L)`}
        >
          <div className={`w-4 h-1 rounded-full mb-1 ${state.lights > 0 ? 'bg-cyan-400' : 'bg-slate-700'}`} />
          <Lightbulb className="w-5 h-5 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">LIGHT</span>
        </button>

        {/* Roof Beacon */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onToggleBeacon();
          }}
          className={`rounded-xl border p-1 flex flex-col items-center justify-center h-16 transition-all cursor-pointer active:scale-95 ${
            state.beacon
              ? 'bg-[#291e12] border-amber-500 text-amber-400 animate-beacon shadow-md shadow-amber-500/30'
              : 'bg-[#10151e] border-white/10 text-slate-400 hover:bg-[#161c28]'
          }`}
          title="Roof Hazard Beacon (Key: O)"
        >
          <div className={`w-4 h-1 rounded-full mb-1 ${state.beacon ? 'bg-amber-400' : 'bg-slate-700'}`} />
          <Lightbulb className="w-5 h-5 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">BEACON</span>
        </button>

        {/* Camera Views */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onCycleCamera();
          }}
          className="bg-[#10151e] hover:bg-[#161c28] border border-white/10 text-slate-300 rounded-xl p-1 flex flex-col items-center justify-center h-16 transition-all cursor-pointer active:scale-95"
          title={`Cycle Camera (View ${state.cameraView})`}
        >
          <div className="w-4 h-1 rounded-full mb-1 bg-slate-600" />
          <Camera className="w-5 h-5 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">CAM {state.cameraView}</span>
        </button>

        {/* Differential Lock */}
        <button
          onClick={() => {
            sounds.playClick(500);
            haptics.click();
            onToggleDiffLock();
          }}
          className={`rounded-xl border p-1 flex flex-col items-center justify-center h-16 transition-all cursor-pointer active:scale-95 ${
            state.diffLock
              ? 'bg-[#261c12] border-amber-500/60 text-amber-400 shadow-md shadow-amber-500/20'
              : 'bg-[#10151e] border-white/10 text-slate-400 hover:bg-[#161c28]'
          }`}
          title="Differential Lock (Key: V)"
        >
          <div className={`w-4 h-1 rounded-full mb-1 ${state.diffLock ? 'bg-amber-400' : 'bg-slate-700'}`} />
          <Lock className="w-5 h-5 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">DIFF</span>
        </button>

        {/* Lift Axle */}
        <button
          onClick={() => {
            sounds.playClick(600);
            haptics.click();
            onToggleAxleLift();
          }}
          className={`rounded-xl border p-1 flex flex-col items-center justify-center h-16 transition-all cursor-pointer active:scale-95 ${
            state.axleLift
              ? 'bg-[#152332] border-cyan-500/50 text-cyan-400 shadow-md shadow-cyan-500/20'
              : 'bg-[#10151e] border-white/10 text-slate-400 hover:bg-[#161c28]'
          }`}
          title="Lift / Lower Tag Axle (Key: U)"
        >
          <div className={`w-4 h-1 rounded-full mb-1 ${state.axleLift ? 'bg-cyan-400' : 'bg-slate-700'}`} />
          <ArrowUpToLine className="w-5 h-5 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">AXLE</span>
        </button>

        {/* Cruise Control */}
        <div className="bg-[#10151e] border border-white/10 rounded-xl p-1 flex flex-col justify-between items-center h-16 shadow-lg">
          <button
            onClick={() => onAdjustCruiseSpeed(5)}
            className="w-full text-slate-400 hover:text-white flex items-center justify-center active:scale-90 text-[10px] font-bold cursor-pointer"
            title="Cruise Speed Up"
          >
            +
          </button>
          <button
            onClick={() => {
              sounds.playClick();
              haptics.click();
              onToggleCruise();
            }}
            className="flex flex-col items-center cursor-pointer"
            title="Toggle Cruise Control (Key: C)"
          >
            <Gauge className={`w-4 h-4 ${state.cruiseControlActive ? 'text-emerald-400' : 'text-slate-500'}`} />
            <span className={`text-[8px] font-mono-nums font-bold ${state.cruiseControlActive ? 'text-emerald-400' : 'text-slate-400'}`}>
              {state.cruiseControlSpeed}
            </span>
          </button>
          <button
            onClick={() => onAdjustCruiseSpeed(-5)}
            className="w-full text-slate-400 hover:text-white flex items-center justify-center active:scale-90 text-[10px] font-bold cursor-pointer"
            title="Cruise Speed Down"
          >
            -
          </button>
        </div>
      </div>

      {/* 3. ROW 2 COCKPIT COMMANDS */}
      <div className="grid grid-cols-6 gap-2 w-full items-center">
        {/* Engine Start / Stop */}
        <button
          onClick={() => {
            haptics.engineStart();
            onToggleEngine();
          }}
          className={`rounded-full aspect-square max-w-[62px] mx-auto border-2 flex flex-col items-center justify-center transition-all cursor-pointer active:scale-95 shadow-lg ${
            state.engine
              ? 'bg-[#152a1a] border-emerald-500 text-emerald-400 shadow-emerald-500/30'
              : 'bg-[#181c24] hover:bg-[#202530] border-slate-700 text-slate-400'
          }`}
          title="Engine Ignition Start/Stop (Key: E)"
        >
          <Power className="w-5 h-5 mb-0.5" />
          <span className="text-[7px] font-mono-nums font-bold tracking-tight text-center leading-tight">
            ENGINE<br />START
          </span>
        </button>

        {/* Look Glance Left / Right */}
        <div className="bg-[#10151e] border border-white/10 rounded-xl p-1 flex items-center justify-between h-14 col-span-1 shadow-lg">
          <button
            onClick={() => {
              sounds.playClick();
              haptics.click();
              onQuickLook('left');
            }}
            className="w-6 h-full flex items-center justify-center text-slate-400 hover:text-white active:scale-90 transition-transform cursor-pointer"
            title="Quick Glance Left (Key: Num 4)"
          >
            <ChevronLeft className="w-5 h-5" />
          </button>
          <span className="text-[8px] font-mono-nums text-slate-500 font-bold">LOOK</span>
          <button
            onClick={() => {
              sounds.playClick();
              haptics.click();
              onQuickLook('right');
            }}
            className="w-6 h-full flex items-center justify-center text-slate-400 hover:text-white active:scale-90 transition-transform cursor-pointer"
            title="Quick Glance Right (Key: Num 6)"
          >
            <ChevronRight className="w-5 h-5" />
          </button>
        </div>

        {/* Trailer Hitch */}
        <button
          onClick={() => {
            sounds.playClick(300);
            haptics.click();
            onToggleTrailer();
          }}
          className={`rounded-xl border p-1 flex flex-col items-center justify-center h-14 transition-all cursor-pointer active:scale-95 ${
            state.trailerAttached
              ? 'bg-[#281d11] border-amber-500 text-amber-400 shadow-md shadow-amber-500/25 ring-1 ring-amber-500/40'
              : 'bg-[#10151e] border-white/10 text-slate-500 hover:bg-[#161c28]'
          }`}
          title="Attach / Detach Trailer (Key: T)"
        >
          <Link className="w-5 h-5 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">TRAILER</span>
        </button>

        {/* Interior Light */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onToggleInteriorLight();
          }}
          className={`rounded-xl border p-1 flex flex-col items-center justify-center h-14 transition-all cursor-pointer active:scale-95 ${
            state.interiorLight
              ? 'bg-[#202533] border-cyan-400/60 text-cyan-300 shadow-md shadow-cyan-400/20'
              : 'bg-[#10151e] border-white/10 text-slate-400 hover:bg-[#161c28]'
          }`}
          title="Interior Cabin Light"
        >
          <div className={`w-3 h-1 rounded-full mb-1 ${state.interiorLight ? 'bg-cyan-300' : 'bg-slate-700'}`} />
          <Sun className="w-4 h-4 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">INTERIOR</span>
        </button>

        {/* Windshield Wipers */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onCycleWipers();
          }}
          className={`rounded-xl border p-1 flex flex-col items-center justify-center h-14 transition-all cursor-pointer active:scale-95 ${
            state.wipers > 0
              ? 'bg-[#132332] border-cyan-500 text-cyan-400 shadow-md shadow-cyan-500/20'
              : 'bg-[#10151e] border-white/10 text-slate-400 hover:bg-[#161c28]'
          }`}
          title={`Windshield Wipers: ${wiperLabels[state.wipers]} (Key: P)`}
        >
          <div className={`w-3 h-1 rounded-full mb-1 ${state.wipers > 0 ? 'bg-cyan-400' : 'bg-slate-700'}`} />
          <CloudRain className="w-4 h-4 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">WIPERS</span>
        </button>

        {/* Parking Brake */}
        <button
          onClick={() => {
            haptics.airBrake();
            onToggleParkingBrake();
          }}
          className={`rounded-xl border p-1 flex flex-col items-center justify-center h-14 transition-all cursor-pointer active:scale-95 ${
            state.parkingBrake
              ? 'bg-[#2d1212] border-red-500 text-red-500 shadow-lg shadow-red-500/35 ring-1 ring-red-500/50'
              : 'bg-[#10151e] border-white/10 text-slate-500 hover:bg-[#161c28]'
          }`}
          title="Air Parking Brake (Key: Space)"
        >
          <CircleDot className="w-5 h-5 mb-0.5" />
          <span className="text-[8px] font-mono-nums font-bold tracking-tight">P-BRAKE</span>
        </button>
      </div>

      {/* 4. REALISTIC CB RADIO CONSOLE */}
      <div className="w-full bg-[#0d121a] border border-white/10 rounded-2xl p-2.5 shadow-2xl flex items-center justify-between gap-3">
        {/* Left: Volume / Mute Button */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onToggleCbMute();
          }}
          className={`w-11 h-11 rounded-xl border flex flex-col items-center justify-center transition-all cursor-pointer active:scale-95 shrink-0 ${
            state.cbRadio.isMuted
              ? 'bg-red-500/10 border-red-500/30 text-red-400'
              : 'bg-[#171e2c] border-white/10 text-amber-400 hover:bg-[#1f2738]'
          }`}
          title="CB Radio Volume / Mute"
        >
          {state.cbRadio.isMuted ? <VolumeX className="w-5 h-5" /> : <Volume2 className="w-5 h-5" />}
          <span className="text-[6px] font-mono-nums font-bold text-slate-400 mt-0.5">VOL / MUTE</span>
        </button>

        {/* Center: Backlit Amber LCD Screen */}
        <div className="flex-1 bg-[#161205] border border-amber-500/30 rounded-xl px-3 py-2 flex flex-col justify-between shadow-inner">
          {/* Top row: Status, Channel Name, Time */}
          <div className="flex items-center justify-between text-[11px] font-mono-nums font-bold text-amber-400 leading-tight">
            <div className="flex items-center gap-2">
              <span className={`w-2 h-2 rounded-full ${state.cbRadio.isTransmitting ? 'bg-red-500 animate-ping' : 'bg-emerald-400'}`} />
              <span>CB CH {state.cbRadio.channel} HIGHWAY</span>
            </div>
            <div className="text-amber-300">10:42 CET</div>
          </div>

          {/* Middle row: Frequency & RF Meter */}
          <div className="flex items-center justify-between text-[10px] font-mono-nums text-amber-500/90 my-1">
            <span>{state.cbRadio.frequency} · Trucker CB</span>
            <div className="flex items-center gap-0.5">
              {signalBars.map((bar, i) => (
                <div
                  key={i}
                  className="w-1 rounded-sm bg-amber-400"
                  style={{ height: `${bar * 2.5}px` }}
                />
              ))}
            </div>
          </div>

          {/* Bottom row: Preset Channel Buttons */}
          <div className="flex items-center justify-between gap-1 pt-1 border-t border-amber-500/20">
            {[1, 2, 3, 4, 5, 6].map((ch) => (
              <button
                key={ch}
                onClick={() => {
                  sounds.playClick();
                  haptics.click();
                  onSelectCbChannel(ch);
                }}
                className={`flex-1 py-0.5 rounded text-[9px] font-mono-nums font-bold transition-all cursor-pointer ${
                  state.cbRadio.channel === (ch === 1 ? 19 : ch === 2 ? 9 : ch * 3)
                    ? 'bg-amber-500 text-black shadow-sm'
                    : 'bg-black/40 text-amber-400/80 hover:bg-amber-500/20'
                }`}
              >
                {ch}
              </button>
            ))}
          </div>
        </div>

        {/* Right: Push-To-Talk / Tune Button */}
        <button
          onPointerDown={() => {
            sounds.playCbSquelch(true);
            haptics.radioPtt();
            onCbPttDown();
          }}
          onPointerUp={() => {
            sounds.playCbSquelch(false);
            onCbPttUp();
          }}
          className={`w-11 h-11 rounded-xl border flex flex-col items-center justify-center transition-all cursor-pointer active:scale-95 shrink-0 ${
            state.cbRadio.isTransmitting
              ? 'bg-red-600/30 border-red-500 text-red-400 animate-pulse'
              : 'bg-[#171e2c] border-white/10 text-slate-300 hover:bg-[#1f2738]'
          }`}
          title="Hold to Transmit (PTT)"
        >
          <Radio className="w-5 h-5 mb-0.5" />
          <span className="text-[7px] font-mono-nums font-bold tracking-tight">TUNE</span>
        </button>
      </div>
    </div>
  );
};
