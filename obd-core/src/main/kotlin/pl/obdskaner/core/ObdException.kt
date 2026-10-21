package pl.obdskaner.core

/** Rodzaje błędów zgłaszanych przez adapter lub połączenie. */
enum class ObdError(val description: String) {
    CONNECTION_FAILED("Nie udało się połączyć z adapterem"),
    DISCONNECTED("Połączenie z adapterem zostało przerwane"),
    TIMEOUT("Adapter nie odpowiedział w wyznaczonym czasie"),
    NO_DATA("Brak danych – auto nie obsługuje tego zapytania"),
    UNABLE_TO_CONNECT("Adapter nie może połączyć się z autem – sprawdź, czy zapłon jest włączony"),
    BUS_INIT("Błąd inicjalizacji magistrali"),
    CAN_ERROR("Błąd magistrali CAN"),
    BUS_ERROR("Błąd lub zajętość magistrali"),
    DATA_ERROR("Odebrano uszkodzone dane"),
    BUFFER_FULL("Przepełniony bufor adaptera"),
    STOPPED("Odbiór danych został przerwany"),
    LOW_VOLTAGE("Zbyt niskie napięcie – adapter się zresetował"),
    UNKNOWN_COMMAND("Adapter nie rozumie polecenia"),
    NEGATIVE_RESPONSE("Sterownik odrzucił zapytanie"),
    UNKNOWN("Nieznany błąd adaptera"),
}

class ObdException(
    val error: ObdError,
    detail: String? = null,
    /** Kod odmowy sterownika (NRC) przy [ObdError.NEGATIVE_RESPONSE]. */
    val negativeResponseCode: Int? = null,
) : Exception(detail ?: error.description)

/** Opisy najczęstszych kodów odmowy (NRC) zwracanych przez sterowniki. */
internal fun describeNegativeResponse(nrc: Int): String = when (nrc) {
    0x10 -> "Sterownik odrzucił zapytanie"
    0x11 -> "Sterownik nie obsługuje tej usługi"
    0x12 -> "Sterownik nie obsługuje tej funkcji"
    0x13 -> "Nieprawidłowa długość lub format zapytania"
    0x21 -> "Sterownik jest zajęty – spróbuj ponownie"
    0x22 -> "Warunki niespełnione (np. pracujący silnik) – spróbuj przy włączonym zapłonie i wyłączonym silniku"
    0x31 -> "Wartość poza zakresem"
    0x33 -> "Wymagany dostęp zabezpieczony"
    else -> "Sterownik odrzucił zapytanie (kod 0x%02X)".format(nrc)
}
