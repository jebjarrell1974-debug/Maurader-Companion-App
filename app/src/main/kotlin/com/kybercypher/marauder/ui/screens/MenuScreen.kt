package com.kybercypher.marauder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kybercypher.marauder.device.*
import com.kybercypher.marauder.ui.Breadcrumb
import com.kybercypher.marauder.ui.GlowDot
import com.kybercypher.marauder.ui.Pill
import com.kybercypher.marauder.ui.Rule
import com.kybercypher.marauder.ui.theme.Palette
import com.kybercypher.marauder.ui.theme.color
import com.kybercypher.marauder.ui.theme.label

/**
 * The full command surface, organised the way the device organises it.
 *
 * Three gates stand in front of anything that transmits: an explicit
 * confirmation naming the consequence, red chrome for as long as the radio
 * emits, and a hard 60-second auto-stop. The auto-stop is the substantive one —
 * the firmware runs until stopscan, so without it a forgotten tab keeps
 * transmitting from a pocket.
 */
@Composable
fun MenuScreen(
    scan: ScanState,
    busy: String?,
    streamEnabled: Boolean,
    onToggleStream: (Boolean) -> Unit,
    onRun: (MarauderCommand, Map<String, String>) -> Unit,
    onStop: () -> Unit,
    onExtend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingParams by remember { mutableStateOf<MarauderCommand?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<MarauderCommand, Map<String, String>>?>(null) }
    var category by remember { mutableStateOf(Category.WIFI_SCAN) }

    fun launch(cmd: MarauderCommand, values: Map<String, String>) {
        if (cmd.transmits) pendingConfirm = cmd to values else onRun(cmd, values)
    }

    pendingParams?.let { cmd ->
        ParamDialog(
            cmd = cmd,
            onDismiss = { pendingParams = null },
            onSubmit = { values -> pendingParams = null; launch(cmd, values) },
        )
    }

    pendingConfirm?.let { (cmd, values) ->
        TransmitConfirmDialog(
            cmd = cmd,
            rendered = cmd.render(values),
            onDismiss = { pendingConfirm = null },
            onConfirm = { pendingConfirm = null; onRun(cmd, values) },
        )
    }

    Column(modifier.fillMaxSize()) {

        // Working indicator. Starting a scan is several serial round trips, so
        // naming the step beats a bare spinner — it says WHICH part is slow.
        if (busy != null) {
            Row(
                Modifier.fillMaxWidth().background(Palette.VioletDim.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlowDot(Palette.Violet, pulsing = true, size = 7)
                Spacer(Modifier.width(6.dp))
                Text(busy, style = MaterialTheme.typography.labelSmall, color = Palette.White)
            }
        }

        if (scan.running) RunningBar(scan, onStop, onExtend)

        StreamToggle(streamEnabled, onToggleStream)
        Rule()
        CategoryTabs(category) { category = it }
        Rule()

        LazyColumn(Modifier.fillMaxSize()) {
            items(Commands.byCategory(category), key = { it.id }) { cmd ->
                CommandRow(cmd, enabled = busy == null && (!scan.running || cmd.gating == Gating.ALWAYS)) {
                    if (cmd.needsInput) pendingParams = cmd else launch(cmd, emptyMap())
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RunningBar(scan: ScanState, onStop: () -> Unit, onExtend: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(if (scan.transmitting) Palette.Red.copy(alpha = 0.12f) else Palette.Ground)
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
                    Text("auto-stop in ${it}s",
                        style = MaterialTheme.typography.labelSmall, color = Palette.Dim)
                }
            }
            if (scan.transmitting) {
                Pill("+60s", Palette.Orange) { onExtend() }
                Spacer(Modifier.width(8.dp))
            }
            Pill("STOP", Palette.Orange) { onStop() }
        }
        Rule()
    }
}

@Composable
private fun StreamToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (enabled) "STREAM PACKETS TO PHONE" else "SD ONLY (recommended)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Palette.Orange else Palette.Green,
            )
            Text(
                if (enabled)
                    "Adds -serial. Capped near 7.7 KB/s and lossy above ~40 pkt/s; also stalls the board's loop."
                else
                    "Board records a complete capture to SD. Phone still gets live telemetry.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.Dim,
            )
        }
        Spacer(Modifier.width(10.dp))
        Pill(if (enabled) "ON" else "OFF", if (enabled) Palette.Orange else Palette.Dim) {
            onToggle(!enabled)
        }
    }
}

