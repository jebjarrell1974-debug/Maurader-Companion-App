package com.kybercypher.marauder.ui.theme

import androidx.compose.ui.graphics.Color
import com.kybercypher.marauder.device.LineKind
import com.kybercypher.marauder.device.ScanClass

/**
 * The firmware's own semantic color grammar, preserved exactly.
 *
 * This mapping exists nowhere but the source — every Marauder user has learned
 * it by eye from the device screen. Keeping it means muscle memory transfers,
 * and it makes RED a safety property rather than decoration: if anything in this
 * app is red, the radio is transmitting. Nothing else may use red. Errors and
 * warnings use orange.
 *
 * Source: the TFT_* color argument at each startScanFromCLI call site in
 * CommandLine.cpp at firmware v1.17.0.
 */
fun ScanClass.color(): Color = when (this) {
    ScanClass.PASSIVE -> Palette.Magenta    // AP/probe/pwn/pinescan/multissid/follow
    ScanClass.TRANSMIT -> Palette.Red       // deauth/badmsg/sleep/auth  -- RESERVED
    ScanClass.BLE -> Palette.Cyan           // fox hunt, funny beacon, CSA, quiet
    ScanClass.NAVIGATE -> Palette.Green     // signal strength, wardrive
    ScanClass.METER -> Palette.Orange       // packet rate, evil portal
    ScanClass.TARGETED -> Palette.Yellow    // targeted variants, rickroll
    ScanClass.RAW -> Palette.White          // raw capture, AirTag
    ScanClass.NONE -> Palette.Dim
}

fun ScanClass.label(): String = when (this) {
    ScanClass.PASSIVE -> "PASSIVE"
    ScanClass.TRANSMIT -> "TRANSMITTING"
    ScanClass.BLE -> "BLE"
    ScanClass.NAVIGATE -> "NAVIGATE"
    ScanClass.METER -> "METER"
    ScanClass.TARGETED -> "TARGETED"
    ScanClass.RAW -> "RAW"
    ScanClass.NONE -> "IDLE"
}

fun LineKind.color(): Color = when (this) {
    LineKind.ECHO -> Palette.Violet
    LineKind.SIGHTING -> Palette.Green
    LineKind.LIST -> Palette.White
    LineKind.SETTING -> Palette.Cyan
    LineKind.STATE -> Palette.Orange
    LineKind.MACHINE -> Palette.Magenta
    LineKind.ANOMALY -> Palette.Orange
    LineKind.PLAIN -> Palette.Dim
}
