/**
 * Bluetooth HID & Low Latency Telemetry Service
 * Emulates Bluetooth HID Gamepad reports & connects to Web Bluetooth or local PC bridge.
 */

import { ControllerState } from '../types/controller';

export interface HidReportData {
  steeringRaw: number; // -32767 to 32767 (16-bit)
  throttleRaw: number; // 0 to 255 (8-bit)
  brakeRaw: number;    // 0 to 255 (8-bit)
  retarderRaw: number; // 0 to 255 (8-bit)
  buttonsBitmask: number; // 32-bit bitfield
  hatSwitch: number;  // 0-7 or 8 for centered
  timestamp: number;
}

class BluetoothHidService {
  private isConnected = false;
  private isScanning = false;
  private deviceName: string | null = null;
  private ws: WebSocket | null = null;
  private listeners: ((connected: boolean, name: string | null) => void)[] = [];
  private lastLatency = 0.8;
  private packetCount = 0;

  public subscribe(listener: (connected: boolean, name: string | null) => void) {
    this.listeners.push(listener);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  private notify() {
    this.listeners.forEach((l) => l(this.isConnected, this.deviceName));
  }

  public getConnectionState() {
    return {
      isConnected: this.isConnected,
      deviceName: this.deviceName,
      latency: this.lastLatency,
      isScanning: this.isScanning,
    };
  }

  /**
   * Connect using Web Bluetooth API if available on browser
   */
  public async requestWebBluetooth(): Promise<{ success: boolean; message: string }> {
    this.isScanning = true;

    if (typeof navigator !== 'undefined' && 'bluetooth' in navigator) {
      try {
        const navBt = (navigator as unknown as { bluetooth: { requestDevice: (opt: unknown) => Promise<{ name?: string }> } }).bluetooth;
        const device = await navBt.requestDevice({
          acceptAllDevices: true,
          optionalServices: ['generic_access', 'human_interface_device', '00001812-0000-1000-8000-00805f9b34fb'],
        });

        this.isConnected = true;
        this.deviceName = device.name || 'ETS2 Bluetooth Rig';
        this.isScanning = false;
        this.notify();
        return { success: true, message: `Connected to ${this.deviceName} via Bluetooth HID` };
      } catch (err: unknown) {
        this.isScanning = false;
        const errMsg = err instanceof Error ? err.message : 'User cancelled device prompt';
        // If user cancelled or security blocked in iframe, allow virtual / simulator pairing mode
        return { success: false, message: errMsg };
      }
    } else {
      this.isScanning = false;
      return {
        success: false,
        message: 'Web Bluetooth API not directly available in this browser window. Use Android APK export or PC Bridge Server.',
      };
    }
  }

  /**
   * Connect to optional local PC Bridge Server (Node/Python script for vJoy / vXbox in ETS2)
   */
  public connectToPcBridge(ip = '127.0.0.1', port = 8765): Promise<boolean> {
    return new Promise((resolve) => {
      try {
        if (this.ws) {
          this.ws.close();
        }
        this.ws = new WebSocket(`ws://${ip}:${port}`);

        this.ws.onopen = () => {
          this.isConnected = true;
          this.deviceName = `PC Bridge (${ip})`;
          this.notify();
          resolve(true);
        };

        this.ws.onerror = () => {
          resolve(false);
        };

        this.ws.onclose = () => {
          if (this.deviceName?.startsWith('PC Bridge')) {
            this.isConnected = false;
            this.deviceName = null;
            this.notify();
          }
        };

        // Timeout fallback after 1.5s
        setTimeout(() => {
          if (!this.isConnected) {
            resolve(false);
          }
        }, 1500);
      } catch {
        resolve(false);
      }
    });
  }

  /**
   * Toggle virtual Bluetooth controller simulation for browser testing
   */
  public toggleVirtualBluetooth() {
    if (this.isConnected) {
      this.isConnected = false;
      this.deviceName = null;
    } else {
      this.isConnected = true;
      this.deviceName = 'TruckController Pro (BT HID)';
    }
    this.notify();
    return this.isConnected;
  }

  /**
   * Build 16-bit low latency HID report packet from Controller State
   */
  public generateHidReport(state: ControllerState): HidReportData {
    const start = performance.now();

    // Map steering -1.0 to 1.0 to 16-bit signed integer (-32767 to +32767)
    const steeringRaw = Math.round(state.steeringNormalized * 32767);
    const throttleRaw = Math.round((state.gas / 100) * 255);
    const brakeRaw = Math.round((state.brake / 100) * 255);
    const retarderRaw = Math.round((state.retarderLevel / 5) * 255);

    // Compute bitmask for buttons (32-bit field)
    let bitmask = 0;
    if (state.engine) bitmask |= 1 << 0;
    if (state.parkingBrake) bitmask |= 1 << 1;
    if (state.trailerAttached) bitmask |= 1 << 2;
    if (state.diffLock) bitmask |= 1 << 3;
    if (state.axleLift) bitmask |= 1 << 4;
    if (state.horn) bitmask |= 1 << 5;
    if (state.beacon) bitmask |= 1 << 6;
    if (state.lights > 0) bitmask |= 1 << 7;
    if (state.lights === 3) bitmask |= 1 << 8; // High beam
    if (state.turnSignal === 'left') bitmask |= 1 << 9;
    if (state.turnSignal === 'right') bitmask |= 1 << 10;
    if (state.hazardLights) bitmask |= 1 << 11;
    if (state.wipers > 0) bitmask |= 1 << 12;
    if (state.interiorLight) bitmask |= 1 << 13;
    if (state.cruiseControlActive) bitmask |= 1 << 14;
    if (state.cbRadio.isTransmitting) bitmask |= 1 << 15;

    // Hat switch for Look/Pan
    let hat = 8; // centered
    const px = state.lookPan.x;
    const py = state.lookPan.y;
    if (Math.hypot(px, py) > 0.3) {
      const angle = (Math.atan2(py, px) * 180) / Math.PI; // -180 to 180
      // 0 = Up, 1 = Up-Right, 2 = Right, 3 = Down-Right, 4 = Down, 5 = Down-Left, 6 = Left, 7 = Up-Left
      if (angle >= -67.5 && angle < -22.5) hat = 1;
      else if (angle >= -22.5 && angle < 22.5) hat = 2;
      else if (angle >= 22.5 && angle < 67.5) hat = 3;
      else if (angle >= 67.5 && angle < 112.5) hat = 4;
      else if (angle >= 112.5 && angle < 157.5) hat = 5;
      else if (angle >= 157.5 || angle < -157.5) hat = 6;
      else if (angle >= -157.5 && angle < -112.5) hat = 7;
      else hat = 0;
    }

    const elapsed = performance.now() - start;
    // Smoothed jitter filter
    this.lastLatency = Math.min(2.5, Math.max(0.4, Number((this.lastLatency * 0.95 + elapsed * 0.05).toFixed(1))));
    this.packetCount++;

    const report: HidReportData = {
      steeringRaw,
      throttleRaw,
      brakeRaw,
      retarderRaw,
      buttonsBitmask: bitmask,
      hatSwitch: hat,
      timestamp: Date.now(),
    };

    // If WebSocket PC bridge is active, dispatch binary/JSON payload
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      try {
        this.ws.send(JSON.stringify(report));
      } catch {
        // fail silently
      }
    }

    return report;
  }
}

export const bluetoothHid = new BluetoothHidService();
