# PhoneDeck

**One app, a whole deck of tools.** PhoneDeck turns an Android phone into a **Bluetooth steering
wheel for Euro Truck Simulator 2**, a **wireless keyboard, touchpad and presentation remote** for
your PC, and a **toolbox for the phone itself**: hardware and sensor tests, battery and storage
analysis, screen time, a speedometer, a QR / barcode scanner and generator, a screen dimmer, and
**Wi-Fi file transfer** between the phone and any PC browser.

The PC-control features use the Bluetooth HID Device profile built into Android 9+, so the PC sees a
normal Bluetooth game controller / keyboard / mouse. **No PC software, drivers or server are needed.**
All phone tools work offline. Nothing leaves the phone except the files you choose to transfer to
a PC on your own network with File Transfer.

📖 **New here? Follow the step-by-step [Installation Guide](INSTALL.md).**

---

## Getting started

1. **Get the APK**: use a ready-made APK from the repository's *Releases* page, or build it:
   ```bash
   git clone https://github.com/ShubratoDn/AndroidLife.git
   cd AndroidLife/android
   ./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
   ```
   The APK is created at `android/app/build/outputs/apk/debug/app-debug.apk`.
2. **Install it on the phone**: copy the APK to the phone and open it (allow *Install unknown
   apps*), or run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. **Open PhoneDeck.** The home screen lists every tool by category; type in the search box to find
   one quickly (for example "gps", "qr" or "battery").
4. **To control a PC**, open the *ETS2 Truck Controller* or a *Keyboard & Mouse* tool. On the first
   visit it offers to pair: tap **Make visible**, then add the phone in the PC's Bluetooth settings.
   The status turns green: *Connected*.

Detailed instructions and troubleshooting: **[INSTALL.md](INSTALL.md)**.

---

## Tools

### Game control
- **ETS2 Truck Controller**: steering wheel (360°–1800°, auto-centering, deadzone, response curve,
  or tilt steering: turn the whole phone like a wheel),
  analog gas and brake pedals, sequential shifter, retarder, cruise control, engine start, parking
  brake, cockpit switches (lights, beacon, hazards, blinkers, wipers, diff lock, lift axle, trailer,
  camera), look pad, horn, CB push-to-talk, truck sounds and haptics

### Keyboard & mouse
| Tool | What you get |
|---|---|
| **Keyboard + Touchpad** | Compact keyboard next to (or, in portrait, below) a touchpad with Left / Middle / Right buttons |
| **Keyboard** | Standard layout with F-keys, symbols and arrows |
| **Num Pad + Touchpad** | Numeric keypad with Num Lock indicator and a large touchpad |
| **Keyboard Complete** | Full 104-key layout: F-row, navigation cluster, arrows and numpad |
| **Presentation Remote** | Next / previous slide, talk timer, start / end show, black screen, laser pointer, and a pointer pad that works as a touchpad or as an air pointer (point the phone at the screen, gyroscope); the phone's volume keys also change slides |
| **Type on PC** | Paste or type text on the phone and the PC types it out: passwords, codes, long text; adjustable speed and start delay |

Touchpad gestures (tap, two-/three-finger tap, two-finger scroll, drag), sticky and lockable
modifiers, Caps / Num Lock indicators that follow the PC, media keys, and portrait / landscape for
every mode with an adjustable keyboard height.

### Phone tools
| Tool | What it does |
|---|---|
| **Device Info** | Model, Android version, processor, RAM (live), display, cameras and hardware features |
| **Battery Info** | Health, temperature, voltage, design vs. estimated capacity, wear, cycle count, charger rating |
| **Charging Meter** | Live charge / discharge current, power, min / max and a current graph with state colours |
| **Storage Analyzer** | Space used by apps, photos, videos and audio; largest files and apps |
| **Sensor Tester** | 12 hardware tests (GPS, Wi-Fi, Bluetooth, NFC, mobile network, cameras, flashlight, fingerprint & face, microphones, speakers, buttons, infrared), vibration / multi-touch / display / metal-detector tests, and a live test for every sensor. Each test explains what it does and how to test it |
| **Screen Time** | Daily and weekly screen time, unlocks, longest session and per-app usage |