@Composable
private fun CategoryTabs(selected: Category, onSelect: (Category) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Category.entries.forEach { c ->
            Box(
                Modifier.padding(end = 6.dp).clickable { onSelect(c) }
                    .background(
                        if (c == selected) Palette.VioletDim.copy(alpha = 0.55f) else Palette.Ground,
                        RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    c.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c == selected) Palette.White else Palette.Dim,
                )
            }
        }
    }
}

@Composable
private fun CommandRow(cmd: MarauderCommand, enabled: Boolean, onClick: () -> Unit) {
    val accent = if (cmd.transmits) Palette.Red else cmd.scanClass.color()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(if (cmd.description.isBlank()) 20.dp else 30.dp)
            .background(if (enabled) accent else Palette.VioletDim, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                cmd.label + if (cmd.needsInput) " …" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) Palette.White else Palette.Dim,
            )
            Text(cmd.template, style = MaterialTheme.typography.labelSmall, color = Palette.Dim)
            if (cmd.description.isNotBlank()) {
                Text(cmd.description, style = MaterialTheme.typography.labelSmall,
                    color = Palette.Dim.copy(alpha = 0.8f))
            }
        }
        Text(
            if (cmd.transmits) "TX" else cmd.scanClass.label(),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) accent else Palette.VioletDim,
        )
    }
}

/** Collects a command's arguments before it is sent. */
@Composable
private fun ParamDialog(
    cmd: MarauderCommand,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, String>) -> Unit,
) {
    val values = remember(cmd.id) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Ground,
        titleContentColor = Palette.Violet,
        textContentColor = Palette.Dim,
        title = { Text(cmd.label, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                cmd.params.forEach { p ->
                    when (p) {
                        is Param.Choice -> {
                            Text(p.label, style = MaterialTheme.typography.labelSmall, color = Palette.Dim)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                                p.options.forEach { opt ->
                                    val on = values[p.key] == opt
                                    Box(Modifier.padding(end = 6.dp)
                                        .clickable { values[p.key] = opt }
                                        .background(
                                            if (on) Palette.Violet.copy(alpha = 0.3f) else Palette.Ground,
                                            RoundedCornerShape(4.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text(opt, style = MaterialTheme.typography.labelSmall,
                                            color = if (on) Palette.White else Palette.Dim)
                                    }
                                }
                            }
                        }
                        is Param.Index, is Param.Number, is Param.Text -> {
                            val numeric = p is Param.Index || p is Param.Number
                            OutlinedTextField(
                                value = values[p.key].orEmpty(),
                                onValueChange = { values[p.key] = it },
                                label = { Text(p.label, style = MaterialTheme.typography.labelSmall) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
                                ),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(cmd.render(values), style = MaterialTheme.typography.labelSmall,
                    color = Palette.Green)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(values.toMap()) }) {
                Text("RUN", color = Palette.Green, style = MaterialTheme.typography.bodyMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Palette.Dim, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}

/** The gate in front of every transmitting mode. */
@Composable
private fun TransmitConfirmDialog(
    cmd: MarauderCommand,
    rendered: String,
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
                if (cmd.description.isNotBlank()) {
                    Text(cmd.description, style = MaterialTheme.typography.bodyMedium,
                        color = Palette.White)
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    "This puts energy in the air and affects devices around you, not just " +
                    "your own. Only run it on networks you own or have written permission " +
                    "to test. In most places doing otherwise is illegal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Orange,
                )
                Spacer(Modifier.height(12.dp))
                Text("Stops automatically after 60 seconds.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Dim)
                Text(rendered, style = MaterialTheme.typography.labelSmall, color = Palette.VioletText)
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
