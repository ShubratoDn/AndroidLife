import React, { useState } from 'react';
import { X, Sliders, RotateCcw, Check, Sparkles } from 'lucide-react';
import { ButtonMapping } from '../types/controller';
import { DEFAULT_MAPPINGS } from '../data/defaultMappings';
import { sounds } from '../services/soundEffects';
import { haptics } from '../services/haptics';

interface ButtonMappingModalProps {
  isOpen: boolean;
  onClose: () => void;
  mappings: ButtonMapping[];
  onSaveMappings: (mappings: ButtonMapping[]) => void;
}

export const ButtonMappingModal: React.FC<ButtonMappingModalProps> = ({
  isOpen,
  onClose,
  mappings,
  onSaveMappings,
}) => {
  const [currentMappings, setCurrentMappings] = useState<ButtonMapping[]>(mappings);
  const [selectedCategory, setSelectedCategory] = useState<string>('All');
  const [editingId, setEditingId] = useState<string | null>(null);

  if (!isOpen) return null;

  const categories = ['All', 'Transmission', 'Drive', 'Lighting', 'Cabin', 'Camera'];

  const filtered =
    selectedCategory === 'All'
      ? currentMappings
      : currentMappings.filter((m) => m.category === selectedCategory);

  const handleKeyChange = (id: string, newKey: string) => {
    setCurrentMappings((prev) =>
      prev.map((m) => (m.id === id ? { ...m, defaultKey: newKey } : m))
    );
    setEditingId(null);
    sounds.playClick();
    haptics.click();
  };

  const handleResetDefaults = () => {
    sounds.playClick();
    haptics.click();
    setCurrentMappings(DEFAULT_MAPPINGS);
  };

  const handleApplyPreset = (presetName: string) => {
    sounds.playClick();
    haptics.click();
    if (presetName === 'eaton') {
      setCurrentMappings((prev) =>
        prev.map((m) => {
          if (m.id === 'gear_up') return { ...m, defaultKey: 'Shift', hidButton: 1 };
          if (m.id === 'gear_down') return { ...m, defaultKey: 'Ctrl', hidButton: 2 };
          if (m.id === 'splitter_toggle') return { ...m, defaultKey: 'CapsLock', hidButton: 3 };
          if (m.id === 'range_toggle') return { ...m, defaultKey: 'Tab', hidButton: 4 };
          return m;
        })
      );
    } else if (presetName === 'joystick') {
      setCurrentMappings((prev) =>
        prev.map((m, idx) => ({ ...m, hidButton: idx + 1 }))
      );
    }
  };

  const handleSave = () => {
    sounds.playClick();
    haptics.click();
    onSaveMappings(currentMappings);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 bg-black/80 backdrop-blur-md">
      <div className="bg-[#0f141d] border border-white/10 rounded-2xl w-full max-w-4xl h-[84vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-150">
        {/* Header */}
        <div className="px-6 py-4 border-b border-white/5 flex items-center justify-between bg-[#0b0e14]">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-amber-500 to-orange-600 flex items-center justify-center shadow-lg shadow-amber-500/20">
              <Sliders className="w-5 h-5 text-white" />
            </div>
            <div>
              <h2 className="font-display font-bold text-lg text-white">
                Custom Mappable Button Controls
              </h2>
              <p className="text-xs text-slate-400">
                Map Euro Truck Simulator 2 keyboard triggers and Bluetooth HID joystick buttons
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleResetDefaults}
              className="px-3 py-1.5 rounded-lg bg-[#141a24] hover:bg-[#1a2230] text-slate-300 text-xs font-mono-nums flex items-center gap-1.5 transition-all cursor-pointer"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              <span>Reset Defaults</span>
            </button>

            <button
              onClick={handleSave}
              className="px-4 py-1.5 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold flex items-center gap-1.5 transition-all cursor-pointer shadow-md shadow-emerald-600/30"
            >
              <Check className="w-4 h-4" />
              <span>Save &amp; Apply</span>
            </button>

            <button
              onClick={onClose}
              className="p-1.5 rounded-lg bg-white/5 hover:bg-white/10 text-slate-400 hover:text-white transition-all cursor-pointer"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Presets and Filter Bar */}
        <div className="px-6 py-2.5 border-b border-white/5 bg-[#0d1118] flex flex-wrap items-center justify-between gap-3 text-xs">
          {/* Categories */}
          <div className="flex items-center gap-1">
            {categories.map((cat) => (
              <button
                key={cat}
                onClick={() => setSelectedCategory(cat)}
                className={`px-3 py-1 rounded-md font-mono-nums transition-colors cursor-pointer ${
                  selectedCategory === cat
                    ? 'bg-amber-500 text-black font-semibold'
                    : 'text-slate-400 hover:text-white hover:bg-white/5'
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          {/* Quick Presets */}
          <div className="flex items-center gap-2">
            <span className="text-slate-500 text-[11px] font-mono-nums">Presets:</span>
            <button
              onClick={() => handleApplyPreset('eaton')}
              className="px-2.5 py-1 rounded bg-[#161c28] hover:bg-[#1f2738] text-slate-300 text-[11px] font-mono-nums flex items-center gap-1 transition-all cursor-pointer"
            >
              <Sparkles className="w-3 h-3 text-amber-400" />
              <span>18-Speed Shifter</span>
            </button>
            <button
              onClick={() => handleApplyPreset('joystick')}
              className="px-2.5 py-1 rounded bg-[#161c28] hover:bg-[#1f2738] text-slate-300 text-[11px] font-mono-nums flex items-center gap-1 transition-all cursor-pointer"
            >
              <Sparkles className="w-3 h-3 text-cyan-400" />
              <span>Direct HID Buttons</span>
            </button>
          </div>
        </div>

        {/* Mappings Table */}
        <div className="flex-1 overflow-y-auto p-4 sm:p-6 bg-[#090d13]">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {filtered.map((item) => (
              <div
                key={item.id}
                className="p-3 rounded-xl bg-[#0f141d] border border-white/5 hover:border-white/10 flex items-center justify-between gap-3 transition-colors"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-semibold text-white">{item.name}</span>
                    <span className="text-[10px] font-mono-nums px-1.5 py-0.5 rounded bg-white/5 text-slate-400">
                      {item.category}
                    </span>
                  </div>
                  <p className="text-xs text-slate-500 truncate mt-0.5">{item.description}</p>
                </div>

                <div className="flex items-center gap-2 shrink-0">
                  {/* HID Button pill */}
                  <span className="text-[10px] font-mono-nums text-slate-400 bg-[#161c27] px-2 py-1 rounded border border-white/5">
                    Btn {item.hidButton}
                  </span>

                  {/* Key binding button */}
                  {editingId === item.id ? (
                    <input
                      autoFocus
                      type="text"
                      maxLength={10}
                      defaultValue={item.defaultKey}
                      onKeyDown={(e) => {
                        e.preventDefault();
                        handleKeyChange(item.id, e.key.length === 1 ? e.key.toUpperCase() : e.key);
                      }}
                      onBlur={() => setEditingId(null)}
                      className="w-16 px-2 py-1 text-xs text-center font-mono-nums font-bold text-amber-400 bg-amber-500/20 border border-amber-400 rounded outline-none"
                    />
                  ) : (
                    <button
                      onClick={() => setEditingId(item.id)}
                      className="min-w-16 px-2 py-1 text-xs text-center font-mono-nums font-semibold text-slate-200 bg-[#1a2230] hover:bg-[#222d40] border border-white/10 rounded cursor-pointer transition-all"
                      title="Click to remap keyboard key"
                    >
                      {item.defaultKey}
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
