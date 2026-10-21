package com.kybercypher.marauder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kybercypher.marauder.capture.CaptureService
import com.kybercypher.marauder.transport.ConnectionState
import com.kybercypher.marauder.ui.Pill
import com.kybercypher.marauder.ui.Rule
import com.kybercypher.marauder.ui.screens.*
import com.kybercypher.marauder.ui.theme.MarauderTheme
import com.kybercypher.marauder.ui.theme.Palette

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /**
     * Detach must be registered at RUNTIME, not in the manifest — detach events
     * only go to the running app. Teardown is idempotent because both this and
     * SerialInputOutputManager.onRunError can fire for a single yank.
     */
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                vm.transport.disconnect()
                CaptureService.stop(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mandatory at targetSdk 36 — the opt-out is deprecated and disabled.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ContextCompat.registerReceiver(
            this, detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent { MarauderTheme { AppRoot(vm) } }

        // Launched by USB_DEVICE_ATTACHED, so we are user-visible and may both
        // request permission and legally start the foreground service.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.connect()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(detachReceiver) }
        super.onDestroy()
    }
}

private enum class Tab(val label: String) { SCAN("scan"), RESULTS("results"), CONSOLE("console") }

@Composable
private fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    val connection by vm.transport.state.collectAsStateWithLifecycle()
    val scan by vm.session.scan.collectAsStateWithLifecycle()
    val aps by vm.session.accessPoints.collectAsStateWithLifecycle()
    val info by vm.session.info.collectAsStateWithLifecycle()
    val stream by vm.streamEnabled.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val lines = vm.session.console.value

    var tab by remember { mutableStateOf(Tab.SCAN) }
    val connected = connection is ConnectionState.Connected

    LaunchedEffect(connected, scan.running) {
        if (connected) CaptureService.start(ctx, if (scan.running) scan.label else "connected")
        else CaptureService.stop(ctx)
    }

    Scaffold(
        containerColor = Palette.Ground,
        topBar = {
            Column {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                StatusStrip(
                    connected = connected,
                    scan = scan,
                    channel = null,
                    firmware = info?.firmware,
                )
                Rule()
            }
        },
        bottomBar = {
            Column {
                Rule()
                Row(Modifier.fillMaxWidth()) {
                    Tab.entries.forEach { t ->
                        Box(
                            Modifier.weight(1f).clickable { tab = t }
                                .background(if (tab == t) Palette.VioletDim.copy(alpha = 0.4f) else Palette.Ground)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                t.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tab == t) Palette.White else Palette.Dim,
                            )
                        }
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            notice?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().background(Palette.Orange.copy(alpha = 0.12f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, color = Palette.Orange)
                    Pill("OK", Palette.Orange) { vm.dismissNotice() }
                }
            }

            if (!connected) {
                ConnectPanel(connection) { vm.connect() }
            } else when (tab) {
                Tab.SCAN -> MenuScreen(
                    scan = scan,
                    streamEnabled = stream,
                    onToggleStream = vm::setStream,
                    onRun = vm::run,
                    onStop = vm::stop,
                    onExtend = vm::extendTransmit,
                )
                Tab.RESULTS -> ListsScreen(
                    accessPoints = aps,
                    scanRunning = scan.running,
                    onRefresh = vm::refreshAccessPoints,
                )
                Tab.CONSOLE -> ConsoleScreen(lines = lines)
            }
        }
    }
}

@Composable
private fun ConnectPanel(state: ConnectionState, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("KYBER//CYPHER", style = MaterialTheme.typography.titleLarge, color = Palette.Violet)
        Spacer(Modifier.height(4.dp))
        Text("marauder control", style = MaterialTheme.typography.labelSmall, color = Palette.Dim)
        Spacer(Modifier.height(28.dp))
        Text(
            when (state) {
                is ConnectionState.NoDevice -> "No Marauder found. Connect it by USB-OTG."
                is ConnectionState.Failed -> state.reason
                is ConnectionState.NeedsPermission -> "Waiting for USB permission…"
                is ConnectionState.Connecting -> "Connecting…"
                else -> "Not connected"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is ConnectionState.Failed) Palette.Orange else Palette.Dim,
        )
        Spacer(Modifier.height(20.dp))
        Pill("CONNECT", Palette.Green) { onConnect() }
    }
}
