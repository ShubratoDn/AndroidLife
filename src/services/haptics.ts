/**
 * Haptic feedback driver using HTML5 Web Vibration API
 */

class HapticsService {
  private enabled = true;
  private intensity = 1.0; // 0.0 to 1.0

  public configure(enabled: boolean, intensityPercent: number) {
    this.enabled = enabled;
    this.intensity = Math.max(0, Math.min(1, intensityPercent / 100));
  }

  private vibrate(pattern: number | number[]) {
    if (!this.enabled || this.intensity <= 0) return;
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      try {
        if (typeof pattern === 'number') {
          navigator.vibrate(Math.round(pattern * this.intensity));
        } else {
          const scaled = pattern.map((p, idx) => (idx % 2 === 0 ? Math.round(p * this.intensity) : p));
          navigator.vibrate(scaled);
        }
      } catch {
        // Ignore unsupported/blocked vibration
      }
    }
  }

  /**
   * Crisp tactile tick for pushbuttons
   */
  public click() {
    this.vibrate(12);
  }

  /**
   * Heavy double-thud for gear shifts
   */
  public gearShift() {
    this.vibrate([28, 30, 45]);
  }

  /**
   * Pneumatic release pulse for air parking brake
   */
  public airBrake() {
    this.vibrate([60, 20, 20, 15, 10]);
  }

  /**
   * Horn continuous vibration pulse
   */
  public horn() {
    this.vibrate([40, 20, 40]);
  }

  /**
   * Engine startup heavy sequence
   */
  public engineStart() {
    this.vibrate([40, 60, 40, 60, 120]);
  }

  /**
   * Steering wheel detent / lock hit
   */
  public wheelLock() {
    this.vibrate(35);
  }

  /**
   * Squelch transmit button
   */
  public radioPtt() {
    this.vibrate([20, 20, 20]);
  }
}

export const haptics = new HapticsService();
