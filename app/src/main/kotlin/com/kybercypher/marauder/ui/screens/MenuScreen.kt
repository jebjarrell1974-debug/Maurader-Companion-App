package com.kybercypher.marauder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kybercypher.marauder.device.Commands
import com.kybercypher.marauder.device.MarauderCommand
import com.kybercypher.marauder.device.ScanState
import com.kybercypher.marauder.ui.Breadcrumb
import com.kybercypher.marauder.ui.Rule
import com.kybercypher.marauder.ui.theme.Palette
import com.kybercypher.marauder.ui.theme.color
import com.kybercypher.marauder.ui.theme.label

/**
 * The scan menu. Only passive modes are offered.
 *
 * Transmit-class commands (deauth, badmsg, sleep, auth) are deliberately not in
 * this build. They are what the firmware colours red, and shipping a one-tap
 * button for them on a phone that lives in a pocket is a bad idea. They remain
 * reachable through the raw console for anyone who means it.
 */
@Composable
fun MenuScreen(
    scan: ScanState,
    streamEnabled: Boolean,
    onToggleStream: (Boolean) -> Unit,
    onRun: (MarauderCommand) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Breadcrumb("marauder", "scan", if (scan.running) scan.label else "idle",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Rule()

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (streamEnabled) "STREAM PACKETS TO PHONE" else "SD ONLY (recommended)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (streamEnabled) Palette.Orange else Palette.Green,
                )
                Text(
                    if (streamEnabled)
                        "Adds -serial. Capped at ~7.7 KB/s; stalls the board's loop and degrades the SD capture."
                    else
                        "Board records a complete capture to SD. Phone still gets live telemetry.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Dim,
                )
            }
            Spacer(Modifier.width(10.dp))
            com.kybercypher.marauder.ui.Pill(
                if (streamEnabled) "ON" else "OFF",
                if (streamEnabled) Palette.Orange else Palette.Dim,
            ) { onToggleStream(!streamEnabled) }
        }
        Rule()

        if (scan.running) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "RUNNING  ${scan.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scan.scanClass.color(),
                    modifier = Modifier.weight(1f),
                )
                com.kybercypher.marauder.ui.Pill("STOP", Palette.Orange) { onStop() }
            }
            Rule()
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(Commands.passiveCatalog, key = { it.id }) { cmd ->
                CommandRow(cmd, enabled = !scan.running) { onRun(cmd) }
            }
        }
    }
}

@Composable
private fun CommandRow(cmd: MarauderCommand, enabled: Boolean, onClick: () -> Unit) {
    val accent = cmd.scanClass.color()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A 2dp bar in the firmware's own semantic colour for this mode.
        Box(Modifier.width(3.dp).height(20.dp)
            .background(if (enabled) accent else Palette.VioletDim, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                cmd.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Palette.White else Palette.Dim,
            )
            Text(
                cmd.command,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Dim,
            )
        }
        Text(
            cmd.scanClass.label(),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) accent else Palette.VioletDim,
        )
    }
}
