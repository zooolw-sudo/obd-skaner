package pl.obdskaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun InfoScreen(state: ScannerState, log: List<String>) {
    val clipboard = LocalClipboardManager.current
    val info = state.vehicle
    val polish = Locale.forLanguageTag("pl-PL")

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { InfoRow("Adapter", info?.adapterVersion?.ifBlank { null } ?: "—") }
        item { InfoRow("Protokół", info?.protocol?.label ?: "—") }
        item { InfoRow("VIN", info?.vin ?: "niedostępny") }
        item {
            InfoRow(
                "Napięcie akumulatora",
                info?.batteryVoltage?.let { String.format(polish, "%.1f V", it) } ?: "—",
            )
        }
        item {
            InfoRow(
                "Obsługiwane PID-y",
                info?.supportedPids?.joinToString(", ") { "%02X".format(it) }?.ifBlank { null } ?: "—",
            )
        }

        item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Dziennik komunikacji",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) {
                    Text("Kopiuj")
                }
            }
        }
        // Najnowsze wpisy na górze.
        items(log.asReversed()) { line ->
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
