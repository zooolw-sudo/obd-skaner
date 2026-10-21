package pl.obdskaner.core.obd

/**
 * Polskie opisy najczęstszych ogólnych kodów (SAE J2012).
 *
 * To baza startowa – do rozbudowy. Kody producenta (P1xxx, B1xxx…) mają różne
 * znaczenie w różnych markach, więc dla nich podajemy tylko opis ogólny.
 */
object DtcDescriptions {

    private val known: Map<String, String> = buildMap {
        put("P0016", "Korelacja położenia wału korbowego i wałka rozrządu (bank 1, czujnik A)")
        put("P0087", "Zbyt niskie ciśnienie paliwa w listwie / układzie")
        put("P0088", "Zbyt wysokie ciśnienie paliwa w listwie / układzie")
        put("P0100", "Przepływomierz powietrza (MAF) – usterka obwodu")
        put("P0101", "Przepływomierz powietrza (MAF) – sygnał poza zakresem / nieprawidłowe działanie")
        put("P0102", "Przepływomierz powietrza (MAF) – niski sygnał")
        put("P0103", "Przepływomierz powietrza (MAF) – wysoki sygnał")
        put("P0106", "Czujnik ciśnienia w kolektorze ssącym (MAP) – sygnał poza zakresem")
        put("P0110", "Czujnik temperatury powietrza dolotowego – usterka obwodu")
        put("P0115", "Czujnik temperatury płynu chłodzącego – usterka obwodu")
        put("P0116", "Czujnik temperatury płynu chłodzącego – sygnał poza zakresem")
        put("P0117", "Czujnik temperatury płynu chłodzącego – niski sygnał")
        put("P0118", "Czujnik temperatury płynu chłodzącego – wysoki sygnał")
        put("P0120", "Czujnik położenia przepustnicy (TPS) A – usterka obwodu")
        put("P0121", "Czujnik położenia przepustnicy (TPS) A – sygnał poza zakresem")
        put("P0128", "Termostat – temperatura płynu chłodzącego poniżej temperatury pracy")
        put("P0130", "Sonda lambda (bank 1, czujnik 1) – usterka obwodu")
        put("P0131", "Sonda lambda (bank 1, czujnik 1) – niskie napięcie")
        put("P0132", "Sonda lambda (bank 1, czujnik 1) – wysokie napięcie")
        put("P0133", "Sonda lambda (bank 1, czujnik 1) – wolna reakcja")
        put("P0134", "Sonda lambda (bank 1, czujnik 1) – brak aktywności")
        put("P0135", "Sonda lambda (bank 1, czujnik 1) – usterka obwodu grzałki")
        put("P0136", "Sonda lambda (bank 1, czujnik 2) – usterka obwodu")
        put("P0141", "Sonda lambda (bank 1, czujnik 2) – usterka obwodu grzałki")
        put("P0171", "Zbyt uboga mieszanka (bank 1)")
        put("P0172", "Zbyt bogata mieszanka (bank 1)")
        put("P0174", "Zbyt uboga mieszanka (bank 2)")
        put("P0175", "Zbyt bogata mieszanka (bank 2)")
        put("P0190", "Czujnik ciśnienia paliwa w listwie – usterka obwodu")
        for (n in 1..8) put("P020$n", "Wtryskiwacz cylindra $n – usterka obwodu")
        put("P0234", "Turbosprężarka – zbyt wysokie ciśnienie doładowania")
        put("P0299", "Turbosprężarka – zbyt niskie ciśnienie doładowania")
        put("P0300", "Wykryto losowe / wielokrotne wypadanie zapłonu")
        for (n in 1..8) put("P030$n", "Wypadanie zapłonu – cylinder $n")
        put("P0325", "Czujnik spalania stukowego 1 (bank 1) – usterka obwodu")
        put("P0335", "Czujnik położenia wału korbowego A – usterka obwodu")
        put("P0340", "Czujnik położenia wałka rozrządu A (bank 1) – usterka obwodu")
        put("P0380", "Świece żarowe – usterka obwodu A")
        put("P0400", "Recyrkulacja spalin (EGR) – nieprawidłowy przepływ")
        put("P0401", "Recyrkulacja spalin (EGR) – niewystarczający przepływ")
        put("P0402", "Recyrkulacja spalin (EGR) – nadmierny przepływ")
        put("P0420", "Sprawność katalizatora poniżej progu (bank 1)")
        put("P0430", "Sprawność katalizatora poniżej progu (bank 2)")
        put("P0440", "Układ kontroli odparowania paliwa (EVAP) – usterka")
        put("P0442", "Układ EVAP – wykryto mały wyciek")
        put("P0455", "Układ EVAP – wykryto duży wyciek")
        put("P0500", "Czujnik prędkości pojazdu A – usterka")
        put("P0505", "Układ sterowania biegiem jałowym – usterka")
        put("P0506", "Obroty biegu jałowego niższe od oczekiwanych")
        put("P0507", "Obroty biegu jałowego wyższe od oczekiwanych")
        put("P0560", "Napięcie instalacji – usterka")
        put("P0562", "Napięcie instalacji – zbyt niskie")
        put("P0563", "Napięcie instalacji – zbyt wysokie")
        put("P0700", "Usterka układu sterowania skrzyni biegów (szczegóły w sterowniku skrzyni)")
        put("P2002", "Sprawność filtra cząstek stałych (DPF) poniżej progu (bank 1)")
        put("P2463", "Filtr cząstek stałych (DPF) – nadmierne nagromadzenie sadzy")
        put("U0100", "Utrata komunikacji ze sterownikiem silnika (ECM/PCM) A")
        put("U0121", "Utrata komunikacji z modułem ABS")
        put("U0155", "Utrata komunikacji z zestawem wskaźników (licznikami)")
    }

    fun describe(code: String): String = known[code] ?: generic(code)

    /** Opis zastępczy na podstawie budowy kodu. */
    private fun generic(code: String): String {
        if (code.length != 5) return "Nieznany kod"
        val system = when (code[0]) {
            'P' -> "napęd"
            'C' -> "podwozie"
            'B' -> "nadwozie"
            'U' -> "komunikacja"
            else -> return "Nieznany kod"
        }
        val type = when (code[1]) {
            '0' -> "kod ogólny"
            '1' -> "kod producenta"
            '2' -> if (code[0] == 'P') "kod ogólny" else "kod producenta"
            else -> if (code[0] == 'P') "kod ogólny / producenta" else "kod ogólny"
        }
        val area = if (code[0] == 'P' && code[1] in "01") {
            when (code[2]) {
                '0', '1', '2' -> ", układ paliwa i powietrza"
                '3' -> ", zapłon / wypadanie zapłonu"
                '4' -> ", dodatkowa kontrola emisji"
                '5' -> ", prędkość pojazdu i bieg jałowy"
                '6' -> ", sterownik i jego wyjścia"
                '7', '8', '9' -> ", skrzynia biegów"
                else -> ""
            }
        } else ""
        return "Brak opisu w bazie – $type ($system$area)"
    }
}
