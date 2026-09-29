import React, { useRef, useState, useEffect } from 'react';
import { Eye } from 'lucide-react';
import { haptics } from '../services/haptics';

interface LookPanWidgetProps {
  lookPan: { x: number; y: number };
  onLookChange: (pos: { x: number; y: number }) => void;
}

export const LookPanWidget: React.FC<LookPanWidgetProps> = ({ lookPan, onLookChange }) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [isDragging, setIsDragging] = useState(false);

  const handlePointerDown = (e: React.PointerEvent) => {
    setIsDragging(true);
    haptics.click();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    updatePosition(e.clientX, e.clientY);
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isDragging) return;
    updatePosition(e.clientX, e.clientY);
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    setIsDragging(false);
    try {
      (e.target as HTMLElement).releasePointerCapture(e.pointerId);
    } catch {
      // ignore
    }
    // Spring return to center
    onLookChange({ x: 0, y: 0 });
  };

  const updatePosition = (clientX: number, clientY: number) => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const radius = rect.width / 2;

    const dx = clientX - cx;
    const dy = clientY - cy;
    const dist = Math.hypot(dx, dy);

    const clampedDist = Math.min(dist, radius);
    const angle = Math.atan2(dy, dx);

    const nx = (Math.cos(angle) * clampedDist) / radius;
    const ny = (Math.sin(angle) * clampedDist) / radius;

    onLookChange({ x: Number(nx.toFixed(2)), y: Number(ny.toFixed(2)) });
  };

  // Ensure spring back if dragging ends outside
  useEffect(() => {
    const handleGlobalUp = () => {
      if (isDragging) {
        setIsDragging(false);
        onLookChange({ x: 0, y: 0 });
      }
    };
    window.addEventListener('pointerup', handleGlobalUp);
    return () => window.removeEventListener('pointerup', handleGlobalUp);
  }, [isDragging, onLookChange]);

  return (
    <div className="flex flex-col items-center select-none touch-none">
      <div
        ref={containerRef}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        className="w-24 h-24 sm:w-28 sm:h-28 rounded-full relative flex items-center justify-center cursor-grab active:cursor-grabbing border border-white/10 bg-[#0d1117] shadow-inner shadow-black"
        style={{
          background: 'radial-gradient(circle, #131924 0%, #0a0d13 100%)',
        }}
      >
        {/* Radar concentric circular rings */}
        <div className="absolute inset-2 rounded-full border border-white/5 pointer-events-none" />
        <div className="absolute inset-5 rounded-full border border-white/5 pointer-events-none" />
        <div className="absolute inset-8 rounded-full border border-white/5 pointer-events-none" />

        {/* Center Crosshairs */}
        <div className="absolute w-full h-[1px] bg-white/5 pointer-events-none" />
        <div className="absolute h-full w-[1px] bg-white/5 pointer-events-none" />

        {/* Floating Reticle Thumb with Eye Icon */}
        <div
          className="w-10 h-10 rounded-full bg-[#1b2230] border border-cyan-500/40 shadow-lg shadow-black flex items-center justify-center transition-transform duration-75 pointer-events-none"
          style={{
            transform: `translate(${lookPan.x * 34}px, ${lookPan.y * 34}px)`,
            boxShadow: isDragging ? '0 0 14px rgba(6, 182, 212, 0.4)' : 'none',
          }}
        >
          <Eye className={`w-5 h-5 ${isDragging ? 'text-cyan-400' : 'text-slate-400'}`} />
        </div>
      </div>

      <span className="text-[10px] font-mono-nums font-semibold tracking-widest text-slate-500 mt-2">
        LOOK / PAN
      </span>
    </div>
  );
};
