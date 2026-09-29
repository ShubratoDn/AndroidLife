# TruckPad

Turn an Android phone into a **Bluetooth steering wheel for Euro Truck Simulator 2** — and into a
**wireless keyboard, touchpad and presentation remote** for your PC.

TruckPad uses the Bluetooth HID Device profile built into Android 9+, so the PC sees a normal
Bluetooth game controller / keyboard / mouse. **No PC software, drivers or server are needed.**

📖 **New here? Follow the step-by-step [Installation Guide](INSTALL.md).**

---

## Getting started

After downloading this project:

1. **Get the APK** — use a ready-made APK from the repository's *Releases* page, or build it:
   ```bash
   git clone https://github.com/ShubratoDn/AndroidLife.git
   cd AndroidLife/android
   ./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
   ```
   The APK is created at `android/app/build/outputs/apk/debug/app-debug.apk`.
2. **Install it on the phone** — copy the APK to the phone and open it (allow *Install unknown
   apps*), or run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. **Open TruckPad** and allow the *Nearby devices* (Bluetooth) permission.
4. **Pair once** — tap **PAIR PC → Make visible**, then add the phone in the PC's Bluetooth
   settings. The status turns green: *Connected*.
5. **Pick a mode** on the landing page — the *ETS2 Truck Controller* or one of the keyboard &
   mouse modes — and start using it.

