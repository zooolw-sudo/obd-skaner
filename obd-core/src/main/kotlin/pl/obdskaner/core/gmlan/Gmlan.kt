package pl.obdskaner.core.gmlan

import pl.obdskaner.core.elm.ObdProtocol
import pl.obdskaner.core.obd.DtcDescriptions

/**
 * Adresy jednego sterownika w sieci GMLAN (Saab 9-3 od 2003, Opel Vectra C, Astra H…).
 *
 * @param request   identyfikator ramki z zapytaniem (USDT, od testera do sterownika)
 * @param response  identyfikator odpowiedzi (USDT, od sterownika)
 * @param uudt      identyfikator ramek UUDT – w nich sterownik wysyła listę błędów
 */
data class GmlanAddress(val request: Int, val response: Int, val uudt: Int) {

    val label: String get() = "0x%03X".format(request)

    companion object {
        /**
         * Konwencja GMLAN: 0x7E0–0x7E7 → odpowiedź +8 (jak w OBD2),
         * 0x241–0x25F → odpowiedź 0x641–0x65F; ramki UUDT mają ten sam młodszy bajt z 0x5xx.
         */
        fun forRequest(request: Int): GmlanAddress {
            val response = if (request in 0x7E0..0x7E7) request + 8 else request + 0x400
            return GmlanAddress(request, response, 0x500 or (response and 0xFF))
        }
    }
}

/**
 * Kod błędu GMLAN: kod główny, typ usterki (FTB) i bajt statusu.
 * W dokumentacji Saaba (WIS) zapisywany jako np. „P0600-00” albo „C0035-01”.
 */
data class GmlanDtc(val code: String, val failureType: Int, val status: Int) {

    val fullCode: String get() = "%s-%02X".format(code, failureType)

    /** Usterka występuje teraz (bit 1 statusu). */
    val isCurrent: Boolean get() = status and 0x02 != 0

    /** Usterka zapisana w historii (bit 4 statusu). */
    val isHistory: Boolean get() = status and 0x10 != 0

    /** Sterownik prosi o zapalenie kontrolki (bit 7 statusu). */
    val warningLamp: Boolean get() = status and 0x80 != 0

    val description: String get() = DtcDescriptions.describe(code)
}

data class GmlanModule(
    val address: GmlanAddress,
    val name: String,
    val dtcs: List<GmlanDtc> = emptyList(),
    /** false = nie odebrano znacznika końca listy, więc lista może być niepełna. */
    val complete: Boolean = true,
    /** Opis problemu, jeśli sterownik odpowiedział, ale nie podał błędów. */
    val error: String? = null,
)

sealed interface GmlanProgress {
    data class Probing(val index: Int, val total: Int, val address: GmlanAddress) : GmlanProgress
    data class Reading(val index: Int, val total: Int, val module: GmlanModule) : GmlanProgress
}

/** Czy skan GMLAN ma sens w tym aucie. */
enum class GmlanCompatibility {
    /** VIN wskazuje auto GM (Saab, Opel, Vauxhall, Chevrolet…), magistrala CAN 500 kbit/s. */
    SUPPORTED,

    /** Nie udało się odczytać VIN – skan tylko po potwierdzeniu przez użytkownika. */
    NEEDS_CONFIRMATION,

    /** Inna marka – zapytania na adresy GMLAN mogłyby trafić w zwykłe ramki tego auta. */
    OTHER_MAKE,

    /** Brak CAN 11 bit 500 kbit/s na pinach 6 i 14. */
    WRONG_PROTOCOL;

    companion object {
        /** Kody producenta (WMI, pierwsze znaki VIN) aut z siecią GMLAN. */
        private val GM_WMI_PREFIXES = listOf("YS3", "W0L", "W0V", "1G", "2G", "3G", "KL")

        fun of(protocol: ObdProtocol, vin: String?): GmlanCompatibility = when {
            protocol != ObdProtocol.CAN_11_500 -> WRONG_PROTOCOL
            vin.isNullOrBlank() -> NEEDS_CONFIRMATION
            GM_WMI_PREFIXES.any { vin.uppercase().startsWith(it) } -> SUPPORTED
            else -> OTHER_MAKE
        }
    }
}

/** Nazwy sterowników o znanych adresach. Pozostałe uzupełnimy po skanie w aucie. */
internal fun gmlanModuleName(request: Int): String = when (request) {
    0x7E0 -> "Silnik (ECM)"
    0x7E2 -> "Skrzynia biegów (TCM)"
    else -> "Sterownik 0x%03X".format(request)
}
