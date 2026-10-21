package pl.obdskaner.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.obdskaner.bluetooth.BluetoothClassicTransport
import pl.obdskaner.core.ObdError
import pl.obdskaner.core.ObdException
import pl.obdskaner.core.elm.Elm327
import pl.obdskaner.core.obd.DtcKind
import pl.obdskaner.core.obd.ObdClient
import pl.obdskaner.core.obd.Pids
import pl.obdskaner.core.transport.StreamTransport

class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val bluetooth: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(ScannerState())
    val state: StateFlow<ScannerState> = _state.asStateFlow()

    /** Surowa komunikacja z adapterem – bezcenna przy debugowaniu tanich klonów. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var transport: StreamTransport? = null
    private var client: ObdClient? = null
    private var connectJob: Job? = null
    private var liveJob: Job? = null

    /**
     * Odczyt na żywo kończymy flagą, a nie anulowaniem w trakcie komendy –
     * dzięki temu adapter zawsze kończy bieżącą odpowiedź przed następnym zapytaniem.
     */
    private var liveRequested = false

    val hasBluetooth: Boolean get() = bluetooth != null

    // ── Urządzenia ───────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission") // UI wywołuje to dopiero po przyznaniu uprawnienia
    fun refreshDevices() {
        val adapter = bluetooth ?: return
        val enabled = adapter.isEnabled
        val devices = try {
            if (enabled) {
                adapter.bondedDevices.orEmpty()
                    .map { PairedDevice(it.name ?: it.address, it.address) }
                    .sortedWith(
                        compareByDescending<PairedDevice> { it.looksLikeObd }
                            .thenBy { it.name.lowercase() },
                    )
            } else {
                emptyList()
            }
        } catch (_: SecurityException) {
            emptyList()
        }
        _state.update { it.copy(bluetoothEnabled = enabled, devices = devices) }
    }

    // ── Połączenie ───────────────────────────────────────────────────────────────

    fun connect(device: PairedDevice) {
        val adapter = bluetooth ?: return
        if (connectJob?.isActive == true) return
        closeConnection()
        _log.value = emptyList()

        connectJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    connection = ConnectionState.Connecting(device.name, "Łączenie z adapterem"),
                    vehicle = null, live = emptyMap(), monitor = null, dtcs = null,
                )
            }
            try {
                val t = BluetoothClassicTransport(adapter.getRemoteDevice(device.address))
                transport = t
                t.open()

                val c = ObdClient(Elm327(t, ::appendLog))
                val info = c.connect { step ->
                    _state.update { it.copy(connection = ConnectionState.Connecting(device.name, step)) }
                }
                client = c
                _state.update {
                    it.copy(
                        connection = ConnectionState.Connected(device.name),
                        vehicle = info,
                        livePids = Pids.ALL.filter { pid -> pid.code in info.supportedPids },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                closeConnection()
                _state.update { it.copy(connection = ConnectionState.Failed(device.name, e.userMessage())) }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        closeConnection()
        _state.update {
            ScannerState(bluetoothEnabled = it.bluetoothEnabled, devices = it.devices)
        }
    }

    private fun closeConnection() {
        liveRequested = false
        liveJob?.cancel()
        liveJob = null
        client = null
        transport?.close()
        transport = null
    }

    private fun onConnectionLost(e: Throwable) {
        val name = (_state.value.connection as? ConnectionState.Connected)?.deviceName ?: ""
        closeConnection()
        _state.update {
            it.copy(connection = ConnectionState.Failed(name, "Utracono połączenie: ${e.userMessage()}"))
        }
    }

    // ── Dane na żywo ─────────────────────────────────────────────────────────────

    fun startLive() {
        val c = client ?: return
        liveRequested = true
        if (liveJob?.isActive == true) return

        liveJob = viewModelScope.launch {
            var round = 0
            var failures = 0
            while (liveRequested) {
                val pids = _state.value.livePids
                if (pids.isEmpty()) break
                // Pierwsze parametry (obroty, prędkość…) odświeżamy co rundę, resztę co 5.
                val batch = pids.filterIndexed { i, _ -> i < FAST_PID_COUNT || round % 5 == 0 }
                for (pid in batch) {
                    if (!liveRequested) break
                    try {
                        val value = c.read(pid)
                        failures = 0
                        if (value != null) {
                            _state.update { it.copy(live = it.live + (pid.code to value)) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ObdException) {
                        if (e.error == ObdError.DISCONNECTED || ++failures >= MAX_FAILURES) {
                            onConnectionLost(e)
                            return@launch
                        }
                    }
                }
                round++
            }
        }
    }

    fun stopLive() {
        liveRequested = false
    }

    // ── Kody błędów ──────────────────────────────────────────────────────────────

    fun readDtcs() {
        val c = client ?: return
        if (_state.value.dtcLoading) return
        viewModelScope.launch {
            _state.update { it.copy(dtcLoading = true) }
            try {
                val monitor = c.readMonitorStatus()
                val dtcs = DtcKind.entries.associateWith { c.readDtcs(it) }
                _state.update { it.copy(monitor = monitor, dtcs = dtcs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ObdException) {
                if (e.error == ObdError.DISCONNECTED) onConnectionLost(e) else showMessage(e.userMessage())
            } finally {
                _state.update { it.copy(dtcLoading = false) }
            }
        }
    }

    fun clearDtcs() {
        val c = client ?: return
        if (_state.value.dtcLoading) return
        viewModelScope.launch {
            _state.update { it.copy(dtcLoading = true) }
            var cleared = false
            try {
                c.clearDtcs()
                cleared = true
                showMessage("Kody błędów zostały skasowane")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ObdException) {
                if (e.error == ObdError.DISCONNECTED) onConnectionLost(e) else showMessage(e.userMessage())
            } finally {
                _state.update { it.copy(dtcLoading = false) }
            }
            if (cleared) readDtcs()
        }
    }

    // ── Pozostałe ────────────────────────────────────────────────────────────────

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    private fun appendLog(line: String) {
        _log.update { (it + line).takeLast(LOG_LIMIT) }
    }

    override fun onCleared() {
        closeConnection()
    }

    private companion object {
        const val FAST_PID_COUNT = 4
        const val MAX_FAILURES = 3
        const val LOG_LIMIT = 300
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is ObdException -> message ?: error.description
    is SecurityException -> "Brak uprawnienia do Bluetooth"
    else -> message ?: "Nieznany błąd"
}
