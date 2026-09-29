/**
 * Sound synthesizer using Web Audio API for zero-dependency, ultra-realistic truck sounds.
 */

class SoundEffectsService {
  private ctx: AudioContext | null = null;
  private engineOsc: OscillatorNode | null = null;
  private engineGain: GainNode | null = null;
  private engineFilter: BiquadFilterNode | null = null;
  private isEngineRunning = false;
  private isMuted = false;
  private masterVolume = 0.8;

  private initContext() {
    if (!this.ctx) {
      const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
      this.ctx = new AudioCtx();
    }
    if (this.ctx.state === 'suspended') {
      this.ctx.resume();
    }
  }

  public setVolume(vol: number) {
    this.masterVolume = Math.max(0, Math.min(1, vol / 100));
    if (this.engineGain && this.isEngineRunning) {
      this.engineGain.gain.setTargetAtTime(this.masterVolume * 0.25, this.ctx?.currentTime || 0, 0.1);
    }
  }

  public setMute(muted: boolean) {
    this.isMuted = muted;
    if (this.engineGain) {
      const current = this.ctx?.currentTime || 0;
      this.engineGain.gain.setTargetAtTime(muted ? 0 : this.masterVolume * 0.25, current, 0.05);
    }
  }

  /**
   * Tactile micro-click for physical cockpit buttons
   */
  public playClick(pitch = 1200) {
    if (this.isMuted) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();

      osc.type = 'sine';
      osc.frequency.setValueAtTime(pitch, this.ctx.currentTime);
      osc.frequency.exponentialRampToValueAtTime(300, this.ctx.currentTime + 0.025);

      gain.gain.setValueAtTime(this.masterVolume * 0.15, this.ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + 0.025);

      osc.connect(gain);
      gain.connect(this.ctx.destination);

      osc.start();
      osc.stop(this.ctx.currentTime + 0.03);
    } catch {
      // Audio context might be restricted before user gesture
    }
  }

  /**
   * Heavy transmission gear shift clunk
   */
  public playGearShift() {
    if (this.isMuted) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      // Mechanical clunk 1
      const osc1 = this.ctx.createOscillator();
      const gain1 = this.ctx.createGain();
      osc1.type = 'triangle';
      osc1.frequency.setValueAtTime(140, now);
      osc1.frequency.exponentialRampToValueAtTime(45, now + 0.08);

      gain1.gain.setValueAtTime(this.masterVolume * 0.4, now);
      gain1.gain.exponentialRampToValueAtTime(0.001, now + 0.08);

      osc1.connect(gain1);
      gain1.connect(this.ctx.destination);

      osc1.start(now);
      osc1.stop(now + 0.09);

      // Metallic latch 2
      const osc2 = this.ctx.createOscillator();
      const gain2 = this.ctx.createGain();
      osc2.type = 'sine';
      osc2.frequency.setValueAtTime(480, now + 0.03);
      osc2.frequency.exponentialRampToValueAtTime(120, now + 0.12);

      gain2.gain.setValueAtTime(this.masterVolume * 0.25, now + 0.03);
      gain2.gain.exponentialRampToValueAtTime(0.001, now + 0.12);

      osc2.connect(gain2);
      gain2.connect(this.ctx.destination);

      osc2.start(now + 0.03);
      osc2.stop(now + 0.13);
    } catch {
      // Audio failover
    }
  }

  /**
   * Air Brake pneumatic release hiss: "Psssshhhhh-clack!"
   */
  public playAirBrake() {
    if (this.isMuted) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      const bufferSize = this.ctx.sampleRate * 0.45;
      const buffer = this.ctx.createBuffer(1, bufferSize, this.ctx.sampleRate);
      const output = buffer.getChannelData(0);

      // Generate pink/white noise for air release
      for (let i = 0; i < bufferSize; i++) {
        output[i] = (Math.random() * 2 - 1) * Math.exp(-i / (this.ctx.sampleRate * 0.22));
      }

      const whiteNoise = this.ctx.createBufferSource();
      whiteNoise.buffer = buffer;

      const filter = this.ctx.createBiquadFilter();
      filter.type = 'bandpass';
      filter.frequency.setValueAtTime(1600, now);
      filter.frequency.exponentialRampToValueAtTime(700, now + 0.4);
      filter.Q.value = 1.2;

      const gain = this.ctx.createGain();
      gain.gain.setValueAtTime(this.masterVolume * 0.6, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.44);

      whiteNoise.connect(filter);
      filter.connect(gain);
      gain.connect(this.ctx.destination);

      whiteNoise.start(now);

      // Mechanical spring clack at the end
      this.playClick(280);
    } catch {
      // Audio failover
    }
  }

  /**
   * European Heavy Truck Air Horn
   */
  private hornOscs: OscillatorNode[] = [];
  private hornGain: GainNode | null = null;

  public startHorn() {
    if (this.isMuted) return;
    try {
      this.initContext();
      if (!this.ctx || this.hornOscs.length > 0) return;

      const now = this.ctx.currentTime;
      this.hornGain = this.ctx.createGain();
      this.hornGain.gain.setValueAtTime(0.001, now);
      this.hornGain.gain.linearRampToValueAtTime(this.masterVolume * 0.5, now + 0.05);
      this.hornGain.connect(this.ctx.destination);

      // European dual pneumatic trumpet chord: F3 (174Hz) + A3 (220Hz) + C4 (261Hz)
      const freqs = [174.6, 220.0, 261.6];
      this.hornOscs = freqs.map((freq) => {
        const osc = this.ctx!.createOscillator();
        osc.type = 'sawtooth';
        osc.frequency.setValueAtTime(freq, now);
        osc.connect(this.hornGain!);
        osc.start(now);
        return osc;
      });
    } catch {
      // Audio failover
    }
  }

  public stopHorn() {
    if (!this.ctx || !this.hornGain) return;
    try {
      const now = this.ctx.currentTime;
      this.hornGain.gain.linearRampToValueAtTime(0.001, now + 0.1);
      setTimeout(() => {
        this.hornOscs.forEach((o) => {
          try {
            o.stop();
            o.disconnect();
          } catch {
            // ignore
          }
        });
        this.hornOscs = [];
        this.hornGain?.disconnect();
        this.hornGain = null;
      }, 120);
    } catch {
      // Audio failover
    }
  }

  /**
   * Turn Signal Relay Click (dual frequency for tick / tock)
   */
  public playTurnSignal(isTock = false) {
    if (this.isMuted) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      const osc = this.ctx.createOscillator();
      const gain = this.ctx.createGain();

      osc.type = 'triangle';
      osc.frequency.setValueAtTime(isTock ? 750 : 920, now);
      osc.frequency.exponentialRampToValueAtTime(180, now + 0.035);

      gain.gain.setValueAtTime(this.masterVolume * 0.28, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.035);

      osc.connect(gain);
      gain.connect(this.ctx.destination);

      osc.start(now);
      osc.stop(now + 0.04);
    } catch {
      // Audio failover
    }
  }

  /**
   * Engine Ignition & Continuous Idle Diesel Rumble
   */
  public startEngine() {
    if (this.isEngineRunning) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      this.isEngineRunning = true;
      const now = this.ctx.currentTime;

      // Cranking sound burst
      const crankOsc = this.ctx.createOscillator();
      const crankGain = this.ctx.createGain();
      crankOsc.type = 'sawtooth';
      crankOsc.frequency.setValueAtTime(45, now);
      crankOsc.frequency.linearRampToValueAtTime(95, now + 0.6);

      crankGain.gain.setValueAtTime(this.isMuted ? 0 : this.masterVolume * 0.3, now);
      crankGain.gain.exponentialRampToValueAtTime(0.001, now + 0.65);

      crankOsc.connect(crankGain);
      crankGain.connect(this.ctx.destination);
      crankOsc.start(now);
      crankOsc.stop(now + 0.7);

      // Diesel Idle Rumble loop
      this.engineOsc = this.ctx.createOscillator();
      this.engineFilter = this.ctx.createBiquadFilter();
      this.engineGain = this.ctx.createGain();

      this.engineOsc.type = 'sawtooth';
      this.engineOsc.frequency.setValueAtTime(42, now + 0.65); // 42Hz ~ 600 RPM 6-cylinder diesel

      this.engineFilter.type = 'lowpass';
      this.engineFilter.frequency.setValueAtTime(140, now + 0.65);

      this.engineGain.gain.setValueAtTime(0.001, now);
      this.engineGain.gain.setTargetAtTime(this.isMuted ? 0 : this.masterVolume * 0.22, now + 0.7, 0.2);

      this.engineOsc.connect(this.engineFilter);
      this.engineFilter.connect(this.engineGain);
      this.engineGain.connect(this.ctx.destination);

      this.engineOsc.start(now + 0.65);
    } catch {
      // Audio failover
    }
  }

  public updateEngineRpm(rpm: number) {
    if (!this.engineOsc || !this.engineFilter || !this.ctx) return;
    // Map 600 - 2200 RPM to oscillator freq 38Hz - 110Hz
    const targetFreq = 38 + (rpm / 2000) * 70;
    this.engineOsc.frequency.setTargetAtTime(targetFreq, this.ctx.currentTime, 0.05);
    this.engineFilter.frequency.setTargetAtTime(120 + (rpm / 2000) * 180, this.ctx.currentTime, 0.05);
  }

  public stopEngine() {
    if (!this.isEngineRunning) return;
    this.isEngineRunning = false;
    try {
      if (this.engineGain && this.ctx) {
        const now = this.ctx.currentTime;
        this.engineGain.gain.setTargetAtTime(0.001, now, 0.15);
        setTimeout(() => {
          this.engineOsc?.stop();
          this.engineOsc?.disconnect();
          this.engineGain?.disconnect();
          this.engineOsc = null;
          this.engineGain = null;
          this.engineFilter = null;
        }, 200);
      }
    } catch {
      // Audio failover
    }
  }

  /**
   * CB Radio Squelch Burst & Roger Beep
   */
  public playCbSquelch(open = true) {
    if (this.isMuted) return;
    try {
      this.initContext();
      if (!this.ctx) return;

      const now = this.ctx.currentTime;
      const bufferSize = this.ctx.sampleRate * 0.15;
      const buffer = this.ctx.createBuffer(1, bufferSize, this.ctx.sampleRate);
      const data = buffer.getChannelData(0);

      for (let i = 0; i < bufferSize; i++) {
        data[i] = (Math.random() * 2 - 1) * (1 - i / bufferSize);
      }

      const noise = this.ctx.createBufferSource();
      noise.buffer = buffer;

      const filter = this.ctx.createBiquadFilter();
      filter.type = 'bandpass';
      filter.frequency.value = 2400;
      filter.Q.value = 2.5;

      const gain = this.ctx.createGain();
      gain.gain.setValueAtTime(this.masterVolume * 0.35, now);
      gain.gain.exponentialRampToValueAtTime(0.001, now + 0.14);

      noise.connect(filter);
      filter.connect(gain);
      gain.connect(this.ctx.destination);

      noise.start(now);

      // Roger Beep if closing transmission
      if (!open) {
        const beep = this.ctx.createOscillator();
        const beepGain = this.ctx.createGain();
        beep.type = 'sine';
        beep.frequency.setValueAtTime(1850, now + 0.1);
        beepGain.gain.setValueAtTime(this.masterVolume * 0.25, now + 0.1);
        beepGain.gain.exponentialRampToValueAtTime(0.001, now + 0.2);

        beep.connect(beepGain);
        beepGain.connect(this.ctx.destination);

        beep.start(now + 0.1);
        beep.stop(now + 0.21);
      }
    } catch {
      // Audio failover
    }
  }

  /**
   * Retarder Detent Click
   */
  public playRetarderClick() {
    this.playClick(420);
  }
}

export const sounds = new SoundEffectsService();
