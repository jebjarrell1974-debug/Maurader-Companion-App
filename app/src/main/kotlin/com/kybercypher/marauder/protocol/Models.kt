package com.kybercypher.marauder.protocol

// NOTE: ScanClass lives in device/Commands.kt, which is where the gating model
// is. It is deliberately NOT duplicated here.

data class AccessPoint(
    val index: Int,
    val channel: Int,
    val essid: String,
    val rssi: Int,
    val selected: Boolean = false,
)

data class Station(val index: Int, val mac: String, val selected: Boolean = false)

data class ApWithStations(val ap: AccessPoint, val stations: List<Station>)

data class SsidEntry(val index: Int, val essid: String, val selected: Boolean = false)

data class BleDevice(val index: Int, val rssi: Int, val name: String)

data class FlipperDevice(val index: Int, val mac: String, val name: String)

data class SimpleEntry(val index: Int, val value: String)

data class SettingEntry(val name: String, val type: String, val value: String) {
    val isRedacted get() = value == "[redacted]"
    val asBool: Boolean? get() = when (value.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

/** A live per-packet telemetry line. Arrives whether or not -serial is on. */
data class ApSighting(val rssi: Int, val channel: Int, val mac: String, val essid: String)
data class ProbeSighting(val rssi: Int, val channel: Int, val clientMac: String, val requesting: String)

/** The firmware's own machine-readable handshake. */
data class ProtocolInfo(
    val protocol: Int,
    val transactionId: String,
    val status: String,
    val code: String,
    val firmware: String,
    val capabilities: List<String>,
)
