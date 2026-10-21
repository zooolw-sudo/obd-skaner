package pl.obdskaner.core.obd

import pl.obdskaner.core.ObdError
import pl.obdskaner.core.ObdException
import pl.obdskaner.core.elm.Elm327
import pl.obdskaner.core.elm.ObdProtocol

data class VehicleInfo(
    val adapterVersion: String,
    val protocol: ObdProtocol,
    val supportedPids: Set<Int>,
    val vin: String?,
    val batteryVoltage: Double?,
)

/**
 * Wysoki poziom: usługi OBD2 (dane na żywo, błędy, VIN).
 * Korzysta z [Elm327] i niczego nie wie o Bluetoothcie ani o UI.
 */
class ObdClient(val elm: Elm327) {

    var protocol: ObdProtocol = ObdProtocol.UNKNOWN
        private set

    var supportedPids: Set<Int> = emptySet()
        private set

    /**
     * Inicjalizuje adapter i łączy się z autem.
     * Kolejne kroki zgłasza przez [onStep] (np. do paska postępu).
     */
    suspend fun connect(onStep: (String) -> Unit = {}): VehicleInfo {
        onStep("Reset adaptera")
        elm.initialize()

        onStep("Szukanie protokołu auta")
        supportedPids = readSupportedPids()

        onStep("Odczyt informacji o aucie")
        protocol = runCatching { elm.protocol() }.getOrDefault(ObdProtocol.UNKNOWN)
        val voltage = runCatching { elm.batteryVoltage() }.getOrNull()
        val vin = runCatching { readVin() }.getOrNull()

        return VehicleInfo(elm.version, protocol, supportedPids, vin, voltage)
    }

    /** Odczytuje jeden parametr. Zwraca null, gdy auto go nie obsługuje. */
    suspend fun read(pid: Pid): PidValue? {
        val messages = try {
            elm.obd("01%02X".format(pid.code))
        } catch (e: ObdException) {
            if (e.error == ObdError.NO_DATA || e.error == ObdError.NEGATIVE_RESPONSE) return null
            throw e
        }
        val m = messages.firstOrNull { it.size >= 2 + pid.bytes && it[0] == 0x41 && it[1] == pid.code }
            ?: return null
        return PidValue(pid, pid.decode(m.copyOfRange(2, 2 + pid.bytes)))
    }

    /** Stan kontrolki MIL i liczba zapisanych błędów (sumowana ze wszystkich sterowników). */
    suspend fun readMonitorStatus(): MonitorStatus? {
        val messages = try {
            elm.obd("0101")
        } catch (e: ObdException) {
            if (e.error == ObdError.NO_DATA) return null
            throw e
        }
        val relevant = messages.filter { it.size >= 3 && it[0] == 0x41 && it[1] == 0x01 }
        if (relevant.isEmpty()) return null
        return MonitorStatus(
            milOn = relevant.any { (it[2] and 0x80) != 0 },
            dtcCount = relevant.sumOf { it[2] and 0x7F },
        )
    }

    /** Odczytuje kody błędów danego rodzaju. Pusta lista = brak błędów. */
    suspend fun readDtcs(kind: DtcKind): List<Dtc> {
        val messages = try {
            elm.obd(kind.mode, DTC_TIMEOUT_MS)
        } catch (e: ObdException) {
            if (e.error == ObdError.NO_DATA) return emptyList()
            throw e
        }
        val isCan = protocol.takeIf { it != ObdProtocol.UNKNOWN }?.isCan
        return DtcDecoder.parse(messages, kind.responseSid, isCan)
            .map { Dtc(it, DtcDescriptions.describe(it)) }
    }

    /**
     * Kasuje kody błędów i dane zamrożonej ramki (tryb 04).
     * Uwaga: resetuje też monitory gotowości.
     * @throws ObdException np. gdy silnik pracuje (odmowa 0x22)
     */
    suspend fun clearDtcs() {
        val messages = elm.obd("04", DTC_TIMEOUT_MS)
        if (messages.none { it.isNotEmpty() && it[0] == 0x44 }) {
            throw ObdException(ObdError.UNKNOWN, "Sterownik nie potwierdził skasowania błędów")
        }
    }

    /** Numer VIN (tryb 09, PID 02). Starsze auta go nie udostępniają. */
    suspend fun readVin(): String? {
        val messages = try {
            elm.obd("0902")
        } catch (e: ObdException) {
            if (e.error == ObdError.NO_DATA || e.error == ObdError.NEGATIVE_RESPONSE) return null
            throw e
        }
        val vinMessages = messages.filter { it.size >= 3 && it[0] == 0x49 && it[1] == 0x02 }
        if (vinMessages.isEmpty()) return null

        // CAN: jedna złożona wiadomość „49 02 01” + 17 znaków.
        // K-line / J1850: 5 wiadomości „49 02 <nr>” + 4 bajty każda.
        val bytes = if (vinMessages.size == 1) {
            vinMessages[0].drop(3)
        } else {
            vinMessages.sortedBy { it[2] }.flatMap { it.drop(3) }
        }
        val vin = bytes
            .filter { it in 0x30..0x39 || it in 0x41..0x5A }
            .map { it.toChar() }
            .joinToString("")
        return vin.takeLast(17).takeIf { it.length == 17 }
    }

    /**
     * PID-y 00, 20, 40… zwracają maskę bitową obsługiwanych parametrów.
     * Najstarszy bit pierwszego bajtu odpowiada PID-owi (baza + 1).
     */
    private suspend fun readSupportedPids(): Set<Int> {
        val result = sortedSetOf<Int>()
        var base = 0x00
        var timeout = Elm327.PROTOCOL_SEARCH_TIMEOUT_MS
        while (base <= 0xC0) {
            val messages = try {
                elm.obd("01%02X".format(base), timeout)
            } catch (e: ObdException) {
                if (base == 0x00) throw e else break
            }
            timeout = Elm327.OBD_TIMEOUT_MS

            var found = false
            for (m in messages) {
                if (m.size < 6 || m[0] != 0x41 || m[1] != base) continue
                found = true
                for (i in 0 until 32) {
                    val byte = m[2 + i / 8]
                    val bit = 7 - (i % 8)
                    if ((byte shr bit) and 1 == 1) result += base + i + 1
                }
            }
            if (!found) {
                if (base == 0x00) throw ObdException(ObdError.NO_DATA, "Auto nie odpowiedziało na zapytanie 0100")
                break
            }
            if ((base + 0x20) !in result) break
            base += 0x20
        }
        return result
    }

    companion object {
        const val DTC_TIMEOUT_MS = 8_000L
    }
}
