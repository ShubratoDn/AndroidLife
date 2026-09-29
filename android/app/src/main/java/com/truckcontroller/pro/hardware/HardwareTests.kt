package com.truckcontroller.pro.hardware

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.nfc.NfcAdapter
import com.truckcontroller.pro.R

/** A hardware test that opens its own screen from the Sensor Tester list. */
class HardwareTest(
    val section: Section,
    val icon: Int,
    val title: String,
    val tag: String,
    val what: String,
    val how: String,
    val activity: Class<out Activity>,
    val available: (Context) -> Boolean,
) {
    enum class Section { CONNECTIVITY, HARDWARE }
}

object HardwareTests {

    private fun Context.has(feature: String) = packageManager.hasSystemFeature(feature)

    val all = listOf(
        HardwareTest(
            HardwareTest.Section.CONNECTIVITY, R.drawable.ic_satellite, "GPS / GNSS", "Satellites & location",
            "Receives signals from navigation satellites (GPS, GLONASS, Galileo, BeiDou…) to find your position. " +
                "Shows every satellite in view, its signal strength, which ones are used for the fix, and whether " +
                "the phone supports dual-frequency (L5) for better accuracy.",
            "go outside or stand next to a window with a clear view of the sky and wait 30–60 seconds. " +
                "A healthy GPS sees 20+ satellites, uses 8+ of them, and reaches an accuracy under 10 m.",
            GpsTestActivity::class.java,
        ) { it.has(PackageManager.FEATURE_LOCATION_GPS) },
        HardwareTest(
            HardwareTest.Section.CONNECTIVITY, R.drawable.ic_wifi, "Wi-Fi", "Connection & nearby networks",
            "Shows the current Wi-Fi connection (signal, speed, band, channel, Wi-Fi generation, IP address), which " +
                "Wi-Fi standards and bands the chip supports, and scans for nearby networks.",
            "connect to a network and walk away from the router; the signal (dBm) should drop smoothly. Tap " +
                "Scan to list nearby networks. -30 to -60 dBm is excellent, below -80 dBm is weak.",
            WifiTestActivity::class.java,
        ) { it.has(PackageManager.FEATURE_WIFI) },
        HardwareTest(
            HardwareTest.Section.CONNECTIVITY, R.drawable.ic_bluetooth, "Bluetooth", "Adapter, paired & nearby",
            "Checks the Bluetooth adapter and its capabilities (Bluetooth 5 features, LE Audio), lists paired " +
                "devices and scans for nearby Bluetooth devices with their signal strength.",
            "turn Bluetooth on and tap Scan. Nearby phones, earbuds, watches and speakers should appear within " +
                "a few seconds; the signal gets stronger as you move closer to them.",
            BluetoothTestActivity::class.java,
        ) { it.has(PackageManager.FEATURE_BLUETOOTH) },
        HardwareTest(
            HardwareTest.Section.CONNECTIVITY, R.drawable.ic_nfc, "NFC", "Read cards & tags",
            "Near-field communication, used for contactless payments, transit cards and NFC tags. The test reads " +
                "any card or tag: its ID, chip type, memory and stored data (links, text).",
            "turn NFC on, then hold a bank card, transit/ID card or NFC sticker flat against the back of the " +
                "phone (usually near the camera or middle). The phone vibrates and shows the card details.",
            NfcTestActivity::class.java,
        ) { NfcAdapter.getDefaultAdapter(it) != null },
        HardwareTest(
            HardwareTest.Section.CONNECTIVITY, R.drawable.ic_signal, "Mobile network", "SIM, signal & 4G/5G",
            "Shows the SIM card and mobile network: operator, network type (4G/5G), signal strength in dBm and " +
                "signal quality, roaming, eSIM support and number of SIM slots.",
            "insert a SIM and watch the signal. Move near a window or outside; dBm closer to 0 is better " +
                "(-80 dBm is good, -110 dBm is poor).",
            CellularTestActivity::class.java,
        ) { it.has(PackageManager.FEATURE_TELEPHONY) },

        HardwareTest(
            HardwareTest.Section.HARDWARE, R.drawable.ic_camera, "Cameras", "Live preview of each camera",
            "Lists every camera with its resolution, lens (focal length, aperture), stabilization and autofocus, " +
                "and shows a live preview so you can check focus, zoom and image quality.",
            "pick each camera, point it at text a little away from you and tap the screen to focus. The " +
                "picture should be sharp and clean with no spots, blur or colour stains.",
            CameraTestActivity::class.java,
        ) { it.has(PackageManager.FEATURE_CAMERA_ANY) },
        HardwareTest(
            HardwareTest.Section.HARDWARE, R.drawable.ic_flash, "Flashlight", "Torch, brightness & strobe",
            "Turns on the camera flash LED(s) as a torch, with brightness control on supported phones, plus " +
                "strobe and SOS patterns.",
            "tap On and point the phone at a wall. Try the brightness slider and the strobe; the light should " +
                "be steady with no flicker.",
            FlashlightTestActivity::class.java,
        ) { ctx -> hasFlash(ctx) },
        HardwareTest(
            HardwareTest.Section.HARDWARE, R.drawable.ic_fingerprint, "Fingerprint & face", "Biometric unlock",
            "Checks which biometric sensors the phone has (fingerprint, face, iris), whether any are set up, and " +
                "runs a real verification to confirm they recognise you.",
            "tap Verify and touch the fingerprint sensor (or look at the phone for face unlock). Also try a " +
                "finger that isn't registered: it should be rejected.",
            BiometricTestActivity::class.java,
        ) { true },
        HardwareTest(
            HardwareTest.Section.HARDWARE, R.drawable.ic_mic, "Microphones", "Level meter & recording",
            "Shows a live sound level for each microphone and records a short clip you can play back, to find a " +
                "dead or muffled microphone.",
            "pick a microphone, then speak or clap: the meter should jump. Tap Record, speak for 5 seconds and " +
                "listen to the playback; it should be clear, not quiet or crackling. Repeat for each mic.",
            MicrophoneTestActivity::class.java,
        ) { it.has(PackageManager.FEATURE_MICROPHONE) },
        HardwareTest(
            HardwareTest.Section.HARDWARE, R.drawable.ic_speaker, "Speakers & audio", "Speaker, earpiece & headphones",
            "Plays test tones through the loudspeaker, left/right channels and the call earpiece, with a " +
                "frequency sweep for rattles. Also lists connected audio outputs (headphone jack, USB, Bluetooth).",
            "raise the volume and play each tone. Left and Right should come from different sides in stereo " +
                "phones, the earpiece tone only from the top when held to your ear. Plug in headphones to check " +
                "the jack or USB audio.",
            SpeakerTestActivity::class.java,
        ) { true },
        HardwareTest(
            HardwareTest.Section.HARDWARE, R.drawable.ic_square_power, "Hardware buttons", "Volume, power & back",
            "Checks that the physical buttons respond: volume up, volume down, power and back.",
            "press each button. Its tile lights up and counts presses. The power button turns the screen off: " +
                "turn it back on to see it counted. Use the Home button at the top to leave.",
            ButtonsTestActivity::class.java,
        ) { true },
        HardwareTest(
            HardwareTest.Section.HARDWARE, R.drawable.ic_radio, "Infrared blaster", "IR remote transmitter",
            "An infrared LED used to control TVs, air conditioners and set-top boxes like a remote. The test " +
                "shows its frequency range and sends real remote codes.",
            "point the top of the phone at a TV and send a power code. You can also look at the IR LED through " +
                "another phone's camera (front cameras work best): it flashes purple while sending.",
            InfraredTestActivity::class.java,
        ) { ctx -> ctx.getSystemService(ConsumerIrManager::class.java)?.hasIrEmitter() == true },
    )

    fun forClass(c: Class<*>) = all.first { it.activity == c }

    private fun hasFlash(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        cm.cameraIdList.any { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrDefault(false)
}
