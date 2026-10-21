package com.kybercypher.marauder.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Built on Koko's palette.
 *
 * These are not approximations of the Marauder's colors — they ARE the
 * Marauder's colors, read out of the firmware's TFT table and converted from
 * RGB565 to sRGB. That the Kyber//Cypher purple and cyberpunk green already
 * exist in that table is the reason this app can be both identities at once
 * without compromising either.
 *
 *   TFT_VIOLET   0x915C -> #9428E6
 *   TFT_GREEN    0x07E0 -> #00FF00
 *   TFT_MAGENTA  0xF81F -> #FF00FF
 *   TFT_CYAN     0x07FF -> #00FFFF
 *   TFT_ORANGE   0xFDA0 -> #FFB600
 *   STATUSBAR    0x4A49 -> #4A494A
 */
object Palette {
    val Ground = Color(0xFF000000)      // OLED true black
    val Violet = Color(0xFF9428E6)      // chrome, brand, structure
    val VioletDim = Color(0xFF3A1059)   // inactive chrome
    val VioletText = Color(0xFFB36BF0)  // the one purple safe for small text
    val Green = Color(0xFF00FF00)       // live / healthy / navigate
    val Magenta = Color(0xFFFF00FF)     // passive recon
    val Cyan = Color(0xFF00FFFF)        // BLE / benign spoof
    val Orange = Color(0xFFFFB600)      // metering, and ALL warnings and errors
    val Red = Color(0xFFFF0000)         // RESERVED: the radio is transmitting
    val Yellow = Color(0xFFFFFF00)      // targeted variant
    val White = Color(0xFFFFFFFF)       // raw capture
    val Chrome = Color(0xFF4A494A)      // status strip fill
    val Dim = Color(0xFF6E6E6E)         // secondary text
}

/**
 * MEASURED CONTRAST ON PURE BLACK (WCAG). This is why Violet is chrome and not
 * body text:
 *
 *   Yellow  19.56   text
 *   Cyan    16.75   text
 *   Green   15.30   text
 *   Orange  11.94   text
 *   Magenta  6.70   text
 *   Red      5.25   text
 *   Violet   3.71   LARGE TEXT AND UI ONLY - fails AA for body copy
 *   Chrome   2.34   decorative fill only
 *
 * VioletText (#B36BF0) is the lightened variant at ~6.0:1 for the rare case a
 * purple-tinted small label is genuinely wanted. Never tint data columns.
 */
object ContrastNotes
