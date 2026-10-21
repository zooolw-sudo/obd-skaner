package pl.obdskaner.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    state: ScannerState,
    hasBluetooth: Boolean,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onConnect: (PairedDevice) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var granted by remember {
        mutableStateOf(
            !needsPermission ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onRefresh() }

    // Odświeżamy listę po każdym powrocie do aplikacji (np. z ustawień Bluetooth po sparowaniu).
    LifecycleResumeEffect(granted) {
        if (granted) onRefresh()
        onPauseOrDispose { }
    }

    val connecting = state.connection as? ConnectionState.Connecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OBD Skaner") },
                actions = {
                    if (granted && state.bluetoothEnabled) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Odśwież")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !hasBluetooth -> CenteredMessage("Ten telefon nie ma Bluetooth.")

                !granted -> CenteredMessage(
                    text = "Aplikacja potrzebuje dostępu do urządzeń Bluetooth w pobliżu, " +
                        "żeby połączyć się z adapterem OBD.",
                    action = "Zezwól",
                ) { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }

                !state.bluetoothEnabled -> CenteredMessage(
                    text = "Bluetooth jest wyłączony.",
                    action = "Włącz Bluetooth",
                ) { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }

                else -> DeviceList(
                    state = state,
                    enabled = connecting == null,
                    onConnect = onConnect,
                    onOpenSettings = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                )
            }

            if (connecting != null) ConnectingOverlay(connecting, onCancel)
        }
    }
}

@Composable
private fun DeviceList(
    state: ScannerState,
    enabled: Boolean,
    onConnect: (PairedDevice) -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val failed = state.connection as? ConnectionState.Failed
        if (failed != null) {
            item { ErrorCard(failed) }
        }

        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text("Sparowane urządzenia", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Podłącz adapter do gniazda OBD, włącz zapłon i sparuj adapter " +
                        "w ustawieniach Bluetooth (PIN to zwykle 1234 lub 0000).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.devices.isEmpty()) {
            item {
                Text(
                    "Brak sparowanych urządzeń.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        items(state.devices, key = { it.address }) { device ->
            OutlinedCard(
                onClick = { onConnect(device) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = {
                        Text(
                            if (device.looksLikeObd) "Wygląda na adapter OBD · ${device.address}"
                            else device.address,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        item {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Ustawienia Bluetooth")
            }
        }
    }
}

@Composable
private fun ErrorCard(failed: ConnectionState.Failed) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (failed.deviceName.isNotBlank()) "Problem z połączeniem: ${failed.deviceName}"
                else "Problem z połączeniem",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(failed.reason, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectingOverlay(connecting: ConnectionState.Connecting, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            // Blokuje dotyk pod spodem.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(32.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "Łączenie z ${connecting.deviceName}",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    connecting.step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel) { Text("Anuluj") }
            }
        }
    }
}

@Composable
internal fun CenteredMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}
