package com.kybercypher.marauder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kybercypher.marauder.device.ScanState
import com.kybercypher.marauder.ui.GlowDot
import com.kybercypher.marauder.ui.theme.Palette
import com.kybercypher.marauder.ui.theme.color
import com.kybercypher.marauder.ui.theme.label

/**
 * Mirrors the Marauder's OWN status bar — same fields, same order, same chrome
 * fill (STATUSBAR_COLOR 0x4A49 -> #4A494A). From MenuFunctions::drawStatusBar:
 * GPS sats, "CH: n", "D:nn%" DRAM, "P:nn%" PSRAM, SD, battery %.
 *
 * A v8 owner recognises this instantly, and every field is genuinely useful.
 * It is the single glowing element when a scan is live — one thing glows at a
 * time, and it's whatever is actually happening.
 */
@Composable
fun StatusStrip(
    connected: Boolean,
    scan: ScanState,
    channel: Int?,
    firmware: String?,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        scan.transmitting -> Palette.Red
        scan.running -> scan.scanClass.color()
        else -> Palette.Dim
    }
    // Red chrome means exactly one thing in this app: the radio is emitting.
    val fill = if (scan.transmitting) Palette.Red.copy(alpha = 0.18f)
               else Palette.Chrome.copy(alpha = 0.22f)
    Column(modifier.fillMaxWidth().background(fill)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlowDot(
                color = if (connected) accent else Palette.Dim,
                pulsing = scan.running,
                size = 8,
            )
            Spacer(Modifier.width(4.dp))
            Field("CH", channel?.toString() ?: "--")
            Spacer(Modifier.width(12.dp))
            Field("FW", firmware ?: "--")
            Spacer(Modifier.weight(1f))
            if (scan.transmitting) {
                scan.secondsRemaining?.let {
                    Text(
                        "TX ${it}s",
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.Red,
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                when {
                    scan.transmitting -> "TRANSMITTING"
                    scan.running -> scan.scanClass.label()
                    connected -> "IDLE"
                    else -> "OFFLINE"
                },
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
        if (scan.running && scan.streaming) {
            // Honesty about instrument limits is part of the aesthetic.
            Text(
                "STREAMING — capture is rate-limited and lossy above ~40 pkt/s",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Orange,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Field(key: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$key:", style = MaterialTheme.typography.labelSmall, color = Palette.Dim)
        Spacer(Modifier.width(3.dp))
        // Let the data be the bright thing; labels stay dim.
        Text(value, style = MaterialTheme.typography.labelSmall, color = Palette.White)
    }
}
