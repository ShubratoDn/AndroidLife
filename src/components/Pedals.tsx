import React, { useRef, useState, useEffect } from 'react';
import { sounds } from '../services/soundEffects';
import { haptics } from '../services/haptics';

interface PedalsProps {
  gas: number;
  brake: number;
  onGasChange: (val: number) => void;
  onBrakeChange: (val: number) => void;
}

export const Pedals: React.FC<PedalsProps> = ({ gas, brake, onGasChange, onBrakeChange }) => {
  const gasRef = useRef<HTMLDivElement>(null);
  const brakeRef = useRef<HTMLDivElement>(null);

  const [gasDragging, setGasDragging] = useState(false);
  const [brakeDragging, setBrakeDragging] = useState(false);

  // Keyboard controls for pedals: W / S or ArrowUp / ArrowDown
  useEffect(() => {
    let keyInterval: number | null = null;
    let gasPressed = false;
    let brakePressed = false;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;
      if (e.key === 'w' || e.key === 'W' || e.key === 'ArrowUp') {
        gasPressed = true;
      }
      if (e.key === 's' || e.key === 'S' || e.key === 'ArrowDown') {
        brakePressed = true;
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;
      if (e.key === 'w' || e.key === 'W' || e.key === 'ArrowUp') {
        gasPressed = false;
      }
      if (e.key === 's' || e.key === 'S' || e.key === 'ArrowDown') {
        brakePressed = false;
      }
    };

    keyInterval = window.setInterval(() => {
      if (gasPressed) {
        onGasChange(Math.min(100, gas + 15));
      } else if (!gasDragging && gas > 0) {
        onGasChange(Math.max(0, gas - 20));
      }

      if (brakePressed) {
        onBrakeChange(Math.min(100, brake + 18));
      } else if (!brakeDragging && brake > 0) {
        onBrakeChange(Math.max(0, brake - 25));
      }
    }, 20);

    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);

    return () => {
      if (keyInterval) clearInterval(keyInterval);
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
    };
  }, [brake, brakeDragging, gas, gasDragging, onBrakeChange, onGasChange]);

  // Pointer drag for Gas Pedal
  const handleGasDown = (e: React.PointerEvent) => {
    setGasDragging(true);
    haptics.click();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    updateGas(e.clientY);
  };

  const handleGasMove = (e: React.PointerEvent) => {
    if (!gasDragging) return;
    updateGas(e.clientY);
  };

  const handleGasUp = (e: React.PointerEvent) => {
    setGasDragging(false);
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {
      // ignore
    }
    onGasChange(0);
  };

  const updateGas = (clientY: number) => {
    if (!gasRef.current) return;
    const rect = gasRef.current.getBoundingClientRect();
    const clampedY = Math.max(rect.top, Math.min(rect.bottom, clientY));
    const pct = Math.round(((rect.bottom - clampedY) / rect.height) * 100);
    onGasChange(pct);
  };

  // Pointer drag for Brake Pedal
  const handleBrakeDown = (e: React.PointerEvent) => {
    setBrakeDragging(true);
    sounds.playAirBrake();
    haptics.click();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    updateBrake(e.clientY);
  };

  const handleBrakeMove = (e: React.PointerEvent) => {
    if (!brakeDragging) return;
    updateBrake(e.clientY);
  };

  const handleBrakeUp = (e: React.PointerEvent) => {
    setBrakeDragging(false);
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {
      // ignore
    }
    onBrakeChange(0);
  };

  const updateBrake = (clientY: number) => {
    if (!brakeRef.current) return;
    const rect = brakeRef.current.getBoundingClientRect();
    const clampedY = Math.max(rect.top, Math.min(rect.bottom, clientY));
    const pct = Math.round(((rect.bottom - clampedY) / rect.height) * 100);
    onBrakeChange(pct);
  };

  return (
    <div className="flex items-center gap-4 select-none touch-none">
      {/* BRAKE PEDAL */}
      <div className="flex flex-col items-center">
        <div
          ref={brakeRef}
          onPointerDown={handleBrakeDown}
          onPointerMove={handleBrakeMove}
          onPointerUp={handleBrakeUp}
          className="w-16 h-48 sm:w-18 sm:h-56 rounded-2xl relative bg-[#0e131b] border border-white/10 shadow-2xl flex flex-col justify-end p-1.5 overflow-hidden cursor-pointer"
        >
          {/* Active Brake Fill */}
          <div
            className="w-full rounded-xl bg-gradient-to-t from-red-600/80 to-red-500/30 transition-all duration-75 shadow-lg shadow-red-500/40"
            style={{ height: `${brake}%` }}
          />

          {/* Ribbed Grooves on Pedal Face */}
          <div className="absolute inset-0 flex flex-col justify-around p-3 pointer-events-none opacity-40">
            {[...Array(7)].map((_, i) => (
              <div key={i} className="w-full h-1.5 rounded-full bg-[#202735] border-t border-white/5" />
            ))}
          </div>

          {/* Glowing border if active */}
          <div
            className={`absolute inset-0 rounded-2xl border transition-all pointer-events-none ${
              brake > 0 ? 'border-red-500 shadow-md shadow-red-500/30' : 'border-white/5'
            }`}
          />
        </div>

        {/* Brake Label & Readout */}
        <span className="text-[11px] font-mono-nums font-bold text-red-500 mt-2 tracking-wider">
          BRAKE
        </span>
        <span className="text-xs font-mono-nums font-bold text-slate-300">
          {Math.round(brake)}%
        </span>
      </div>

      {/* GAS PEDAL */}
      <div className="flex flex-col items-center">
        <div
          ref={gasRef}
          onPointerDown={handleGasDown}
          onPointerMove={handleGasMove}
          onPointerUp={handleGasUp}
          className="w-16 h-48 sm:w-18 sm:h-56 rounded-2xl relative bg-[#0e131b] border border-white/10 shadow-2xl flex flex-col justify-end p-1.5 overflow-hidden cursor-pointer"
        >
          {/* Active Gas Fill */}
          <div
            className="w-full rounded-xl bg-gradient-to-t from-emerald-600/80 to-emerald-500/30 transition-all duration-75 shadow-lg shadow-emerald-500/40"
            style={{ height: `${gas}%` }}
          />

          {/* Ribbed Grooves on Pedal Face */}
          <div className="absolute inset-0 flex flex-col justify-around p-3 pointer-events-none opacity-40">
            {[...Array(7)].map((_, i) => (
              <div key={i} className="w-full h-1.5 rounded-full bg-[#202735] border-t border-white/5" />
            ))}
          </div>

          {/* Glowing border if active */}
          <div
            className={`absolute inset-0 rounded-2xl border transition-all pointer-events-none ${
              gas > 0 ? 'border-emerald-500 shadow-md shadow-emerald-500/30' : 'border-white/5'
            }`}
          />
        </div>

        {/* Gas Label & Readout */}
        <span className="text-[11px] font-mono-nums font-bold text-emerald-400 mt-2 tracking-wider">
          GAS
        </span>
        <span className="text-xs font-mono-nums font-bold text-slate-300">
          {Math.round(gas)}%
        </span>
      </div>
    </div>
  );
};