### Utilities
| Tool | What it does |
|---|---|
| **Speedometer** | GPS speed with trips (distance, time, max and average speed), saved trip history, and each trip's route on an OpenStreetMap map: live while driving, coloured by speed afterwards, exportable as GPX |
| **Night Screen** | Dims the screen below the lowest brightness; adjustable from the notification and Quick Settings |
| **Live View** | Watch the phone camera (security / document camera) or its screen live in a PC browser, up to original quality (screen at its own resolution, camera up to 4K, full-resolution snapshots); shortcut to File Transfer's Live view |
| **Scanner** | Reads QR codes and barcodes, with actions for links, Wi-Fi, contacts, and typing the code on the PC |
| **QR & Barcode Maker** | QR codes in many designs, plus Code 128, EAN-13, EAN-8, UPC-A and Code 39 barcodes |
| **File Transfer** | Send files and text between the phone and PCs, or PC to PC through the phone, from any web browser on the same Wi-Fi or hotspot. New browsers must be approved on the phone and can't see its files unless given shared-folder or full access. Also in the share sheet as *Send to PC*. **Clipboard sync**: Ctrl + V on the PC puts text on the phone's clipboard; the phone's clipboard reaches the PC from its notification, a Quick Settings tile or while the screen is open. **Live view** shows the phone's camera (security / document cam, switch lens, light, snapshot) or mirrors its screen in the browser while you share it. Nothing to install on the PCs |

A fullscreen toggle is available on every screen and is remembered.

---

## Requirements

| | |
|---|---|
| Phone | Android 9 (API 28) or newer. PC control needs Bluetooth; some manufacturer systems disable the Bluetooth HID Device profile, in which case the PC tools stay "Offline" (all phone tools still work) |
| PC | Any computer with Bluetooth (tested with Windows 11), only for the game and keyboard & mouse tools. File Transfer needs only a web browser on the same network |
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

1. Open the **ETS2 Truck Controller** or any **Keyboard & Mouse** tool and allow the **Nearby
   devices** (Bluetooth) permission.
2. In the pairing dialog (or the Bluetooth button in the header) tap **Make visible**.
3. On the PC open **Bluetooth settings → Add device** and select the phone.
4. The status pill turns green: **Connected**.

After the first pairing the app reconnects to the same PC automatically. One connection is shared
by all PC tools, so you can switch between the truck controller and the keyboard & mouse screens
freely.

> On the PC the controller is named **TruckPad** (its original name), so existing pairings and
> ETS2 bindings keep working.

> **Re-pair after updates that change the controller layout.** The PC stores the device description
> when pairing. If the app shows *Connected* but nothing reacts, remove the phone in the PC's
> Bluetooth settings, unpair the PC on the phone, and pair again.

To check the controller on Windows, press **Win + R**, run `joy.cpl`, select **TruckPad** and open
**Properties**.

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
Set the game's steering non-linearity to 0 %; the app already applies its own curve.

4. Bind the buttons: in the app open **Button mappings** (sliders icon in the header) and tap a row
   to send that button while ETS2 is waiting for input.

| Button | Action | Button | Action |
|---|---|---|---|
| 1 | Gear up | 14 | Right blinker |
| 2 | Gear down | 15 | Hazard lights |
| 3 | Splitter | 16 | Light modes |
| 4 | Range | 17 | Beacon |
| 5 | Engine start / stop | 18 | High beam |
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
    HomeActivity.kt                   Home screen: searchable tool hub
    MainActivity.kt                   ETS2 truck controller screen
    InputActivity.kt                  Keyboard & mouse tools
    HidActivity.kt                    Shared Bluetooth permission / pairing / header logic
    ToolActivity.kt                   Shared layout for the phone tools
    DeviceInfoActivity.kt, BatteryInfoActivity.kt, BatteryActivity.kt,
    StorageActivity.kt, ScreenTimeActivity.kt, SensorTesterActivity.kt,
    SpeedometerActivity.kt, ScannerActivity.kt, QrGeneratorActivity.kt, FileTransferActivity.kt
    hardware/                         Hardware tests (GPS, Wi-Fi, Bluetooth, NFC, camera, audio …)
    sensors/, battery/, screentime/, speed/, scan/, qr/, dimmer/
    transfer/                         File Transfer web server and background service
  app/src/main/assets/transfer/       Browser page served to the PC by File Transfer
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
| PC tools stay **Offline** after pairing | Tap the Bluetooth button in the header and select the PC, or remove and re-pair the phone |
| PC cannot find the phone | Tap **Make visible** again; visibility lasts 2 minutes |
| *Connected*, but keys / mouse / controller do nothing | Re-pair (see above); the PC still has an older device description |
| ETS2 does not list the controller | Connect the phone first, then start the game |
| Pedals inverted or stuck at 100 % | Enable **Invert** for that axis in ETS2 |
| A phone tool says a permission is needed | Tap **Allow**, or enable it in the app's settings; each tool only asks for what it uses |
| A hardware test says "Not on this phone" | The phone doesn't report that component (for example no infrared blaster) |
| File Transfer page doesn't open on the PC | Both devices must be on the same network; guest Wi-Fi often blocks devices from reaching each other, so use the phone's hotspot instead |
