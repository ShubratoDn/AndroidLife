import React from 'react';
import { ControllerState } from '../types/controller';

interface StatusBarProps {
  state: ControllerState;
}

export const StatusBar: React.FC<StatusBarProps> = ({ state }) => {
  return (
    <footer className="h-9 px-4 sm:px-6 flex items-center justify-between border-t border-white/5 bg-[#080b10] text-[11px] font-mono-nums text-slate-400 select-none shrink-0 z-20">
      {/* Left: Engine & Air Brake Status */}
      <div className="flex items-center gap-3">
        <div>
          <span>ENGINE: </span>
          <strong className={state.engine ? 'text-emerald-400' : 'text-slate-400'}>
            {state.engine ? 'RUNNING (IDLE 650 RPM)' : 'STOPPED'}
          </strong>
        </div>
        <span className="text-slate-600">|</span>
        <div>
          <span>P-BRAKE: </span>
          <strong className={state.parkingBrake ? 'text-red-500 font-bold' : 'text-emerald-400'}>
            {state.parkingBrake ? 'ENGAGED' : 'RELEASED'}
          </strong>
        </div>
      </div>

      {/* Right: Low Latency Benchmark & Polling Rate */}
      <div className="flex items-center gap-3">
        <div>
          <span>LATENCY: </span>
          <strong className="text-amber-400 font-semibold">{state.latencyMs} ms (Local)</strong>
        </div>
        <span className="text-slate-600">|</span>
        <div>
          <span>POLL: </span>
          <strong className="text-cyan-400 font-semibold">{state.pollingHz} Hz</strong>
        </div>
      </div>
    </footer>
  );
};
