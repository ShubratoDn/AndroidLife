import React, { useState } from 'react';
import { X, Download, Copy, Check, FileCode, Smartphone, Terminal, ExternalLink } from 'lucide-react';
import { ANDROID_SOURCE_FILES, SourceFile } from '../data/androidSourceCode';
import { downloadAndroidProjectZip } from '../utils/zipGenerator';
import { sounds } from '../services/soundEffects';
import { haptics } from '../services/haptics';

interface AndroidProjectModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const AndroidProjectModal: React.FC<AndroidProjectModalProps> = ({ isOpen, onClose }) => {
  const [selectedFile, setSelectedFile] = useState<SourceFile>(ANDROID_SOURCE_FILES[0]);
  const [copied, setCopied] = useState(false);
  const [isDownloading, setIsDownloading] = useState(false);
  const [activeTab, setActiveTab] = useState<'code' | 'guide'>('code');

  if (!isOpen) return null;

  const handleCopy = () => {
    navigator.clipboard.writeText(selectedFile.content);
    setCopied(true);
    sounds.playClick();
    haptics.click();
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownloadZip = async () => {
    try {
      setIsDownloading(true);
      sounds.playClick();
      haptics.click();
      await downloadAndroidProjectZip();
    } finally {
      setIsDownloading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 bg-black/80 backdrop-blur-md">
      <div className="bg-[#0f141d] border border-white/10 rounded-2xl w-full max-w-5xl h-[88vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-150">
        {/* Modal Header */}
        <div className="px-6 py-4 border-b border-white/5 flex items-center justify-between bg-[#0b0e14]">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-orange-500 to-amber-600 flex items-center justify-center shadow-lg shadow-orange-500/20">
              <Smartphone className="w-5 h-5 text-white" />
            </div>
            <div>
              <h2 className="font-display font-bold text-lg text-white">
                Android Kotlin Application & Project Export
              </h2>
              <p className="text-xs text-slate-400">
                Native Android Bluetooth HID stack with 16-bit steering, haptic feedback & ETS2 integration
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {/* Download Project Zip Button */}
            <button
              onClick={handleDownloadZip}
              disabled={isDownloading}
              className="px-4 py-2 rounded-xl bg-gradient-to-r from-orange-500 to-amber-500 hover:from-orange-400 hover:to-amber-400 text-white font-medium text-xs flex items-center gap-2 shadow-lg shadow-orange-500/20 transition-all cursor-pointer active:scale-95 disabled:opacity-50"
            >
              <Download className="w-4 h-4" />
              <span>{isDownloading ? 'Packing Project...' : 'Download Android Project (.zip)'}</span>
            </button>

            {/* Close Button */}
            <button
              onClick={onClose}
              className="p-2 rounded-xl bg-white/5 hover:bg-white/10 text-slate-400 hover:text-white transition-all cursor-pointer"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Tab Selector */}
        <div className="px-6 py-2 border-b border-white/5 bg-[#0d1118] flex items-center gap-4 text-xs font-mono-nums">
          <button
            onClick={() => setActiveTab('code')}
            className={`py-1.5 border-b-2 font-medium transition-colors cursor-pointer ${
              activeTab === 'code' ? 'border-orange-500 text-orange-400' : 'border-transparent text-slate-400 hover:text-white'
            }`}
          >
            Kotlin Source Code Files (.kt)
          </button>
          <button
            onClick={() => setActiveTab('guide')}
            className={`py-1.5 border-b-2 font-medium transition-colors cursor-pointer ${
              activeTab === 'guide' ? 'border-orange-500 text-orange-400' : 'border-transparent text-slate-400 hover:text-white'
            }`}
          >
            ETS2 Pairing & Android Studio Guide
          </button>
        </div>

        {/* Main Body */}
        {activeTab === 'code' ? (
          <div className="flex-1 flex flex-col md:flex-row min-h-0 bg-[#090d13]">
            {/* Left: File Explorer */}
            <div className="w-full md:w-72 border-r border-white/5 bg-[#0b0f16] p-3 flex flex-col overflow-y-auto">
              <span className="text-[11px] font-mono-nums font-semibold text-slate-500 uppercase px-2 mb-2">
                Project Files
              </span>
              <div className="flex flex-col gap-1">
                {ANDROID_SOURCE_FILES.map((file) => (
                  <button
                    key={file.path}
                    onClick={() => {
                      sounds.playClick();
                      setSelectedFile(file);
                    }}
                    className={`px-3 py-2 rounded-lg text-left text-xs font-mono-nums flex items-center gap-2.5 transition-all cursor-pointer ${
                      selectedFile.path === file.path
                        ? 'bg-orange-500/15 text-orange-400 border border-orange-500/30'
                        : 'text-slate-400 hover:bg-white/5 hover:text-slate-200 border border-transparent'
                    }`}
                  >
                    <FileCode className="w-4 h-4 shrink-0" />
                    <span className="truncate">{file.name}</span>
                  </button>
                ))}
              </div>
            </div>

            {/* Right: Code Viewer */}
            <div className="flex-1 flex flex-col min-w-0 bg-[#070a0e] overflow-hidden">
              {/* File details bar */}
              <div className="px-4 py-2.5 border-b border-white/5 bg-[#0b0e14] flex items-center justify-between text-xs font-mono-nums">
                <div className="flex items-center gap-2 truncate">
                  <span className="text-slate-500">Path:</span>
                  <span className="text-slate-300 truncate">{selectedFile.path}</span>
                </div>
                <button
                  onClick={handleCopy}
                  className="px-2.5 py-1 rounded bg-[#161d29] hover:bg-[#1f2838] border border-white/10 text-slate-300 hover:text-white flex items-center gap-1.5 transition-all cursor-pointer shrink-0"
                >
                  {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{copied ? 'Copied' : 'Copy Code'}</span>
                </button>
              </div>

              {/* File description note */}
              <div className="px-4 py-2 bg-amber-500/5 border-b border-amber-500/10 text-xs text-amber-300/80 font-mono-nums">
                {selectedFile.description}
              </div>

              {/* Code Pre Container */}
              <div className="flex-1 overflow-auto p-4 text-[12px] font-mono leading-relaxed text-slate-300 select-text">
                <pre>{selectedFile.content}</pre>
              </div>
            </div>
          </div>
        ) : (
          /* Guide Tab */
          <div className="flex-1 overflow-y-auto p-6 space-y-6 bg-[#090d13] text-sm text-slate-300">
            <div className="p-4 rounded-xl bg-orange-500/10 border border-orange-500/20 text-orange-200">
              <h3 className="font-bold text-base text-orange-400 mb-1">
                How Android Bluetooth HID Works for Euro Truck Simulator 2
              </h3>
              <p className="text-xs leading-relaxed text-slate-300">
                Android 9.0 (API 28) and newer include the native <code>BluetoothHidDevice</code> API.
                When this app runs on your phone, Android advertises as an official Bluetooth Gamepad / Joystick.
                Your Windows PC pairs directly to your phone via standard Bluetooth—<strong>no third-party driver software required</strong> on the PC!
              </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="p-4 rounded-xl bg-[#0e131b] border border-white/5 space-y-3">
                <div className="flex items-center gap-2 text-white font-semibold">
                  <Smartphone className="w-4 h-4 text-orange-400" />
                  <h4>1. Build the APK in Android Studio</h4>
                </div>
                <ol className="list-decimal list-inside text-xs space-y-2 text-slate-400">
                  <li>Click <strong>Download Android Project (.zip)</strong> above.</li>
                  <li>Extract the zip archive to your computer.</li>
                  <li>In Android Studio, click <strong>File &gt; Open...</strong> and choose the folder.</li>
                  <li>Let Gradle sync dependencies (Kotlin 1.9.22, compileSdk 34).</li>
                  <li>Connect your Android phone via USB with USB Debugging enabled.</li>
                  <li>Click <strong>Run 'app'</strong> (Shift + F10) to install the APK!</li>
                </ol>
              </div>

              <div className="p-4 rounded-xl bg-[#0e131b] border border-white/5 space-y-3">
                <div className="flex items-center gap-2 text-white font-semibold">
                  <Terminal className="w-4 h-4 text-cyan-400" />
                  <h4>2. Connect to PC via Bluetooth</h4>
                </div>
                <ol className="list-decimal list-inside text-xs space-y-2 text-slate-400">
                  <li>On your PC, open <strong>Settings &gt; Bluetooth &amp; Devices</strong>.</li>
                  <li>Turn on Bluetooth and click <strong>Add device</strong>.</li>
                  <li>Launch <strong>TruckController Pro</strong> on your phone.</li>
                  <li>Select your phone when it appears as <em>TruckController Pro</em>.</li>
                  <li>Windows will recognize it as a standard DirectInput / XInput Joystick!</li>
                </ol>
              </div>
            </div>

            <div className="p-4 rounded-xl bg-[#0e131b] border border-white/5 space-y-3">
              <div className="flex items-center gap-2 text-white font-semibold">
                <ExternalLink className="w-4 h-4 text-emerald-400" />
                <h4>3. Euro Truck Simulator 2 Game Setup</h4>
              </div>
              <ul className="text-xs space-y-2 text-slate-400 list-disc list-inside">
                <li>In ETS2, open <strong>Options &gt; Controls</strong>.</li>
                <li>At the top, select <strong>Keyboard + TruckController Pro</strong>.</li>
                <li>Set Controller Subtype to <strong>Wheel</strong>.</li>
                <li>Click <strong>Steering Axis</strong> and rotate the wheel on your phone.</li>
                <li>Click <strong>Acceleration Axis</strong> and press the green Gas pedal.</li>
                <li>Click <strong>Brake Axis</strong> and press the red Brake pedal.</li>
                <li>Under <strong>Keys &amp; Buttons</strong>, map gear shifting, engine ignition, and retarder!</li>
              </ul>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
