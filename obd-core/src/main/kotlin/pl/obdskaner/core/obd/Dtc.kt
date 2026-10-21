package pl.obdskaner.core.obd

/** Rodzaj listy kodów błędów. */
enum class DtcKind(val mode: String, val responseSid: Int, val label: String) {
    /** Potwierdzone błędy – te zapalają kontrolkę „check engine”. */
    STORED("03", 0x43, "Zapisane"),

    /** Wykryte w bieżącym lub ostatnim cyklu jazdy, jeszcze niepotwierdzone. */
    PENDING("07", 0x47, "Oczekujące"),

    /** Nie da się ich skasować – znikają same, gdy sterownik uzna usterkę za usuniętą. */
    PERMANENT("0A", 0x4A, "Stałe"),
}

data class Dtc(val code: String, val description: String)

/** Stan z PID 01: kontrolka MIL i liczba zapisanych błędów. */
data class MonitorStatus(val milOn: Boolean, val dtcCount: Int)

object DtcDecoder {
    private val systems = charArrayOf('P', 'C', 'B', 'U')

    /**
     * Dwa bajty → kod, np. 0x01 0x33 → "P0133".
     *
     * Bity A7–A6 to układ (P, C, B, U), A5–A4 pierwsza cyfra,
     * A3–A0 druga cyfra (hex), bajt B to dwie ostatnie cyfry.
     */
    fun decode(a: Int, b: Int): String =
        buildString {
            append(systems[(a shr 6) and 0x03])
            append((a shr 4) and 0x03)
            append("%X".format(a and 0x0F))
            append("%02X".format(b and 0xFF))
        }

    /**
     * Wyciąga kody z odpowiedzi na tryb 03 / 07 / 0A.
     *
     * W CAN po identyfikatorze odpowiedzi jest bajt z liczbą kodów, a potem po 2 bajty na kod.
     * W starszych protokołach (K-line, J1850) każda linia ma zawsze 3 kody
     * (6 bajtów), dopełnione zerami, i nie ma bajtu liczby.
     *
     * @param isCan null = protokół nieznany; wtedy decyduje parzystość długości
     */
    fun parse(messages: List<IntArray>, responseSid: Int, isCan: Boolean?): List<String> {
        val codes = LinkedHashSet<String>()
        for (m in messages) {
            if (m.isEmpty() || m[0] != responseSid) continue
            val hasCountByte = isCan ?: ((m.size - 1) % 2 == 1)
            var i = if (hasCountByte) 2 else 1
            while (i + 1 < m.size) {
                val a = m[i]
                val b = m[i + 1]
                if (a != 0 || b != 0) codes += decode(a, b)
                i += 2
            }
        }
        return codes.toList()
    }
}
