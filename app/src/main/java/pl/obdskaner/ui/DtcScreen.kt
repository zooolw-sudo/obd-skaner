package pl.obdskaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.obdskaner.core.gmlan.GmlanCompatibility
import pl.obdskaner.core.obd.Dtc
import pl.obdskaner.core.obd.DtcKind
import pl.obdskaner.core.obd.MonitorStatus

@Composable
fun DtcScreen(
    state: ScannerState,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onScanGmlan: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmGmlan by remember { mutableStateOf(false) }
    val busy = state.dtcLoading || state.gmlan.running
    val compatibility = state.vehicle?.let { GmlanCompatibility.of(it.protocol, it.vin) }

    // Przy pierwszym wejściu odczytujemy błędy automatycznie.
    LaunchedEffect(Unit) {
        if (state.dtcs == null) onRead()
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MilCard(state.monitor, loading = state.dtcLoading, read = state.dtcs != null) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onRead,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Odczytaj")
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !busy && state.dtcs != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Skasuj")
                }
            }
        }

        val dtcs = state.dtcs
        if (dtcs != null) {
            DtcKind.entries.forEach { kind ->
                val list = dtcs[kind].orEmpty()
                item(key = kind.name) {
                    Text(
                        "${kind.label} (${list.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (list.isEmpty()) {
                    item(key = "${kind.name}-empty") {
                        Text(
                            "Brak",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(list, key = { "${kind.name}-${it.code}" }) { DtcRow(it) }
                }
            }
        }

        gmlanSection(
            gmlan = state.gmlan,
            compatibility = compatibility,
            busy = busy,
            onScan = {
                if (compatibility == GmlanCompatibility.SUPPORTED) onScanGmlan() else confirmGmlan = true
            },
        )
    }

    if (confirmGmlan) {
        GmlanConfirmDialog(
            onConfirm = {
                confirmGmlan = false
                onScanGmlan()
            },
            onDismiss = { confirmGmlan = false },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Skasować kody błędów?") },
            text = {
                Text(
                    "Skasowanie nie naprawia usterki – jeśli nadal występuje, kod wróci.\n\n" +
                        "Zresetują się też monitory gotowości. Dopóki auto nie przejedzie pełnego " +
                        "cyklu jazdy, kontrola emisji przez OBD może wykazać brak gotowości.\n\n" +
                        "Najlepiej kasować przy włączonym zapłonie i wyłączonym silniku.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Skasuj") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Anuluj") }
            },
        )
    }
}

@Composable
private fun MilCard(monitor: MonitorStatus?, loading: Boolean, read: Boolean) {
    val milOn = monitor?.milOn == true
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (milOn) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (milOn) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
            }
            Column {
                val title = when {
                    loading -> "Odczytywanie…"
                    monitor == null -> if (read) "Brak danych o kontrolce" else "Nie odczytano"
                    milOn -> "Kontrolka silnika włączona"
                    else -> "Kontrolka silnika wyłączona"
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (monitor != null && !loading) {
                    Spacer(Modifier.height(2.dp))
                    Text("Zapisane błędy według sterownika: ${monitor.dtcCount}")
                }
            }
        }
    }
}

@Composable
private fun DtcRow(dtc: Dtc) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(dtc.code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            },
            supportingContent = { Text(dtc.description) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