For ETS2, connect the phone before launching the game and bind the controls as described in
[Setting up Euro Truck Simulator 2](#setting-up-euro-truck-simulator-2).
Detailed instructions and troubleshooting: **[INSTALL.md](INSTALL.md)**.

---

## Features

### ETS2 Truck Controller
- **Steering wheel** with 360° – 1800° rotation, auto-centering spring, deadzone and response curve
- **Gas and brake pedals** as analog axes (drag up to press)
- **Sequential shifter**, retarder, cruise control (+/−), engine start, parking brake
- **Cockpit switches**: lights, beacon, hazards, blinkers, wipers, diff lock, lift axle, trailer, camera
- **Look / pan pad** (analog look axes + 8-way hat) and quick-look buttons
- **Horn** in the wheel hub and **CB radio** push-to-talk
- Truck sounds and haptic feedback (gear clunk, air brake, blinker relay, engine idle)

### Keyboard & Mouse
| Mode | What you get |
|---|---|
| **Keyboard + Touchpad** | Compact keyboard next to (or, in portrait, below) a touchpad with Left / Middle / Right buttons |
| **Keyboard** | Standard layout with F-keys, symbols and arrows |
| **Num Pad + Touchpad** | Numeric keypad with Num Lock indicator and a large touchpad |
| **Keyboard Complete** | Full 104-key layout: F-row, navigation cluster, arrows and numpad |
| **Presentation Remote** | Next / previous slide, talk timer, start / end show, black screen, laser pointer, pointer pad; the phone's volume keys also change slides |

- **Touchpad gestures**: move, tap to click, two-finger tap for right click, three-finger tap for
  middle click, two-finger scroll, tap-and-drag, and a scroll strip on the right edge
- **Modifiers**: tap Ctrl / Shift / Alt once for the next key, twice to lock, or hold them with a
  second finger; Win works like a real key
- **Caps Lock / Num Lock** indicators follow the PC's real state
- **Media keys**: mute, volume, previous, play / pause, next
- **Portrait and landscape** for every mode, with a rotate button (Auto → Portrait → Landscape);
  in portrait the keyboard height is adjustable with a drag handle
- Leaving a screen always releases every key, mouse button and pedal — nothing gets stuck

---

## Requirements

| | |
|---|---|
| Phone | Android 9 (API 28) or newer with Bluetooth. Some manufacturer ROMs disable the Bluetooth HID Device profile; the app then stays "Offline" |
| PC | Any computer with Bluetooth (tested with Windows 11) |
| Build | JDK 17+, Android SDK with platform 35 and build tools 35 |

---

## Build and install

```bash
cd android
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

If Gradle cannot find the Android SDK, create `android/local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Install it with USB
debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Xiaomi / MIUI phones also enable **Developer options → Install via USB**.

---

## Pairing with the PC

1. Open TruckPad and allow the **Nearby devices** (Bluetooth) permission.
2. Tap **PAIR PC → Make visible**.
3. On the PC open **Bluetooth settings → Add device** and select the phone.
4. The status pill turns green: **Connected**.

After the first pairing the app reconnects to the same PC automatically. One connection is
shared by all modes, so you can switch between the truck controller and the keyboard & mouse
screens freely.

> **Re-pair after updates that change the controller layout.** The PC stores the device
> description when pairing. If the app shows *Connected* but nothing reacts, remove the phone in
> the PC's Bluetooth settings, unpair the PC on the phone, and pair again.

To check the controller on Windows, press **Win + R**, run `joy.cpl`, select **TruckPad** and
open **Properties**.

---

## Setting up Euro Truck Simulator 2

1. Connect the phone **before** starting the game.
2. Open **Options → Controls** and select **TruckPad** as the input device.
3. Bind the axes by clicking an action in the game and moving the control in the app:

| ETS2 action | App control | Axis |
|---|---|---|
| Steering | Steering wheel | X (`joy.x`) |
| Throttle | GAS pedal | Slider (`joy.sl1`) |
| Brake | BRAKE pedal | Dial / 2nd slider (`joy.sl2`) |
| Look left / right | Look pad, sideways | Rx (`joy.rx`) |
| Look up / down | Look pad, up / down | Ry (`joy.ry`) |

Use **full range** for the pedals; if a pedal reads 100 % while released, enable **Invert** for it.
Set the game's steering non-linearity to 0 % — the app already applies its own curve.

4. Bind the buttons: in the app open **Button mappings** (sliders icon in the header) and tap a row
   to send that button while ETS2 is waiting for input.

| Button | Action | Button | Action |
|---|---|---|---|
| 1 | Gear up | 14 | Right blinker |
| 2 | Gear down | 15 | Hazard lights |
| 3 | Splitter | 16 | Light modes |
| 4 | Range | 17 | Beacon |
| 5 | Engine start / stop | 18 | Interior light |
| 6 | Parking brake | 19 | Wipers |
| 7 | Retarder + | 20 | Cruise control |
| 8 | Retarder − | 21 | Cruise speed + |
| 9 | Horn (held) | 22 | Cruise speed − |
| 10 | Attach / detach trailer | 23 | Camera |
| 11 | Differential lock | 24 | Look left |
| 12 | Lift axle | 25 | Look right |
| 13 | Left blinker | 26 | CB push-to-talk (held) |

Toggle actions are sent as a short press; horn and CB push-to-talk are held while touched.

---

## How it works

The phone registers **one composite HID device** with four report IDs:

| Report ID | Collection | Contents |
|---|---|---|
| 1 | Keyboard | Modifier byte, 6-key rollover, Num / Caps / Scroll Lock LEDs |
| 2 | Mouse | 5 buttons, relative X / Y, vertical wheel, horizontal pan |
| 3 | Gamepad (ETS2) | 16-bit steering, look Rx / Ry, pedal Slider / Dial, hat, 32 buttons |
| 4 | Consumer control | Media and volume keys |

Every stick-type axis rests at its center and the pedals use slider axes, so the controller never
moves the game's menu cursor while idle. Reports are sent from a dedicated thread; analog and mouse
motion are coalesced to about 120 reports per second.

---

## Project structure

```
android/                              Android app (Kotlin)
  app/src/main/java/com/truckcontroller/pro/
    HomeActivity.kt                   Landing page
    MainActivity.kt                   ETS2 truck controller screen
    InputActivity.kt                  Keyboard & mouse modes
    HidActivity.kt                    Shared Bluetooth permission / pairing / header logic
    bluetooth/BluetoothHidService.kt  HID descriptor and report sending
    input/                            Keyboard layouts, keyboard view, touchpad view
    ui/                               Steering wheel, pedals, look pad, tiles, dialogs
    audio/, haptics/, model/          Sounds, vibration, controller state and settings
src/                                  Web prototype of the truck controller UI (React + Vite)
```

### Web prototype

The `src/` folder holds the original browser prototype of the cockpit UI. It is a design reference
only; it does not talk to the PC.

```bash
npm install
npm run dev        # http://localhost:3000
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Stays **Offline** after pairing | Tap **PAIR PC** and select the PC, or remove and re-pair the phone |
| PC cannot find the phone | Tap **Make visible** again — visibility lasts 2 minutes |
| *Connected*, but keys / mouse / controller do nothing | Re-pair (see above); the PC still has an older device description |
| ETS2 does not list the controller | Connect the phone first, then start the game |
| Pedals inverted or stuck at 100 % | Enable **Invert** for that axis in ETS2 |
| The app's gear / light state differs from the game | The app shows its own taps only; keyboard input in the game is not reflected |
