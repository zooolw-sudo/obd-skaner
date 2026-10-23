package pl.obdskaner.core.gmlan

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.obdskaner.core.ObdError
import pl.obdskaner.core.ObdException
import pl.obdskaner.core.describeNegativeResponse
import pl.obdskaner.core.elm.Elm327
import pl.obdskaner.core.elm.ObdProtocol
import pl.obdskaner.core.elm.ResponseParser
import pl.obdskaner.core.obd.DtcDecoder

/** Surowa ramka CAN odebrana przez ELM327 (przy ATH1 i ATCAF0). */
internal class CanFrame(val id: Int, val data: IntArray) {
    operator fun get(i: Int): Int = data.getOrElse(i) { -1 }
    override fun toString() = "%03X ".format(id) + data.joinToString("") { "%02X".format(it) }
}

/**
 * Wyszukuje sterowniki na magistrali P-bus i odczytuje z nich kody błędów
 * usługami GMLAN (specyfikacja GM: GMW3110). Wyłącznie odczyt – nic nie zapisuje.
 *
 * Saab 9-3 (2003+) ma dwie magistrale: P-bus (CAN 500 kbit/s na pinach 6 i 14: silnik,
 * skrzynia, ABS/ESP, moduł kolumny kierownicy CIM, zestaw wskaźników MIU) oraz I-bus
 * (jednoprzewodowa CAN 33 kbit/s: BCM, drzwi, tył nadwozia). ELM327 ma tylko
 * dwuprzewodowy CAN, więc widzi wyłącznie P-bus.
 *
 * Na czas skanu adapter przechodzi w tryb surowych ramek (ATH1 + ATCAF0), bo lista
 * błędów przychodzi w ramkach UUDT, których ELM327 nie umie złożyć sam. Po skanie
 * adapter jest resetowany i wraca do zwykłej pracy z OBD2.
 */
