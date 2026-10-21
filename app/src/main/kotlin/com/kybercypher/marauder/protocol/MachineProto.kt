package com.kybercypher.marauder.protocol

import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

/**
 * The firmware's own machine-readable handshake — upstream, no fork required.
 * CommandLine.cpp:550 emits a single line:
 *
 *   @MARAUDER:{"protocol":1,"tx":"<id>","command":"protocolinfo",
 *              "status":"success","code":"OK","firmware":"<ver>",
 *              "capabilities":[...],"backupPath":"/spiffs"}
 *
 * The transaction id is validated by the firmware; an invalid one comes back as
 * status "error", code "INVALID_TRANSACTION".
 */
object MachineProto {

    /** Firmware-safe transaction id: short, alphanumeric. */
    fun newTransactionId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    fun handshakeCommand(tx: String) = "protocolinfo --machine $tx"

    fun parse(json: String): ProtocolInfo? = try {
        val o = JSONObject(json)
        val caps = o.optJSONArray("capabilities")
        ProtocolInfo(
            protocol = o.optInt("protocol", -1),
            transactionId = o.optString("tx"),
            status = o.optString("status"),
            code = o.optString("code"),
            firmware = o.optString("firmware"),
            capabilities = buildList {
                if (caps != null) for (i in 0 until caps.length()) add(caps.getString(i))
            },
        )
    } catch (_: Throwable) {
        null
    }

    fun ProtocolInfo.isSuccess() = status.lowercase(Locale.ROOT) == "success"
}
