package com.truckcontroller.pro.model

enum class ShifterMode(val label: String) {
    SEQUENTIAL("SEQUENTIAL"),
    RANGE_SPLITTER("RANGE-SPLITTER"),
    H_SHIFTER("H-SHIFTER"),
    AUTOMATIC("AUTOMATIC");

    fun next(): ShifterMode = entries[(ordinal + 1) % entries.size]
}

enum class TurnSignal { OFF, LEFT, RIGHT }

enum class ConnectionState { OFFLINE, PAIRING, CONNECTED }

data class CbPreset(val preset: Int, val channel: Int, val name: String, val frequency: String)

val CB_PRESETS = listOf(
    CbPreset(1, 19, "HIGHWAY", "27.185 MHz"),
    CbPreset(2, 9, "EMERGENCY", "27.065 MHz"),
    CbPreset(3, 10, "CONVOY", "27.085 MHz"),
    CbPreset(4, 12, "LOGISTICS", "27.125 MHz"),
    CbPreset(5, 15, "FERRY/PORT", "27.165 MHz"),
    CbPreset(6, 18, "CHATTER", "27.205 MHz"),
)

val LIGHT_LABELS = arrayOf("LIGHT", "PARK", "LOW", "HIGH")
val WIPER_LABELS = arrayOf("WIPERS", "INT", "SLOW", "FAST")

/**
 * Controller state model representing all inputs for Euro Truck Simulator 2
 */
class ControllerState {
    // Steering
    var steeringAngle = 0f                   // Degrees (e.g. -450f to +450f for 900°)
    var steeringNormalized = 0f              // After deadzone / curve (-1.0f to +1.0f)

    // Pedals
    var gas = 0f                             // Accelerator percentage (0f to 100f)
    var brake = 0f                           // Brake percentage (0f to 100f)

    // Shifter
    var gear = 0                             // -2 = R2, -1 = R1, 0 = N, 1..12
    var shifterMode = ShifterMode.SEQUENTIAL

    // Engine & air brakes
    var engineRunning = false
    var parkingBrake = true
    var trailerAttached = true
    var retarderLevel = 0                    // 0 to 5 steps

    // Lights & cabin
    var lightMode = 0                        // 0=off, 1=parking, 2=low, 3=high
    var beaconActive = false
    var highBeam = false
    var hazardActive = false
    var turnSignal = TurnSignal.OFF
    var wiperSpeed = 0                       // 0=off, 1=intermittent, 2=slow, 3=fast

    // Truck functions
    var diffLock = false
    var axleLift = false
    var cameraView = 1                       // 1 to 5
    var cruiseControlActive = false
    var cruiseControlSpeed = 80              // km/h

    // Horn & look
    var hornActive = false
    var lookPanX = 0f                        // -1.0f to 1.0f
    var lookPanY = 0f                        // -1.0f to 1.0f

    // CB radio
    var cbPreset = CB_PRESETS[0]
    var cbMuted = false
    var cbTransmitting = false

    val gearText: String
        get() = when (gear) {
            0 -> "N"
            -1 -> "R1"
            -2 -> "R2"
            else -> gear.toString()
        }
}
