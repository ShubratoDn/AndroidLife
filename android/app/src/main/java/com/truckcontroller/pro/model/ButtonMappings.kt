package com.truckcontroller.pro.model

/**
 * HID gamepad button numbers (1-based, as shown in Windows "Game Controllers" and the ETS2 controls menu).
 * Toggle actions are sent as a short press; horn and CB push-to-talk are held while touched.
 */
object HidButton {
    const val GEAR_UP = 1
    const val GEAR_DOWN = 2
    const val SPLITTER = 3
    const val RANGE = 4
    const val ENGINE = 5
    const val PARKING_BRAKE = 6
    const val RETARDER_UP = 7
    const val RETARDER_DOWN = 8
    const val HORN = 9
    const val TRAILER = 10
    const val DIFF_LOCK = 11
    const val AXLE_LIFT = 12
    const val TURN_LEFT = 13
    const val TURN_RIGHT = 14
    const val HAZARDS = 15
    const val LIGHTS = 16
    const val BEACON = 17
    const val HIGH_BEAM = 18
    const val WIPERS = 19
    const val CRUISE_TOGGLE = 20
    const val CRUISE_UP = 21
    const val CRUISE_DOWN = 22
    const val CAMERA = 23
    const val LOOK_LEFT = 24
    const val LOOK_RIGHT = 25
    const val CB_PTT = 26
}

data class ButtonMapping(
    val hidButton: Int,
    val name: String,
    val category: String,
    val description: String,
)

val BUTTON_MAPPINGS = listOf(
    ButtonMapping(HidButton.GEAR_UP, "Shift Gear Up", "Transmission", "Shift up one gear sequentially or splitter"),
    ButtonMapping(HidButton.GEAR_DOWN, "Shift Gear Down", "Transmission", "Shift down one gear"),
    ButtonMapping(HidButton.SPLITTER, "Shifter Splitter Toggle", "Transmission", "Toggle Low/High split for 12/16/18-speed boxes"),
    ButtonMapping(HidButton.RANGE, "Shifter Range Toggle", "Transmission", "Toggle Low/High range (Gears 1-6 / 7-12)"),
    ButtonMapping(HidButton.ENGINE, "Engine Start / Stop", "Drive", "Ignition electrics and diesel starter"),
    ButtonMapping(HidButton.PARKING_BRAKE, "Parking Brake", "Drive", "Pneumatic handbrake locking drive axles"),
    ButtonMapping(HidButton.RETARDER_UP, "Retarder Increase", "Drive", "Increase engine retarder braking step (1-5)"),
    ButtonMapping(HidButton.RETARDER_DOWN, "Retarder Decrease", "Drive", "Decrease retarder braking step"),
    ButtonMapping(HidButton.HORN, "Truck Air Horn", "Drive", "Sound dual roof trumpet air horn (held)"),
    ButtonMapping(HidButton.TRAILER, "Attach / Detach Trailer", "Drive", "Fifth wheel coupling lock"),
    ButtonMapping(HidButton.DIFF_LOCK, "Differential Lock", "Drive", "Lock rear axle differentials for mud/gravel"),
    ButtonMapping(HidButton.AXLE_LIFT, "Lift / Lower Axle", "Drive", "Pneumatic lift of rear tag or pusher axle"),
    ButtonMapping(HidButton.TURN_LEFT, "Left Blinker", "Lighting", "Turn indicator left"),
    ButtonMapping(HidButton.TURN_RIGHT, "Right Blinker", "Lighting", "Turn indicator right"),
    ButtonMapping(HidButton.HAZARDS, "Hazard Lights", "Lighting", "Emergency 4-way flashers"),
    ButtonMapping(HidButton.LIGHTS, "Headlight Modes", "Lighting", "Cycle Off -> Parking -> Low -> High"),
    ButtonMapping(HidButton.BEACON, "Roof Warning Beacon", "Lighting", "Amber flashing roof hazard beacons"),
    ButtonMapping(HidButton.HIGH_BEAM, "High Beam", "Lighting", "Toggle the high beam headlights"),
    ButtonMapping(HidButton.WIPERS, "Windshield Wipers", "Cabin", "Cycle wiper speed modes"),
    ButtonMapping(HidButton.CRUISE_TOGGLE, "Cruise Control", "Drive", "Engage / disengage automatic cruise control"),
    ButtonMapping(HidButton.CRUISE_UP, "Cruise Speed (+)", "Drive", "Increase cruise setpoint by 5 km/h"),
    ButtonMapping(HidButton.CRUISE_DOWN, "Cruise Speed (-)", "Drive", "Decrease cruise setpoint by 5 km/h"),
    ButtonMapping(HidButton.CAMERA, "Camera Viewpoint", "Camera", "Interior / chase / top camera"),
    ButtonMapping(HidButton.LOOK_LEFT, "Look Left 90°", "Camera", "Quick mirror and shoulder glance"),
    ButtonMapping(HidButton.LOOK_RIGHT, "Look Right 90°", "Camera", "Quick mirror and window glance"),
    ButtonMapping(HidButton.CB_PTT, "CB Radio Push-To-Talk", "Cabin", "Broadcast on CB Radio (held)"),
)
