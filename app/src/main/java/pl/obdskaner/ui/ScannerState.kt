package pl.obdskaner.ui

import pl.obdskaner.core.obd.Dtc
import pl.obdskaner.core.obd.DtcKind
import pl.obdskaner.core.obd.MonitorStatus
import pl.obdskaner.core.obd.Pid
import pl.obdskaner.core.obd.PidValue
import pl.obdskaner.core.obd.VehicleInfo

data class PairedDevice(val name: String, val address: String) {
    /** Czy nazwa wygląda na adapter OBD – takie pokazujemy na górze listy. */
    val looksLikeObd: Boolean
        get() = OBD_NAME_HINTS.any { name.contains(it, ignoreCase = true) }

    private companion object {
        val OBD_NAME_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK")
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceName: String, val step: String) : ConnectionState
    data class Connected(val deviceName: String) : ConnectionState
    data class Failed(val deviceName: String, val reason: String) : ConnectionState
}

data class ScannerState(
    val bluetoothEnabled: Boolean = true,
    val devices: List<PairedDevice> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val vehicle: VehicleInfo? = null,
    /** Parametry obsługiwane przez auto, w kolejności wyświetlania. */
    val livePids: List<Pid> = emptyList(),
    val live: Map<Int, PidValue> = emptyMap(),
    val monitor: MonitorStatus? = null,
    /** null = jeszcze nie odczytano. */
    val dtcs: Map<DtcKind, List<Dtc>>? = null,
    val dtcLoading: Boolean = false,
    /** Krótki komunikat do pokazania na pasku na dole ekranu. */
    val message: String? = null,
)
