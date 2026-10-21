package pl.obdskaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import pl.obdskaner.core.obd.Pid
import pl.obdskaner.core.obd.PidValue

@Composable
fun LiveScreen(state: ScannerState, onStart: () -> Unit, onStop: () -> Unit) {
    // Odpytujemy auto tylko wtedy, gdy ten ekran jest widoczny.
    LifecycleResumeEffect(Unit) {
        onStart()
        onPauseOrDispose { onStop() }
    }

    if (state.livePids.isEmpty()) {
        CenteredMessage("Auto nie udostępnia żadnego z obsługiwanych parametrów.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.livePids, key = { it.code }) { pid ->
            GaugeCard(pid, state.live[pid.code])
        }
    }
}

@Composable
private fun GaugeCard(pid: Pid, value: PidValue?) {
    ElevatedCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                pid.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value?.formatted ?: "—",
                    // Cyfry o stałej szerokości – liczby nie „skaczą” przy zmianie wartości.
                    style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    pid.unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { value?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
