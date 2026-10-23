package pl.obdskaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.obdskaner.core.gmlan.GmlanCompatibility
import pl.obdskaner.core.gmlan.GmlanDtc
import pl.obdskaner.core.gmlan.GmlanModule

/** Sekcja „Pozostałe sterowniki” na ekranie błędów. */
fun LazyListScope.gmlanSection(
    gmlan: GmlanState,
    compatibility: GmlanCompatibility?,
    busy: Boolean,
    onScan: () -> Unit,
) {
    item(key = "gmlan-header") {
        Column(Modifier.padding(top = 16.dp)) {
            HorizontalDivider(Modifier.padding(bottom = 16.dp))
            Text("Pozostałe sterowniki (Saab / Opel)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Błędy z ABS/ESP, skrzyni biegów i innych sterowników na magistrali P-bus. " +
                    "Moduły nadwozia z magistrali I-bus (BCM, drzwi) wymagają adaptera " +
                    "z obsługą jednoprzewodowej CAN (SW-CAN).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val blocker = when (compatibility) {
        GmlanCompatibility.WRONG_PROTOCOL ->
            "Niedostępne: to auto nie komunikuje się przez CAN 500 kbit/s."
        GmlanCompatibility.OTHER_MAKE ->
            "Niedostępne: VIN wskazuje inną markę niż Saab lub Opel. Skan wysyła zapytania " +
                "na adresy diagnostyczne GM, które w innych autach mogą mieć inne znaczenie."
        else -> null
    }
    if (blocker != null) {
        item(key = "gmlan-blocked") {
            Text(blocker, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        return
    }

    item(key = "gmlan-button") {
        FilledTonalButton(
            onClick = onScan,
            enabled = !busy && !gmlan.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (gmlan.modules == null) "Skanuj sterowniki" else "Skanuj ponownie")
        }
    }

    if (gmlan.running) {
        item(key = "gmlan-progress") {
            Column {
                LinearProgressIndicator(progress = { gmlan.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    gmlan.step.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    gmlan.error?.let { error ->
        item(key = "gmlan-error") {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }

    val modules = gmlan.modules ?: return
    if (modules.isEmpty()) {
        item(key = "gmlan-empty") {
            Text(
                "Żaden sterownik nie odpowiedział. Skopiuj dziennik z zakładki Info – " +
                    "pokaże, co dokładnie zwrócił adapter.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    modules.forEach { module ->
        item(key = "gmlan-${module.address.request}") { ModuleCard(module) }
    }
}

@Composable
private fun ModuleCard(module: GmlanModule) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    module.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    module.address.label,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            when {
                module.error != null -> Text(
                    module.error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                module.dtcs.isEmpty() && module.complete -> Text(
                    "Brak błędów",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> module.dtcs.forEach { GmlanDtcRow(it) }
            }
            if (!module.complete && module.error == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Lista może być niepełna – sterownik nie potwierdził jej końca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GmlanDtcRow(dtc: GmlanDtc) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(dtc.fullCode, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(dtc.description, style = MaterialTheme.typography.bodyMedium)
        val flags = listOfNotNull(
            "występuje teraz".takeIf { dtc.isCurrent },
            "w historii".takeIf { dtc.isHistory },
            "zapala kontrolkę".takeIf { dtc.warningLamp },
        ).ifEmpty { listOf("status 0x%02X".format(dtc.status)) }
        Text(
            flags.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = if (dtc.isCurrent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ostrzeżenie, gdy nie udało się odczytać VIN i nie wiadomo, czy to Saab/Opel. */
@Composable
fun GmlanConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skanować sterowniki?") },
        text = {
            Text(
                "Nie udało się odczytać numeru VIN, więc aplikacja nie wie, jaka to marka.\n\n" +
                    "Skan jest przeznaczony dla Saaba 9-3 (od 2003) i Opli z siecią GMLAN. " +
                    "Uruchamiaj go tylko w takim aucie – w innych markach te same adresy " +
                    "mogą znaczyć coś innego.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("To Saab / Opel – skanuj") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
