import React, { useState } from 'react';
import { X, Bluetooth, Wifi, Activity, CheckCircle2, AlertCircle, RefreshCw } from 'lucide-react';
import { ControllerState } from '../types/controller';
import { bluetoothHid } from '../services/bluetoothHid';
import { sounds } from '../services/soundEffects';
import { haptics } from '../services/haptics';

interface BluetoothModalProps {
  isOpen: boolean;
  onClose: () => void;
  state: ControllerState;
  onToggleVirtualBt: () => void;
}

export const BluetoothModal: React.FC<BluetoothModalProps> = ({
  isOpen,
  onClose,
  state,
  onToggleVirtualBt,
}) => {
  const [pcBridgeIp, setPcBridgeIp] = useState('127.0.0.1');
  const [isConnectingBridge, setIsConnectingBridge] = useState(false);
  const [btStatusMsg, setBtStatusMsg] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleWebBluetoothScan = async () => {
    sounds.playClick();
    haptics.click();
    setBtStatusMsg('Requesting Bluetooth device...');
    const res = await bluetoothHid.requestWebBluetooth();
    setBtStatusMsg(res.message);
  };

  const handleConnectBridge = async () => {
    sounds.playClick();
    haptics.click();
    setIsConnectingBridge(true);
    const ok = await bluetoothHid.connectToPcBridge(pcBridgeIp, 8765);
    setIsConnectingBridge(false);
    if (!ok) {
      setBtStatusMsg(`Could not connect to PC Bridge at ${pcBridgeIp}:8765. Make sure 'pc_bridge_server.py' is running.`);
    } else {
      setBtStatusMsg(`Connected to PC Bridge at ${pcBridgeIp}:8765!`);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 bg-black/80 backdrop-blur-md">
      <div className="bg-[#0f141d] border border-white/10 rounded-2xl w-full max-w-2xl flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-150">
        {/* Header */}
        <div className="px-6 py-4 border-b border-white/5 flex items-center justify-between bg-[#0b0e14]">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center shadow-lg shadow-blue-500/20">
              <Bluetooth className="w-5 h-5 text-white" />
            </div>
            <div>
              <h2 className="font-display font-bold text-lg text-white">
                Bluetooth HID &amp; Low Latency Bridge
              </h2>
              <p className="text-xs text-slate-400">
                120Hz DirectInput / XInput Joystick output for Euro Truck Simulator 2
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

        {/* Content */}
        <div className="p-6 space-y-5 bg-[#090d13] text-xs font-mono-nums">
          {/* Connection Status Box */}
          <div className="p-4 rounded-xl bg-[#0e131b] border border-white/5 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div
                className={`w-3 h-3 rounded-full ${
                  state.connection === 'connected' ? 'bg-emerald-400 animate-pulse' : 'bg-slate-600'
                }`}
              />
              <div>
                <span className="text-slate-400">Current Status: </span>
                <strong className={state.connection === 'connected' ? 'text-emerald-400' : 'text-slate-300'}>
                  {state.connection === 'connected'
                    ? `Connected (${state.connectedDeviceName || 'BT HID'})`
                    : 'Disconnected (Offline)'}
                </strong>
              </div>
            </div>

            <button
              onClick={() => {
                sounds.playClick();
                haptics.click();
                onToggleVirtualBt();
              }}
              className="px-3 py-1.5 rounded-lg bg-[#161d2a] hover:bg-[#20293a] text-slate-300 border border-white/10 transition-all cursor-pointer"
            >
              {state.connection === 'connected' ? 'Disconnect' : 'Simulate Connected'}
            </button>
          </div>

          {/* Methods */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {/* Method A: Web Bluetooth */}
            <div className="p-4 rounded-xl bg-[#0e131b] border border-white/5 space-y-3">
              <div className="flex items-center gap-2 text-white font-semibold">
                <Bluetooth className="w-4 h-4 text-blue-400" />
                <span>Web Bluetooth (HID)</span>
              </div>
              <p className="text-[11px] text-slate-400">
                Scan for Bluetooth devices from supported Chrome/Edge browsers.
              </p>
              <button
                onClick={handleWebBluetoothScan}
                className="w-full py-2 rounded-lg bg-blue-600 hover:bg-blue-500 text-white font-medium flex items-center justify-center gap-2 shadow-md shadow-blue-600/30 transition-all cursor-pointer"
              >
                <Bluetooth className="w-3.5 h-3.5" />
                <span>Scan for PC Bluetooth</span>
              </button>
            </div>

            {/* Method B: PC WebSocket Bridge */}
            <div className="p-4 rounded-xl bg-[#0e131b] border border-white/5 space-y-3">
              <div className="flex items-center gap-2 text-white font-semibold">
                <Wifi className="w-4 h-4 text-cyan-400" />
                <span>Local PC Bridge</span>
              </div>
              <p className="text-[11px] text-slate-400">
                Connects to <code>pc_bridge_server.py</code> over local WebSocket (8765).
              </p>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={pcBridgeIp}
                  onChange={(e) => setPcBridgeIp(e.target.value)}
                  className="flex-1 px-2.5 py-1.5 rounded bg-black/40 border border-white/10 text-white outline-none"
                  placeholder="127.0.0.1"
                />
                <button
                  onClick={handleConnectBridge}
                  disabled={isConnectingBridge}
                  className="px-3 py-1.5 rounded bg-cyan-600 hover:bg-cyan-500 text-white font-medium transition-all cursor-pointer disabled:opacity-50"
                >
                  {isConnectingBridge ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : 'Connect'}
                </button>
              </div>
            </div>
          </div>

          {/* Status Message */}
          {btStatusMsg && (
            <div className="p-3 rounded-lg bg-slate-800/80 border border-slate-700 text-[11px] text-slate-300 flex items-start gap-2">
              <AlertCircle className="w-4 h-4 text-amber-400 shrink-0 mt-0.5" />
              <span>{btStatusMsg}</span>
            </div>
          )}

          {/* Live HID Report Diagnostic Inspector */}
          <div className="p-4 rounded-xl bg-[#0b0e14] border border-white/5 space-y-2">
            <div className="flex items-center justify-between text-slate-400">
              <div className="flex items-center gap-2">
                <Activity className="w-4 h-4 text-amber-400" />
                <span className="font-semibold text-white">Live HID Packet Telemetry (120 Hz)</span>
              </div>
              <span className="text-amber-400">{state.latencyMs} ms processing</span>
            </div>

            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-2 text-[10px]">
              <div className="p-2 rounded bg-[#10141d] border border-white/5">
                <span className="text-slate-500 block">Steering (16-bit):</span>
                <span className="text-emerald-400 font-bold text-xs">
                  {Math.round(state.steeringNormalized * 32767)}
                </span>
              </div>
              <div className="p-2 rounded bg-[#10141d] border border-white/5">
                <span className="text-slate-500 block">Throttle (8-bit):</span>
                <span className="text-emerald-400 font-bold text-xs">
                  {Math.round((state.gas / 100) * 255)}
                </span>
              </div>
              <div className="p-2 rounded bg-[#10141d] border border-white/5">
                <span className="text-slate-500 block">Brake (8-bit):</span>
                <span className="text-red-400 font-bold text-xs">
                  {Math.round((state.brake / 100) * 255)}
                </span>
              </div>
              <div className="p-2 rounded bg-[#10141d] border border-white/5">
                <span className="text-slate-500 block">Buttons Bitmask:</span>
                <span className="text-cyan-400 font-bold text-xs">
                  0x{state.gear.toString(16).padStart(4, '0')}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
