package com.kybercypher.marauder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * The scan menu, split into listen and transmit sections.
 *
 * Transmit modes are gated three ways: an explicit confirmation carrying the
 * legal consequence, red chrome for as long as the radio is emitting, and a
 * hard 60-second auto-stop. The auto-stop is the important one — the firmware
 * runs until stopscan, so without it a forgotten tab transmits from a pocket
 * indefinitely.
 */
@Composable
fun MenuScreen(
    scan: ScanState,
    streamEnabled: Boolean,
    onToggleStream: (Boolean) -> Unit,
    onRun: (MarauderCommand) -> Unit,
    onStop: () -> Unit,
    onExtend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing that transmits starts without passing through here.
    var pendingTransmit by remember { mutableStateOf<MarauderCommand?>(null) }

    pendingTransmit?.let { cmd ->
        TransmitConfirmDialog(
            cmd = cmd,
            onDismiss = { pendingTransmit = null },
            onConfirm = { pendingTransmit = null; onRun(cmd) },
        )
    }

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
                Modifier.fillMaxWidth()
                    .background(if (scan.transmitting) Palette.Red.copy(alpha = 0.10f) else Palette.Ground)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (scan.transmitting) "TRANSMITTING  ${scan.label}" else "RUNNING  ${scan.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (scan.transmitting) Palette.Red else scan.scanClass.color(),
                    )
                    scan.secondsRemaining?.let {
                        Text(
                            "auto-stop in ${it}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Dim,
                        )
                    }
                }
                if (scan.transmitting) {
                    com.kybercypher.marauder.ui.Pill("+60s", Palette.Orange) { onExtend() }
                    Spacer(Modifier.width(8.dp))
                }
                com.kybercypher.marauder.ui.Pill("STOP", Palette.Orange) { onStop() }
            }
            Rule()
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionHeader("listen") }
            items(Commands.passiveCatalog, key = { it.id }) { cmd ->
                CommandRow(cmd, enabled = !scan.running) { onRun(cmd) }
            }
            item { SectionHeader("transmit") }
            items(Commands.transmitCatalog, key = { it.id }) { cmd ->
                CommandRow(cmd, enabled = !scan.running) { pendingTransmit = cmd }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        Rule()
        Breadcrumb("marauder", text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Rule()
    }
}

/**
 * The gate in front of every transmitting mode. Deliberately not a hold-to-fire
 * control — this is a deliberate confirmation with the consequence spelled out,
 * and the 60s auto-stop behind it is what actually bounds a mistake.
 */
@Composable
private fun TransmitConfirmDialog(
    cmd: MarauderCommand,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Ground,
        titleContentColor = Palette.Red,
        textContentColor = Palette.Dim,
        title = { Text("Transmit: ${cmd.label}", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(cmd.description, style = MaterialTheme.typography.bodyMedium, color = Palette.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    "This puts energy in the air and will affect devices around you, " +
                    "not just your own. Only run it on networks you own or have " +
                    "written permission to test. In most places doing otherwise is illegal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Orange,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Stops automatically after 60 seconds.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Dim,
                )
                Text(
                    cmd.command,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.VioletText,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("TRANSMIT", color = Palette.Red, style = MaterialTheme.typography.bodyMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Palette.Dim, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
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
