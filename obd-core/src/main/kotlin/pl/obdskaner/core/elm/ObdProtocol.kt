package pl.obdskaner.core.elm

/** Protokoły OBD2 obsługiwane przez ELM327 (numeracja z komendy ATDPN). */
enum class ObdProtocol(val code: Char, val label: String, val isCan: Boolean) {
    J1850_PWM('1', "SAE J1850 PWM (41,6 kbaud)", false),
    J1850_VPW('2', "SAE J1850 VPW (10,4 kbaud)", false),
    ISO_9141_2('3', "ISO 9141-2 (K-line, 5 baud init)", false),
    KWP_5BAUD('4', "ISO 14230-4 KWP2000 (5 baud init)", false),
    KWP_FAST('5', "ISO 14230-4 KWP2000 (szybki init)", false),
    CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", true),
    CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", true),
    CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", true),
    CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", true),
    J1939('A', "SAE J1939 CAN (29 bit, 250 kbaud)", true),
    USER1_CAN('B', "CAN użytkownika 1", true),
    USER2_CAN('C', "CAN użytkownika 2", true),
    UNKNOWN('?', "Nieznany", false);

    companion object {
        /**
         * Odpowiedź ATDPN to numer protokołu, poprzedzony literą "A",
         * gdy został wybrany automatycznie (np. "A6" albo "6").
         */
        fun fromDpn(response: String): ObdProtocol {
            val r = response.trim().uppercase()
            val code = when {
                r.length >= 2 && r[0] == 'A' -> r[1]
                r.isNotEmpty() -> r[0]
                else -> return UNKNOWN
            }
            return entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }
}
