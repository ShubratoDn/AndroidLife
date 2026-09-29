import React from 'react';
import { Smartphone, Bluetooth, Sliders, Settings, Maximize, Disc3 } from 'lucide-react';
import { ControllerState, ControllerSettings } from '../types/controller';
import { sounds } from '../services/soundEffects';
import { haptics } from '../services/haptics';

interface HeaderBarProps {
  state: ControllerState;
  settings: ControllerSettings;
  onOpenAndroidModal: () => void;
  onOpenBluetoothModal: () => void;
  onOpenMappingModal: () => void;
  onOpenSettingsModal: () => void;
  onCycleShifterMode: () => void;
}

export const HeaderBar: React.FC<HeaderBarProps> = ({
  state,
  settings,
  onOpenAndroidModal,
  onOpenBluetoothModal,
  onOpenMappingModal,
  onOpenSettingsModal,
  onCycleShifterMode,
}) => {
  const toggleFullscreen = () => {
    sounds.playClick();
    haptics.click();
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => {});
    } else {
      document.exitFullscreen().catch(() => {});
    }
  };

  return (
    <header className="h-14 px-4 sm:px-6 flex items-center justify-between border-b border-white/5 bg-[#0b0e14]/90 backdrop-blur-md select-none shrink-0 z-30">
      {/* Zone 1: Logo & Connection Badge */}
      <div className="flex items-center gap-3">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-amber-500 to-orange-600 flex items-center justify-center shadow-lg shadow-orange-500/20 ring-1 ring-orange-400/40">
            <Disc3 className="w-5 h-5 text-white" />
          </div>
          <span className="font-display font-bold text-lg tracking-wider text-white">
            TruckController <span className="text-orange-500">Pro</span>
          </span>
        </div>

        {/* Connection status pill */}
        <button
          onClick={onOpenBluetoothModal}
          className={`px-2.5 py-1 rounded-full text-xs font-mono-nums flex items-center gap-1.5 transition-all cursor-pointer ${
            state.connection === 'connected'
              ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
              : state.connection === 'pairing'
              ? 'bg-amber-500/15 text-amber-400 border border-amber-500/30 animate-pulse'
              : 'bg-slate-800/80 text-slate-400 border border-slate-700/50 hover:bg-slate-800'
          }`}
          title="Click to manage Bluetooth & PC Bridge"
        >
          <span
            className={`w-2 h-2 rounded-full ${
              state.connection === 'connected'
                ? 'bg-emerald-400 shadow-sm shadow-emerald-400'
                : state.connection === 'pairing'
                ? 'bg-amber-400 shadow-sm shadow-amber-400'
                : 'bg-slate-500'
            }`}
          />
          <span>{state.connection === 'connected' ? 'Connected (BT HID)' : state.connection === 'pairing' ? 'Pairing...' : 'Offline'}</span>
        </button>
      </div>

      {/* Zone 2: Shifter & Wheel Mode Indicator */}
      <div className="hidden md:flex items-center gap-2">
        <button
          onClick={() => {
            sounds.playClick(600);
            haptics.click();
            onCycleShifterMode();
          }}
          className="px-3 py-1 rounded-md bg-[#121721] hover:bg-[#1a2130] border border-white/5 text-xs text-slate-300 font-mono-nums flex items-center gap-2 transition-all cursor-pointer"
        >
          <span>
            SHIFTER: <strong className="text-amber-400 uppercase font-semibold">{state.shifterMode}</strong>
          </span>
          <span className="text-slate-600">·</span>
          <span>
            WHEEL: <strong className="text-slate-200">{settings.wheelMaxAngle}°</strong>
          </span>
        </button>
      </div>

      {/* Zone 3: Actions */}
      <div className="flex items-center gap-2">
        {/* Android APK Button */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onOpenAndroidModal();
          }}
          className="px-3.5 py-1.5 rounded-lg bg-gradient-to-r from-orange-500 to-amber-500 hover:from-orange-400 hover:to-amber-400 text-white font-medium text-xs flex items-center gap-2 shadow-md shadow-orange-500/25 active:scale-95 transition-all cursor-pointer"
        >
          <Smartphone className="w-3.5 h-3.5" />
          <span className="tracking-wide uppercase font-semibold text-[11px]">Android APK</span>
        </button>

        {/* Bluetooth Modal Icon */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onOpenBluetoothModal();
          }}
          className={`p-2 rounded-lg border transition-all cursor-pointer ${
            state.connection === 'connected'
              ? 'bg-blue-500/10 border-blue-500/40 text-blue-400'
              : 'bg-[#151a24] hover:bg-[#1c2331] border-white/5 text-slate-300'
          }`}
          title="Bluetooth HID Setup"
        >
          <Bluetooth className="w-4 h-4" />
        </button>

        {/* Mappable Controls Icon */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onOpenMappingModal();
          }}
          className="p-2 rounded-lg bg-[#151a24] hover:bg-[#1c2331] border border-white/5 text-slate-300 transition-all cursor-pointer"
          title="Custom Button Mappings"
        >
          <Sliders className="w-4 h-4" />
        </button>

        {/* Settings Icon */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onOpenSettingsModal();
          }}
          className="p-2 rounded-lg bg-[#151a24] hover:bg-[#1c2331] border border-white/5 text-slate-300 transition-all cursor-pointer"
          title="Controller Settings"
        >
          <Settings className="w-4 h-4" />
        </button>

        {/* Fullscreen Icon */}
        <button
          onClick={toggleFullscreen}
          className="p-2 rounded-lg bg-[#151a24] hover:bg-[#1c2331] border border-white/5 text-slate-300 transition-all cursor-pointer"
          title="Fullscreen Mode"
        >
          <Maximize className="w-4 h-4" />
        </button>
      </div>
    </header>
  );
};
