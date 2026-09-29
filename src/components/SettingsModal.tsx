import React from 'react';
import { X, Settings, Disc3, Vibrate, Volume2, ShieldCheck } from 'lucide-react';
import { ControllerSettings } from '../types/controller';
import { sounds } from '../services/soundEffects';
import { haptics } from '../services/haptics';

interface SettingsModalProps {
  isOpen: boolean;
  onClose: () => void;
  settings: ControllerSettings;
  onUpdateSettings: (newSettings: Partial<ControllerSettings>) => void;
}

export const SettingsModal: React.FC<SettingsModalProps> = ({
  isOpen,
  onClose,
  settings,
  onUpdateSettings,
}) => {
  if (!isOpen) return null;

  const wheelAngles = [360, 540, 900, 1080, 1800];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 bg-black/80 backdrop-blur-md">
      <div className="bg-[#0f141d] border border-white/10 rounded-2xl w-full max-w-2xl max-h-[85vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-150">
        {/* Header */}
        <div className="px-6 py-4 border-b border-white/5 flex items-center justify-between bg-[#0b0e14]">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-slate-700 to-slate-900 flex items-center justify-center shadow-lg border border-white/10">
              <Settings className="w-5 h-5 text-white" />
            </div>
            <div>
              <h2 className="font-display font-bold text-lg text-white">
                TruckController Settings
              </h2>
              <p className="text-xs text-slate-400">
                Calibrate 900° steering wheel physics, haptic shocks &amp; transmission
              </p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="p-1.5 rounded-lg bg-white/5 hover:bg-white/10 text-slate-400 hover:text-white transition-all cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Settings Body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-6 bg-[#090d13] text-xs font-mono-nums">
          {/* Section 1: Steering Wheel Physics */}
          <div className="space-y-4">
            <div className="flex items-center gap-2 text-white font-semibold pb-1 border-b border-white/5">
              <Disc3 className="w-4 h-4 text-orange-400" />
              <span>Steering Wheel Calibration</span>
            </div>

            {/* Wheel Max Degrees */}
            <div className="space-y-1.5">
              <label className="text-slate-400 block">
                Lock-to-Lock Max Degrees: <strong className="text-amber-400">{settings.wheelMaxAngle}°</strong>
              </label>
              <div className="grid grid-cols-5 gap-2">
                {wheelAngles.map((deg) => (
                  <button
                    key={deg}
                    onClick={() => {
                      sounds.playClick();
                      haptics.click();
                      onUpdateSettings({ wheelMaxAngle: deg });
                    }}
                    className={`py-2 rounded-lg border text-center font-bold transition-all cursor-pointer ${
                      settings.wheelMaxAngle === deg
                        ? 'bg-orange-500/20 border-orange-500 text-orange-400 shadow-sm shadow-orange-500/30'
                        : 'bg-[#10141d] border-white/5 text-slate-400 hover:bg-[#161c28]'
                    }`}
                  >
                    {deg}°
                  </button>
                ))}
              </div>
            </div>

            {/* Auto-Centering Spring */}
            <div className="space-y-1.5">
              <div className="flex justify-between text-slate-400">
                <span>Auto-Centering Spring Force:</span>
                <strong className="text-white">{settings.autoCenterSpring}%</strong>
              </div>
              <input
                type="range"
                min="0"
                max="100"
                value={settings.autoCenterSpring}
                onChange={(e) => onUpdateSettings({ autoCenterSpring: Number(e.target.value) })}
                className="w-full accent-orange-500 cursor-pointer"
              />
            </div>

            {/* Non-Linearity */}
            <div className="space-y-1.5">
              <div className="flex justify-between text-slate-400">
                <span>Steering Non-Linearity (Precision at Center):</span>
                <strong className="text-white">{settings.nonLinearity.toFixed(1)}x</strong>
              </div>
              <input
                type="range"
                min="1.0"
                max="2.5"
                step="0.1"
                value={settings.nonLinearity}
                onChange={(e) => onUpdateSettings({ nonLinearity: Number(e.target.value) })}
                className="w-full accent-orange-500 cursor-pointer"
              />
            </div>
          </div>

          {/* Section 2: Haptic Feedback */}
          <div className="space-y-4">
            <div className="flex items-center gap-2 text-white font-semibold pb-1 border-b border-white/5">
              <Vibrate className="w-4 h-4 text-emerald-400" />
              <span>Haptic Force Feedback Engine</span>
            </div>

            <div className="flex items-center justify-between p-3 rounded-xl bg-[#0e131b] border border-white/5">
              <div>
                <span className="font-semibold text-white block">Vibration / Force Feedback</span>
                <span className="text-[11px] text-slate-500">
                  Haptic pulses for gear shifts, air brake hiss, and wheel lock hits
                </span>
              </div>
              <button
                onClick={() => {
                  sounds.playClick();
                  haptics.click();
                  onUpdateSettings({ hapticsEnabled: !settings.hapticsEnabled });
                }}
                className={`w-12 h-6 rounded-full transition-colors relative cursor-pointer ${
                  settings.hapticsEnabled ? 'bg-emerald-500' : 'bg-slate-700'
                }`}
              >
                <div
                  className={`w-4 h-4 rounded-full bg-white absolute top-1 transition-transform ${
                    settings.hapticsEnabled ? 'left-7' : 'left-1'
                  }`}
                />
              </button>
            </div>

            {settings.hapticsEnabled && (
              <div className="space-y-1.5">
                <div className="flex justify-between text-slate-400">
                  <span>Haptic Intensity:</span>
                  <strong className="text-emerald-400">{settings.hapticIntensity}%</strong>
                </div>
                <input
                  type="range"
                  min="20"
                  max="100"
                  value={settings.hapticIntensity}
                  onChange={(e) => {
                    const val = Number(e.target.value);
                    onUpdateSettings({ hapticIntensity: val });
                    haptics.configure(true, val);
                    haptics.gearShift();
                  }}
                  className="w-full accent-emerald-500 cursor-pointer"
                />
              </div>
            )}
          </div>

          {/* Section 3: Sound Synthesizer */}
          <div className="space-y-4">
            <div className="flex items-center gap-2 text-white font-semibold pb-1 border-b border-white/5">
              <Volume2 className="w-4 h-4 text-cyan-400" />
              <span>Cockpit Audio Synthesizer</span>
            </div>

            <div className="flex items-center justify-between p-3 rounded-xl bg-[#0e131b] border border-white/5">
              <div>
                <span className="font-semibold text-white block">Realistic Truck Audio</span>
                <span className="text-[11px] text-slate-500">
                  Diesel engine idle rumble, pneumatic air brakes, turn signals, CB squelch
                </span>
              </div>
              <button
                onClick={() => {
                  sounds.playClick();
                  haptics.click();
                  onUpdateSettings({ soundEnabled: !settings.soundEnabled });
                  sounds.setMute(settings.soundEnabled);
                }}
                className={`w-12 h-6 rounded-full transition-colors relative cursor-pointer ${
                  settings.soundEnabled ? 'bg-cyan-500' : 'bg-slate-700'
                }`}
              >
                <div
                  className={`w-4 h-4 rounded-full bg-white absolute top-1 transition-transform ${
                    settings.soundEnabled ? 'left-7' : 'left-1'
                  }`}
                />
              </button>
            </div>

            {settings.soundEnabled && (
              <div className="space-y-1.5">
                <div className="flex justify-between text-slate-400">
                  <span>Master Audio Volume:</span>
                  <strong className="text-cyan-400">{settings.soundVolume}%</strong>
                </div>
                <input
                  type="range"
                  min="0"
                  max="100"
                  value={settings.soundVolume}
                  onChange={(e) => {
                    const val = Number(e.target.value);
                    onUpdateSettings({ soundVolume: val });
                    sounds.setVolume(val);
                  }}
                  className="w-full accent-cyan-500 cursor-pointer"
                />
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