class GmlanScanner(
    private val elm: Elm327,
    private val protocol: ObdProtocol,
) {
    private var header = -1
    private var filter: Pair<Int, Int>? = null
    private var timeoutCode = -1

    /**
     * @param candidates adresy zapytań do sprawdzenia
     * @return znalezione sterowniki z błędami (pusta lista = nikt nie odpowiedział)
     */
    suspend fun scan(
        candidates: List<Int> = DEFAULT_CANDIDATES,
        onProgress: (GmlanProgress) -> Unit = {},
    ): List<GmlanModule> {
        if (protocol != ObdProtocol.CAN_11_500) {
            throw ObdException(ObdError.UNKNOWN, "Skan sterowników wymaga magistrali CAN 500 kbit/s (11 bit)")
        }
        enterRawMode()
        try {
            val addresses = candidates.map { GmlanAddress.forRequest(it) }
            val found = mutableListOf<GmlanAddress>()
            addresses.forEachIndexed { i, address ->
                onProgress(GmlanProgress.Probing(i, addresses.size, address))
                if (probe(address)) found += address
            }
            return found.mapIndexed { i, address ->
                val module = GmlanModule(address, gmlanModuleName(address.request))
                onProgress(GmlanProgress.Reading(i, found.size, module))
                readDtcs(module)
            }
        } finally {
            withContext(NonCancellable) { restore() }
        }
    }

    // ── Wyszukiwanie ─────────────────────────────────────────────────────────────

    /** TesterPresent ($3E) – każdy sterownik GMLAN musi na nie odpowiedzieć. */
    private suspend fun probe(address: GmlanAddress): Boolean {
        // Jeden filtr na całą grupę adresów: mniej komend, szybszy skan.
        val group = if (address.request in 0x7E0..0x7E7) PROBE_GROUP_OBD else PROBE_GROUP_GMLAN
        setFilter(group)
        setTimeout(PROBE_TIMEOUT_CODE)
        setHeader(address.request)
        val frames = send(intArrayOf(0x01, SID_TESTER_PRESENT), PROBE_WALL_TIMEOUT_MS)
        return frames.any { f ->
            f.id == address.response &&
                (f[1] == SID_TESTER_PRESENT + 0x40 || (f[1] == 0x7F && f[2] == SID_TESTER_PRESENT))
        }
    }

    // ── Kody błędów ──────────────────────────────────────────────────────────────

    /**
     * ReadDiagnosticInformation ($A9), poziom $81: błędy pasujące do maski statusu.
     * Maska $12 = bieżące (bit 1) + historyczne (bit 4). Maska $FF zwróciłaby wszystkie
     * kody, które sterownik w ogóle obsługuje – setki pozycji.
     *
     * Odpowiedź: opcjonalnie „7F A9 78” (odpowiedź w toku) na adresie USDT, potem
     * po jednej ramce UUDT na błąd: [81, kod H, kod L, typ usterki, status],
     * zakończone ramką z kodem 00 00 00.
     */
    private suspend fun readDtcs(module: GmlanModule): GmlanModule {
        val a = module.address
        setFilter(filterFor(listOf(a.response, a.uudt)))
        setTimeout(DTC_TIMEOUT_CODE)
        setHeader(a.request)

        repeat(BUSY_RETRIES + 1) { attempt ->
            val frames = send(
                intArrayOf(0x03, SID_READ_DIAGNOSTIC_INFORMATION, LEVEL_BY_STATUS_MASK, STATUS_MASK),
                DTC_WALL_TIMEOUT_MS,
            )
            val negative = frames.firstOrNull {
                it.id == a.response && it[1] == 0x7F && it[2] == SID_READ_DIAGNOSTIC_INFORMATION && it[3] != NRC_PENDING
            }
            if (negative != null) {
                if (negative[3] == NRC_BUSY && attempt < BUSY_RETRIES) {
                    delay(BUSY_DELAY_MS)
                    return@repeat
                }
                return module.copy(error = describeNegativeResponse(negative[3]))
            }

            val dtcs = LinkedHashMap<String, GmlanDtc>()
            var complete = false
            for (f in frames) {
                if (f.id != a.uudt || f.data.size < 5) continue
                if (f[1] == 0 && f[2] == 0 && f[3] == 0) {
                    complete = true
                    break
                }
                val dtc = GmlanDtc(DtcDecoder.decode(f[1], f[2]), f[3], f[4])
                dtcs[dtc.fullCode] = dtc
            }
            if (!complete && dtcs.isEmpty() && frames.none { it.id == a.response }) {
                return module.copy(complete = false, error = "Sterownik nie odpowiedział na zapytanie o błędy")
            }
            return module.copy(dtcs = dtcs.values.toList(), complete = complete)
        }
        return module.copy(error = describeNegativeResponse(NRC_BUSY))
    }

    // ── Adapter ──────────────────────────────────────────────────────────────────

    private suspend fun enterRawMode() {
        elm.at("ATH1")                  // pokazuj identyfikatory ramek
        elm.at("ATCAF0")                // bez automatycznego formatowania – ramki wysyłamy i czytamy w całości
        elm.at("ATCFC0", optional = true) // bez automatycznej kontroli przepływu ISO-TP
        elm.at("ATV1", optional = true) // DLC zgodne z liczbą wysłanych bajtów
        elm.at("ATAT0", optional = true)// stały czas oczekiwania (ATST), bez skracania
    }

    /** Pełny reset adaptera i powrót do zwykłego OBD2 na znanym protokole. */
    private suspend fun restore() {
        runCatching {
            elm.initialize()
            elm.at("ATSP${protocol.code}", optional = true)
        }
        header = -1
        filter = null
        timeoutCode = -1
    }

    private suspend fun setHeader(id: Int) {
        if (header == id) return
        elm.at("ATSH%03X".format(id))
        header = id
    }

    private suspend fun setFilter(f: Pair<Int, Int>) {
        if (filter == f) return
        elm.at("ATCF%03X".format(f.first))
        elm.at("ATCM%03X".format(f.second))
        filter = f
    }

    private suspend fun setTimeout(code: Int) {
        if (timeoutCode == code) return
        elm.at("ATST%02X".format(code))
        timeoutCode = code
    }

    /** Wysyła ramkę (dopełnioną zerami do 8 bajtów) i zwraca odebrane ramki. */
    private suspend fun send(payload: IntArray, wallTimeoutMs: Long): List<CanFrame> {
        val bytes = payload + IntArray(8 - payload.size)
        val command = bytes.joinToString("") { "%02X".format(it) }
        val lines = ResponseParser.cleanLines(elm.sendRaw(command, wallTimeoutMs), command)

        val frames = mutableListOf<CanFrame>()
        for (line in lines) {
            val frame = parseFrame(line)
            if (frame != null) {
                frames += frame
                continue
            }
            when (val error = ResponseParser.errorOf(line)) {
                null, ObdError.NO_DATA, ObdError.BUFFER_FULL, ObdError.STOPPED -> Unit
                else -> throw ObdException(error)
            }
        }
        return frames
    }

    internal companion object {
        /** Adresy sprawdzane w skanie: zakres GMLAN 0x241–0x25F oraz OBD 0x7E0–0x7E7. */
        val DEFAULT_CANDIDATES: List<Int> = (0x241..0x25F).toList() + (0x7E0..0x7E7).toList()

        const val SID_TESTER_PRESENT = 0x3E
        const val SID_READ_DIAGNOSTIC_INFORMATION = 0xA9
        const val LEVEL_BY_STATUS_MASK = 0x81
        const val STATUS_MASK = 0x12
        const val NRC_PENDING = 0x78
        const val NRC_BUSY = 0x21

        /** ATST w jednostkach 4 ms: 0x32 = 200 ms na odpowiedź, 0x7D = 500 ms między ramkami błędów. */
        const val PROBE_TIMEOUT_CODE = 0x32
        const val DTC_TIMEOUT_CODE = 0x7D
        const val PROBE_WALL_TIMEOUT_MS = 2_000L
        const val DTC_WALL_TIMEOUT_MS = 15_000L
        const val BUSY_RETRIES = 3
        const val BUSY_DELAY_MS = 100L

        val PROBE_GROUP_GMLAN = filterFor((0x641..0x65F).toList())
        val PROBE_GROUP_OBD = filterFor((0x7E8..0x7EF).toList())

        private val FRAME = Regex("^([0-9A-F]{3})((?:[0-9A-F]{2}){1,8})$")

        /**
         * Para (filtr, maska) dla ATCF/ATCM, która przepuszcza wszystkie podane
         * identyfikatory (i jak najmniej innych). Ramka przechodzi, gdy (id & maska) == filtr.
         */
        fun filterFor(ids: List<Int>): Pair<Int, Int> {
            var and = 0x7FF
            var or = 0
            for (id in ids) {
                and = and and id
                or = or or id
            }
            val mask = (or.inv() and 0x7FF) or and
            return and to mask
        }

        fun parseFrame(line: String): CanFrame? {
            val m = FRAME.find(line.replace(" ", "").uppercase()) ?: return null
            val hex = m.groupValues[2]
            return CanFrame(
                id = m.groupValues[1].toInt(16),
                data = IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) },
            )
        }
    }
}
