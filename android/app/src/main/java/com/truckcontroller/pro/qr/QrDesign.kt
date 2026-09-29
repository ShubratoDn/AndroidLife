package com.truckcontroller.pro.qr

import android.graphics.Color

enum class ModuleShape(val label: String) {
    SQUARE("Square"), ROUNDED("Rounded"), DOTS("Dots"), LIQUID("Liquid"),
    DIAMOND("Diamond"), VERTICAL("V-Bars"), HORIZONTAL("H-Bars"),
}

enum class EyeShape(val label: String) { SQUARE("Square"), ROUNDED("Rounded"), CIRCLE("Circle"), LEAF("Leaf") }

enum class LogoMode(val label: String) { NONE("None"), APP("PhoneDeck"), CUSTOM("My image") }

/** Everything that changes how a QR code looks (not what it contains). */
data class QrDesign(
    val module: ModuleShape = ModuleShape.SQUARE,
    val eye: EyeShape = EyeShape.SQUARE,
    val color: Int = Color.BLACK,
    /** Second colour for a diagonal gradient; null = solid. */
    val color2: Int? = null,
    /** Colour of the three corner "eyes"; null = same as the modules. */
    val eyeColor: Int? = null,
    /** Color.TRANSPARENT for a transparent PNG. */
    val background: Int = Color.WHITE,
    val logo: LogoMode = LogoMode.NONE,
    /** Text on a frame label under the code; null = no frame. */
    val frameText: String? = null,
)

data class ColorChoice(val name: String, val color: Int, val color2: Int? = null)

object QrPresets {

    val COLORS = listOf(
        ColorChoice("Black", Color.BLACK),
        ColorChoice("Orange", Color.parseColor("#F97316")),
        ColorChoice("Navy", Color.parseColor("#1E3A8A")),
        ColorChoice("Emerald", Color.parseColor("#047857")),
        ColorChoice("Purple", Color.parseColor("#6D28D9")),
        ColorChoice("Red", Color.parseColor("#B91C1C")),
        ColorChoice("Sunset", Color.parseColor("#F97316"), Color.parseColor("#DB2777")),
        ColorChoice("Ocean", Color.parseColor("#1D4ED8"), Color.parseColor("#0891B2")),
        ColorChoice("Forest", Color.parseColor("#15803D"), Color.parseColor("#0F766E")),
        ColorChoice("Royal", Color.parseColor("#7C3AED"), Color.parseColor("#2563EB")),
        ColorChoice("Fire", Color.parseColor("#DC2626"), Color.parseColor("#D97706")),
        ColorChoice("Neon", Color.parseColor("#22D3EE"), Color.parseColor("#A3E635")),
    )

    val BACKGROUNDS = listOf(
        ColorChoice("White", Color.WHITE),
        ColorChoice("Cream", Color.parseColor("#FFF7E6")),
        ColorChoice("Sky", Color.parseColor("#EFF6FF")),
        ColorChoice("Dark", Color.parseColor("#0B0E14")),
        ColorChoice("Transparent", Color.TRANSPARENT),
    )

    val EYE_COLORS = listOf<ColorChoice?>(
        null,
        ColorChoice("Black", Color.BLACK),
        ColorChoice("Orange", Color.parseColor("#F97316")),
        ColorChoice("Red", Color.parseColor("#DC2626")),
        ColorChoice("Blue", Color.parseColor("#2563EB")),
        ColorChoice("Green", Color.parseColor("#16A34A")),
    )

    /** Ready-made looks shown as thumbnails. */
    val DESIGNS = listOf(
        "Classic" to QrDesign(),
        "Rounded" to QrDesign(ModuleShape.ROUNDED, EyeShape.ROUNDED, Color.parseColor("#1E3A8A")),
        "Dots" to QrDesign(ModuleShape.DOTS, EyeShape.CIRCLE, Color.parseColor("#6D28D9")),
        "Liquid" to QrDesign(ModuleShape.LIQUID, EyeShape.ROUNDED, Color.parseColor("#047857")),
        "Sunset" to QrDesign(ModuleShape.ROUNDED, EyeShape.LEAF, Color.parseColor("#F97316"), Color.parseColor("#DB2777")),
        "Ocean" to QrDesign(ModuleShape.DOTS, EyeShape.ROUNDED, Color.parseColor("#1D4ED8"), Color.parseColor("#0891B2"),
            background = Color.parseColor("#EFF6FF")),
        "Neon" to QrDesign(ModuleShape.LIQUID, EyeShape.CIRCLE, Color.parseColor("#22D3EE"), Color.parseColor("#A3E635"),
            background = Color.parseColor("#0B0E14")),
        "Royal" to QrDesign(ModuleShape.DIAMOND, EyeShape.LEAF, Color.parseColor("#7C3AED"), Color.parseColor("#2563EB")),
        "Bars" to QrDesign(ModuleShape.VERTICAL, EyeShape.ROUNDED, Color.parseColor("#B91C1C")),
        "Truck" to QrDesign(ModuleShape.ROUNDED, EyeShape.ROUNDED, Color.parseColor("#0B0E14"),
            eyeColor = Color.parseColor("#F97316"), logo = LogoMode.APP),
        "Scan me" to QrDesign(ModuleShape.SQUARE, EyeShape.ROUNDED, Color.parseColor("#DC2626"), Color.parseColor("#D97706"),
            frameText = "SCAN ME"),
        "Cream" to QrDesign(ModuleShape.HORIZONTAL, EyeShape.LEAF, Color.parseColor("#78350F"),
            eyeColor = Color.parseColor("#B45309"), background = Color.parseColor("#FFF7E6")),
    )
}
