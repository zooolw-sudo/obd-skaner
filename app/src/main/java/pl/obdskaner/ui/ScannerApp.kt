package pl.obdskaner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ScannerApp(vm: ScannerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // W aucie ekran nie powinien gasnąć, dopóki jesteśmy połączeni.
    val connected = state.connection is ConnectionState.Connected
    val view = LocalView.current
    DisposableEffect(connected) {
        view.keepScreenOn = connected
        onDispose { view.keepScreenOn = false }
    }

    if (connected) {
        ConnectedScreen(state, log, snackbar, vm)
    } else {
        DevicesScreen(
            state = state,
            hasBluetooth = vm.hasBluetooth,
            snackbar = snackbar,
            onRefresh = vm::refreshDevices,
            onConnect = vm::connect,
            onCancel = vm::disconnect,
        )
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Na żywo", Icons.Filled.PlayArrow),
    DTC("Błędy", Icons.Filled.Warning),
    INFO("Info", Icons.Filled.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedScreen(
    state: ScannerState,
    log: List<String>,
    snackbar: SnackbarHostState,
    vm: ScannerViewModel,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[tabIndex]
    val deviceName = (state.connection as? ConnectionState.Connected)?.deviceName.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OBD Skaner")
                        Text(
                            listOfNotNull(deviceName, state.vehicle?.protocol?.label).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::disconnect) {
                        Icon(Icons.Filled.Close, contentDescription = "Rozłącz")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = i == tabIndex,
                        onClick = { tabIndex = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tab) {
                Tab.LIVE -> LiveScreen(state, onStart = vm::startLive, onStop = vm::stopLive)
                Tab.DTC -> DtcScreen(state, onRead = vm::readDtcs, onClear = vm::clearDtcs)
                Tab.INFO -> InfoScreen(state, log)
            }
        }
    }
}
