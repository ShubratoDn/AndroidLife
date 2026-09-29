# TruckPad — Installation Guide

This guide takes you from downloading TruckPad to driving in Euro Truck Simulator 2 or using your
phone as a keyboard and mouse.

- [1. What you need](#1-what-you-need)
- [2. Get the app](#2-get-the-app)
- [3. Install the app on your phone](#3-install-the-app-on-your-phone)
- [4. First launch](#4-first-launch)
- [5. Pair the phone with your PC](#5-pair-the-phone-with-your-pc)
- [6. Check that the PC sees TruckPad](#6-check-that-the-pc-sees-truckpad)
- [7. Set up Euro Truck Simulator 2](#7-set-up-euro-truck-simulator-2)
- [8. Use the keyboard & mouse modes](#8-use-the-keyboard--mouse-modes)
- [9. Updating and uninstalling](#9-updating-and-uninstalling)
- [10. Troubleshooting](#10-troubleshooting)

---

## 1. What you need

| Item | Details |
|---|---|
| Android phone | Android 9 or newer, with Bluetooth |
| PC | Windows 10 / 11, Linux or macOS with Bluetooth (built in or a USB adapter) |
| Euro Truck Simulator 2 | Only needed for the truck controller |
| To build the app yourself (optional) | JDK 17 or newer and the Android SDK (platform 35) |

No software has to be installed on the PC. TruckPad connects as a standard Bluetooth device.

---

## 2. Get the app

You can either use a ready-made APK file or build it from the source code.

### Option A — Use a ready-made APK

If an APK file (`TruckPad.apk` or `app-debug.apk`) is attached to the repository's **Releases**
page, download it to your phone or PC and continue with
[step 3](#3-install-the-app-on-your-phone).

### Option B — Build the APK from source

1. Download the project:
   ```bash
   git clone https://github.com/ShubratoDn/AndroidLife.git
   cd AndroidLife/android
   ```
   Or use **Code → Download ZIP** on GitHub, extract it and open the `android` folder in a terminal.

2. Tell Gradle where the Android SDK is by creating the file `android/local.properties`:
   ```properties
   # Windows example
   sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
   # Linux / macOS example
   # sdk.dir=/home/<you>/Android/Sdk
   ```
   If you use Android Studio, simply open the `android` folder in it instead — Android Studio creates
   this file for you.

3. Build the APK:
   ```bash
   ./gradlew assembleDebug        # Linux / macOS
   gradlew.bat assembleDebug      # Windows
   ```
   The first build downloads Gradle and the build dependencies, so it needs an internet
   connection and takes a few minutes.

4. The finished APK is here:
   ```
   android/app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 3. Install the app on your phone

### Option A — Copy the APK to the phone (no cable tools needed)

1. Copy `app-debug.apk` to the phone (USB cable, cloud drive, messenger, e-mail …).
2. Open it with the phone's **Files** app.
3. When asked, allow **Install unknown apps** for the Files app, then tap **Install**.
4. If Play Protect warns about an unknown developer, choose **Install anyway**.

### Option B — Install over USB with adb

1. On the phone enable **Developer options**: *Settings → About phone →* tap **Build number** seven
   times.
2. In *Settings → Developer options* turn on **USB debugging**.
   - Xiaomi / Redmi / POCO (MIUI / HyperOS): also turn on **Install via USB**.
3. Connect the phone, unlock it and accept the **Allow USB debugging** prompt.
4. Install:
   ```bash
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```
   If the phone shows an install prompt, tap **Install**.

After installation, **TruckPad** appears in the app drawer with an orange truck icon.

---

## 4. First launch

1. Open **TruckPad**.
2. Allow the **Nearby devices** permission — it is needed for Bluetooth.
3. If Bluetooth is off, TruckPad asks to turn it on; tap **Allow**.
4. The landing page opens. On the very first launch the **Bluetooth HID Connection** dialog opens
   automatically to guide you through pairing.

The landing page has two sections:

- **Game controller** — *ETS2 Truck Controller*
- **Keyboard & mouse** — *Keyboard + Touchpad*, *Keyboard*, *Num Pad + Touchpad*,
  *Keyboard Complete* and *Presentation Remote*

---

## 5. Pair the phone with your PC

Pairing is done **once**. All modes share the same connection.

1. In TruckPad tap **PAIR PC**, then **Make visible** and allow it. The phone stays visible for
   2 minutes.
2. On the PC:
   - **Windows 11**: *Settings → Bluetooth & devices → Add device → Bluetooth*
   - **Windows 10**: *Settings → Devices → Add Bluetooth or other device → Bluetooth*
   - **macOS / Linux**: open the Bluetooth settings and look for new devices
3. Select your phone and confirm that the pairing code matches on both screens.
4. TruckPad shows a green **Connected: \<PC name\>** status.

From now on TruckPad reconnects to this PC automatically when you open it. If it does not, tap
**PAIR PC** and tap the PC's name in the list of paired devices.

> **After updating TruckPad**, if it says *Connected* but the PC does not react, the PC is still
> using the old device description. Remove the phone from the PC's Bluetooth settings, unpair the
> PC on the phone, and pair again.

---

## 6. Check that the PC sees TruckPad

On Windows:

1. Press **Win + R**, type `joy.cpl` and press **Enter**.
2. Select **TruckPad** and click **Properties**.
3. Open the **ETS2 Truck Controller** in the app and turn the wheel, press the pedals and tap
   buttons — the bars and button numbers in the window should react.

For the keyboard & mouse, open **Keyboard + Touchpad** and type into any text field on the PC or
move the pointer with the touchpad.

---

## 7. Set up Euro Truck Simulator 2

1. Connect TruckPad **before** starting the game (the game only detects controllers at startup).
2. In ETS2 open **Options → Controls**.
3. Choose a *keyboard + joystick* control setup and select **TruckPad** as the input device.
4. Bind the axes: click the action in the game, then move the control in TruckPad.

   | ETS2 action | Move in TruckPad |
   |---|---|
   | Steering | Turn the wheel |
   | Throttle | Slide the **GAS** pedal up |
   | Brake | Slide the **BRAKE** pedal up |
   | Look left / right *(optional)* | Drag the look pad sideways |
   | Look up / down *(optional)* | Drag the look pad up / down |

   - Use **full range** for throttle and brake. If a pedal reads 100 % while you are not touching
     it, switch on **Invert** for that pedal.
   - Set **steering non-linearity to 0 %** — TruckPad applies its own curve (adjustable in the app's
     settings).

5. Bind the buttons: click an action in the game, then in TruckPad tap the **sliders icon (Button
   mappings)** in the header and tap the row with the same name. The full button list is in the
   [README](README.md#setting-up-euro-truck-simulator-2).

6. Drive: start the engine, release the parking brake and go.

---

## 8. Use the keyboard & mouse modes

Open a mode from the landing page. Every screen works in **portrait and landscape**; the rotate
button in the header switches between *Auto-rotate*, *Portrait* and *Landscape* (remembered per
mode). The 🏠 button returns to the landing page.

**Touchpad**

| Gesture | Action |
|---|---|
| One finger | Move the pointer |
| Tap | Left click |
| Two-finger tap | Right click |
| Three-finger tap | Middle click |
| Two-finger drag | Scroll |
| Tap, then touch again and drag | Drag and drop |
| Slide along the right edge | Scroll |

**Keyboard**

- **Ctrl / Shift / Alt**: tap once to apply to the next key, tap twice to lock (a bar appears under
  the key), tap again to release — or hold with one finger and press a key with another.
- **Win** works like a real key: tap it to open the Start menu.
- In portrait, drag the **grip bar** above the keys to change the keyboard height.
- Media keys (mute, volume, previous, play / pause, next) are in the header (landscape) or in the
  row above the keys (portrait).

**Presentation Remote**

- **NEXT SLIDE / PREVIOUS** send Page Down / Page Up — this works in PowerPoint, PDF viewers and
  most slide apps.
- The phone's **volume keys** also change slides (can be turned off in settings).
- **START** (F5), **CURRENT** (Shift + F5), **BLACK** (B), **END** (Esc), **LASER** (Ctrl + L),
  **ERASE** (E), **FIRST** (Home), **LAST** (End), plus a talk timer and a pointer pad.

**Settings** (gear icon): pointer speed, scroll speed, natural scrolling, tap to click, key
vibration and volume-key slide control.

---

## 9. Updating and uninstalling

- **Update**: install the new APK over the old one (step 3). Your settings are kept. If the new
  version changes the controller layout, re-pair the phone (see [step 5](#5-pair-the-phone-with-your-pc))
  and check the controller in ETS2 once.
- **Uninstall**: long-press the TruckPad icon → **Uninstall**. Also remove the phone from the PC's
  Bluetooth device list.

---

## 10. Troubleshooting

| Problem | Solution |
|---|---|
| "App not installed" | Uninstall the old version first, or check that the APK downloaded completely |
| `INSTALL_FAILED_USER_RESTRICTED` over adb | Xiaomi: enable **Install via USB**; keep the phone unlocked and tap **Install** on the prompt |
| Status stays **Offline** | Tap **PAIR PC** and select the PC. If it never connects, your phone's system may not support the Bluetooth HID Device profile |
| The PC cannot find the phone | Tap **Make visible** again (visibility lasts 2 minutes) and keep TruckPad open |
| *Connected*, but nothing happens on the PC | Remove and re-pair the phone (step 5) |
| ETS2 does not show TruckPad | Connect first, then start the game |
| Menu cursor moves by itself in ETS2 | Clear any old axis bindings in *Options → Controls* and bind the axes again |
| A pedal is always at 100 % | Enable **Invert** for that pedal in ETS2 |
| Keyboard types wrong symbols | Set the PC's keyboard layout to **English (US)** |
