package com.kybercypher.marauder.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kybercypher.marauder.protocol.AccessPoint
import com.kybercypher.marauder.ui.Breadcrumb
import com.kybercypher.marauder.ui.Pill
import com.kybercypher.marauder.ui.Rule
import com.kybercypher.marauder.ui.theme.Palette

/**
 * Collected results. Refresh is USER-INITIATED, never automatic.
 *
 * Measured on hardware: injecting a console command every 0.5s while capturing
 * collapsed the capture from 24.28 to 0.30 records/sec — 81x — and after the
 * first 19% of the run the binary output stopped entirely. Console servicing and
 * packet capture share a core. So the app never polls, and it tells you what a
 * manual refresh costs.
 */
@Composable
fun ListsScreen(
    accessPoints: List<AccessPoint>,
    scanRunning: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Breadcrumb("marauder", "results", "access points",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Rule()

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${accessPoints.size} access points",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.White)
                if (scanRunning) {
                    Text("Refreshing mid-scan costs captured packets",
                        style = MaterialTheme.typography.labelSmall, color = Palette.Orange)
                }
            }
            Pill("REFRESH", if (scanRunning) Palette.Orange else Palette.Green) { onRefresh() }
        }
        Rule()

        // Aligned monospace columns are what make this read like an instrument.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("#", Modifier.width(32.dp), color = Palette.Dim,
                style = MaterialTheme.typography.labelSmall)
            Text("CH", Modifier.width(36.dp), color = Palette.Dim,
                style = MaterialTheme.typography.labelSmall)
            Text("SSID", Modifier.weight(1f), color = Palette.Dim,
                style = MaterialTheme.typography.labelSmall)
            Text("RSSI", Modifier.width(48.dp), color = Palette.Dim,
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
        }
        Rule()

        LazyColumn(Modifier.fillMaxSize()) {
            items(accessPoints, key = { it.index }) { ap -> ApRow(ap) }
        }
    }
}

@Composable
private fun ApRow(ap: AccessPoint) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ap.index}", Modifier.width(32.dp), color = Palette.Dim,
            style = MaterialTheme.typography.bodySmall)
        Text("${ap.channel}", Modifier.width(36.dp), color = Palette.Cyan,
            style = MaterialTheme.typography.bodySmall)
        Text(
            ap.essid.ifBlank { "<hidden>" },
            Modifier.weight(1f),
            color = if (ap.selected) Palette.Green else Palette.White,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${ap.rssi}",
            Modifier.width(48.dp),
            color = rssiColor(ap.rssi),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
        )
    }
}

private fun rssiColor(rssi: Int) = when {
    rssi >= -55 -> Palette.Green
    rssi >= -70 -> Palette.Cyan
    rssi >= -82 -> Palette.Orange
    else -> Palette.Dim
}
