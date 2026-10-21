package com.kybercypher.marauder.protocol

/** A console line, classified. Unrecognised lines stay as [Raw] and are shown verbatim. */
sealed interface ParsedLine {
    val raw: String

    data class CommandEcho(override val raw: String, val command: String) : ParsedLine
    data class Ap(override val raw: String, val value: AccessPoint) : ParsedLine
    data class StationRow(override val raw: String, val value: Station) : ParsedLine
    data class Ssid(override val raw: String, val value: SsidEntry) : ParsedLine
    data class Ble(override val raw: String, val value: BleDevice) : ParsedLine
    data class Flipper(override val raw: String, val value: FlipperDevice) : ParsedLine
    data class Simple(override val raw: String, val value: SimpleEntry) : ParsedLine
    data class Counts(override val raw: String, val selected: Int, val unselected: Int?) : ParsedLine
    data class Sighting(override val raw: String, val value: ApSighting) : ParsedLine
    data class Probe(override val raw: String, val value: ProbeSighting) : ParsedLine
    data class SettingField(override val raw: String, val key: String, val value: String) : ParsedLine
    data class Machine(override val raw: String, val json: String) : ParsedLine
    data class ScanStopped(override val raw: String, val what: String) : ParsedLine
    data class ScanStarted(override val raw: String, val what: String) : ParsedLine
    data class Raw(override val raw: String) : ParsedLine
}
