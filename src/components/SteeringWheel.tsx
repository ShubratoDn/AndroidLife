import React, { useRef, useState, useEffect, useCallback } from 'react';
import { ArrowLeft, ArrowRight, AlertTriangle, RotateCcw, Volume2 } from 'lucide-react';
import { ControllerState, ControllerSettings } from '../types/controller';
import { sounds } from '../services/soundEffects';
import { haptics } from '../services/haptics';

interface SteeringWheelProps {
  state: ControllerState;
  settings: ControllerSettings;
  onAngleChange: (angle: number, normalized: number) => void;
  onResetCenter: () => void;
  onToggleHazard: () => void;
  onToggleTurnSignal: (signal: 'left' | 'right') => void;
  onHornDown: () => void;
  onHornUp: () => void;
}

export const SteeringWheel: React.FC<SteeringWheelProps> = ({
  state,
  settings,
  onAngleChange,
  onResetCenter,
  onToggleHazard,
  onToggleTurnSignal,
  onHornDown,
  onHornUp,
}) => {
  const wheelRef = useRef<HTMLDivElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  const prevAngleRef = useRef(0);
  const currentAngleRef = useRef(state.steeringAngle);
  const springAnimationRef = useRef<number | null>(null);

  // Sync angle ref
  useEffect(() => {
    currentAngleRef.current = state.steeringAngle;
  }, [state.steeringAngle]);

  const maxDegrees = settings.wheelMaxAngle;
  const limitAngle = maxDegrees / 2;

  // Pointer drag logic
  const handlePointerDown = (e: React.PointerEvent) => {
    if (springAnimationRef.current) {
      cancelAnimationFrame(springAnimationRef.current);
      springAnimationRef.current = null;
    }
    setIsDragging(true);
    haptics.click();

    const rect = wheelRef.current?.getBoundingClientRect();
    if (!rect) return;
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const touchAngle = (Math.atan2(e.clientY - cy, e.clientX - cx) * 180) / Math.PI;
    prevAngleRef.current = touchAngle;

    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isDragging || !wheelRef.current) return;
    const rect = wheelRef.current.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const touchAngle = (Math.atan2(e.clientY - cy, e.clientX - cx) * 180) / Math.PI;

    let delta = touchAngle - prevAngleRef.current;
    if (delta > 180) delta -= 360;
    if (delta < -180) delta += 360;

    let newAngle = currentAngleRef.current + delta;
    if (newAngle > limitAngle) {
      newAngle = limitAngle;
      haptics.wheelLock();
    } else if (newAngle < -limitAngle) {
      newAngle = -limitAngle;
      haptics.wheelLock();
    }

    currentAngleRef.current = newAngle;
    prevAngleRef.current = touchAngle;

    // Apply non-linearity curve if configured
    let normalized = newAngle / limitAngle;
    if (settings.nonLinearity > 1.0) {
      const sign = Math.sign(normalized);
      normalized = sign * Math.pow(Math.abs(normalized), settings.nonLinearity);
    }

    onAngleChange(newAngle, normalized);
  };

  const startCenteringSpring = useCallback(() => {
    if (settings.autoCenterSpring <= 0) return;
    const springRate = (settings.autoCenterSpring / 100) * 0.18;

    const step = () => {
      const current = currentAngleRef.current;
      if (Math.abs(current) > 0.4) {
        const next = current * (1 - springRate);
        currentAngleRef.current = next;
        let normalized = next / limitAngle;
        if (settings.nonLinearity > 1.0) {
          const sign = Math.sign(normalized);
          normalized = sign * Math.pow(Math.abs(normalized), settings.nonLinearity);
        }
        onAngleChange(next, normalized);
        springAnimationRef.current = requestAnimationFrame(step);
      } else {
        currentAngleRef.current = 0;
        onAngleChange(0, 0);
        springAnimationRef.current = null;
      }
    };
    springAnimationRef.current = requestAnimationFrame(step);
  }, [limitAngle, onAngleChange, settings.autoCenterSpring, settings.nonLinearity]);

  const handlePointerUp = (e: React.PointerEvent) => {
    setIsDragging(false);
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {
      // ignore
    }
    startCenteringSpring();
  };

  // Keyboard steering (A / D or Left / Right arrow keys)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;
      if (e.key === 'a' || e.key === 'A' || e.key === 'ArrowLeft') {
        const next = Math.max(-limitAngle, currentAngleRef.current - 22);
        currentAngleRef.current = next;
        onAngleChange(next, next / limitAngle);
      } else if (e.key === 'd' || e.key === 'D' || e.key === 'ArrowRight') {
        const next = Math.min(limitAngle, currentAngleRef.current + 22);
        currentAngleRef.current = next;
        onAngleChange(next, next / limitAngle);
      } else if (e.key === 'h' || e.key === 'H') {
        onHornDown();
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement)?.tagName)) return;
      if (['a', 'A', 'd', 'D', 'ArrowLeft', 'ArrowRight'].includes(e.key)) {
        startCenteringSpring();
      } else if (e.key === 'h' || e.key === 'H') {
        onHornUp();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
    };
  }, [limitAngle, onAngleChange, onHornDown, onHornUp, startCenteringSpring]);

  // Turn signal audio pulse
  useEffect(() => {
    if (state.turnSignal === 'off' && !state.hazardLights) return;
    let isTock = false;
    const timer = setInterval(() => {
      sounds.playTurnSignal(isTock);
      isTock = !isTock;
    }, 380);
    return () => clearInterval(timer);
  }, [state.turnSignal, state.hazardLights]);

  const displayAngle = Math.round(state.steeringAngle);
  const displayOut = Math.round(state.steeringNormalized * 100);

  return (
    <div className="flex flex-col items-center select-none touch-none">
      {/* Top Turn Indicator & Hazard Bar */}
      <div className="flex items-center gap-5 mb-3">
        {/* Left Turn Signal */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onToggleTurnSignal('left');
          }}
          className={`w-10 h-10 rounded-xl flex items-center justify-center border transition-all cursor-pointer ${
            state.turnSignal === 'left' || state.hazardLights
              ? 'bg-emerald-500/20 border-emerald-400 text-emerald-400 animate-turn shadow-lg shadow-emerald-500/30'
              : 'bg-[#141a24] hover:bg-[#1a2230] border-white/10 text-slate-400'
          }`}
          title="Left Turn Signal (Key: [)"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>

        {/* Hazard Warning Flasher */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onToggleHazard();
          }}
          className={`w-12 h-10 rounded-xl flex items-center justify-center border transition-all cursor-pointer ${
            state.hazardLights
              ? 'bg-red-600/30 border-red-500 text-red-500 animate-hazard shadow-lg shadow-red-500/40'
              : 'bg-[#161c27] hover:bg-[#1f2737] border-white/10 text-slate-400'
          }`}
          title="Hazard Warning (Key: F)"
        >
          <AlertTriangle className="w-5 h-5 text-red-500" />
        </button>

        {/* Right Turn Signal */}
        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onToggleTurnSignal('right');
          }}
          className={`w-10 h-10 rounded-xl flex items-center justify-center border transition-all cursor-pointer ${
            state.turnSignal === 'right' || state.hazardLights
              ? 'bg-emerald-500/20 border-emerald-400 text-emerald-400 animate-turn shadow-lg shadow-emerald-500/30'
              : 'bg-[#141a24] hover:bg-[#1a2230] border-white/10 text-slate-400'
          }`}
          title="Right Turn Signal (Key: ])"
        >
          <ArrowRight className="w-5 h-5" />
        </button>
      </div>

      {/* Steering Wheel Outer Container */}
      <div
        ref={wheelRef}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        className="w-64 h-64 sm:w-72 sm:h-72 lg:w-80 lg:h-80 relative flex items-center justify-center cursor-grab active:cursor-grabbing"
      >
        {/* Shadowed wheel backplate */}
        <div className="absolute inset-0 rounded-full bg-black/40 blur-xl pointer-events-none" />

        {/* Rotating Wheel Group */}
        <div
          className="w-full h-full relative rounded-full"
          style={{
            transform: `rotate(${state.steeringAngle}deg)`,
            transition: isDragging ? 'none' : 'transform 0.08s cubic-bezier(0.1, 1, 0.3, 1)',
            willChange: 'transform',
          }}
        >
          {/* Wheel SVG with high realistic details */}
          <svg viewBox="0 0 400 400" className="w-full h-full drop-shadow-2xl">
            <defs>
              {/* Outer Rim Leather Gradient */}
              <radialGradient id="rimGradient" cx="50%" cy="50%" r="50%">
                <stop offset="78%" stopColor="#1e2531" />
                <stop offset="85%" stopColor="#151b24" />
                <stop offset="95%" stopColor="#0d1117" />
                <stop offset="100%" stopColor="#252d3c" />
              </radialGradient>

              {/* Brushed Metallic Spokes Gradient */}
              <linearGradient id="metalSpoke" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stopColor="#2c3545" />
                <stop offset="40%" stopColor="#1c222c" />
                <stop offset="100%" stopColor="#13171f" />
              </linearGradient>

              {/* Perforation Pattern */}
              <pattern id="perforation" x="0" y="0" width="12" height="12" patternUnits="userSpaceOnUse">
                <circle cx="6" cy="6" r="1.2" fill="#0b0e14" opacity="0.6" />
              </pattern>
            </defs>

            {/* Outer Rim Body */}
            <circle cx="200" cy="200" r="176" fill="none" stroke="url(#rimGradient)" strokeWidth="48" />

            {/* Perforated Grip Overlay */}
            <circle
              cx="200"
              cy="200"
              r="176"
              fill="none"
              stroke="url(#perforation)"
              strokeWidth="42"
              opacity="0.8"
            />

            {/* Dual Red Contrast Stitching */}
            <circle
              cx="200"
              cy="200"
              r="154"
              fill="none"
              stroke="#ef4444"
              strokeWidth="1.8"
              strokeDasharray="4 4"
              opacity="0.85"
            />
            <circle
              cx="200"
              cy="200"
              r="198"
              fill="none"
              stroke="#ef4444"
              strokeWidth="1.5"
              strokeDasharray="4 4"
              opacity="0.6"
            />

            {/* High-Visibility 12 O'Clock Orange Marker Stripe */}
            <path
              d="M 190 24 A 176 176 0 0 1 210 24"
              fill="none"
              stroke="#f97316"
              strokeWidth="48"
              strokeLinecap="butt"
            />

            {/* Tri-Spoke Heavy Chassis */}
            {/* Bottom Vertical Spoke */}
            <path d="M 180 200 L 176 340 L 224 340 L 220 200 Z" fill="url(#metalSpoke)" />
            {/* Left Horizontal Spoke */}
            <path d="M 200 180 L 46 174 L 46 226 L 200 220 Z" fill="url(#metalSpoke)" />
            {/* Right Horizontal Spoke */}
            <path d="M 200 180 L 354 174 L 354 226 L 200 220 Z" fill="url(#metalSpoke)" />

            {/* Brushed Spoke Chamfers */}
            <line x1="48" y1="184" x2="160" y2="188" stroke="#475569" strokeWidth="1.5" opacity="0.4" />
            <line x1="48" y1="216" x2="160" y2="212" stroke="#475569" strokeWidth="1.5" opacity="0.4" />
            <line x1="352" y1="184" x2="240" y2="188" stroke="#475569" strokeWidth="1.5" opacity="0.4" />
            <line x1="352" y1="216" x2="240" y2="212" stroke="#475569" strokeWidth="1.5" opacity="0.4" />

            {/* Top Spoke Integrated Digital Display Cluster */}
            <rect x="168" y="112" width="64" height="42" rx="8" fill="#080b0f" stroke="#2a3445" strokeWidth="1.5" />
            <text x="200" y="132" fill="#38bdf8" fontSize="13" fontFamily="Chakra Petch" fontWeight="bold" textAnchor="middle">
              85
            </text>
            <text x="200" y="146" fill="#64748b" fontSize="8" fontFamily="JetBrains Mono" textAnchor="middle">
              KM/H
            </text>

            {/* Central Boss Outer Bezel */}
            <circle cx="200" cy="200" r="74" fill="#111621" stroke="#2d3748" strokeWidth="3" />
            <circle cx="200" cy="200" r="66" fill="#0a0d13" stroke="#1f2737" strokeWidth="1.5" />
          </svg>

          {/* Center Horn Boss Button */}
          <button
            onPointerDown={(e) => {
              e.stopPropagation();
              sounds.startHorn();
              haptics.horn();
              onHornDown();
            }}
            onPointerUp={(e) => {
              e.stopPropagation();
              sounds.stopHorn();
              onHornUp();
            }}
            className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-28 h-28 rounded-full flex flex-col items-center justify-center bg-gradient-to-b from-[#18202c] to-[#0c1017] hover:from-[#1d2634] border border-white/10 active:scale-95 transition-transform shadow-inner shadow-black cursor-pointer group"
            title="Horn (Hold / Key: H)"
          >
            <Volume2 className="w-6 h-6 text-slate-400 group-hover:text-amber-400 transition-colors" />
            <span className="text-[11px] font-mono-nums font-bold tracking-wider text-slate-300 group-hover:text-white mt-1">
              HORN
            </span>
          </button>
        </div>
      </div>

      {/* Wheel Angle & Centering Readout */}
      <div className="flex items-center justify-between w-64 sm:w-72 mt-3 px-2 text-xs font-mono-nums text-slate-400">
        <div>
          <span>ANGLE: </span>
          <strong className="text-white">{displayAngle > 0 ? `+${displayAngle}` : displayAngle}°</strong>
        </div>

        <button
          onClick={() => {
            sounds.playClick();
            haptics.click();
            onResetCenter();
          }}
          className="px-2.5 py-1 rounded-md bg-[#141a24] hover:bg-[#1d2534] border border-white/10 text-slate-300 hover:text-white flex items-center gap-1 transition-all cursor-pointer"
          title="Snap steering back to dead center"
        >
          <RotateCcw className="w-3.5 h-3.5" />
          <span>0°</span>
        </button>

        <div>
          <span>OUT: </span>
          <strong className="text-amber-400">{displayOut}%</strong>
        </div>
      </div>
    </div>
  );
};
