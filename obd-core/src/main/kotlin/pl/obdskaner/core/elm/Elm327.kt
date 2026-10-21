package pl.obdskaner.core.elm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import pl.obdskaner.core.ObdError
import pl.obdskaner.core.ObdException
import pl.obdskaner.core.transport.ObdTransport

/**
 * Niski poziom: rozmowa z układem ELM327 (i jego klonami).
 *
 * Każda komenda to tekst zakończony `\r`. Adapter odpowiada tekstem
 * zakończonym znakiem zachęty `>`. Mutex pilnuje, żeby dwie komendy
 * nigdy nie poszły jednocześnie (np. odczyt danych na żywo i odczyt błędów).
 */
class Elm327(
    private val transport: ObdTransport,
    /** Wywoływane dla każdej wysłanej komendy i odpowiedzi – przydatne do debugowania. */
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val buffer = StringBuilder()

    /** Wersja układu z odpowiedzi na ATZ, np. "ELM327 v1.5". */
    var version: String = ""
        private set

    /** Resetuje adapter i ustawia go do pracy z parserem. */
    suspend fun initialize() {
        val reset = sendRaw("ATZ", RESET_TIMEOUT_MS)
        val resetLines = ResponseParser.cleanLines(reset, "ATZ")
        version = (resetLines.lastOrNull { it.contains("ELM", ignoreCase = true) } ?: resetLines.lastOrNull())
            ?.trim()
            .orEmpty()

        at("ATE0")                  // bez echa komend
        at("ATL0", optional = true) // bez dodatkowych znaków nowej linii
        at("ATS0", optional = true) // bez spacji (od v1.3) – parser i tak radzi sobie ze spacjami
        at("ATH0")                  // bez nagłówków
        at("ATAT1", optional = true)// adaptacyjny czas oczekiwania
        at("ATSP0")                 // automatyczny wybór protokołu
    }

    /**
     * Wysyła komendę AT i sprawdza, czy adapter ją przyjął.
     * Dla [optional] = true ignoruje odpowiedź „?” (tanie klony nie znają części komend).
     */
    suspend fun at(command: String, optional: Boolean = false): String {
        val lines = ResponseParser.cleanLines(sendRaw(command, AT_TIMEOUT_MS), command)
        val unsupported = lines.any { it.trim() == "?" }
        if (unsupported && !optional) {
            throw ObdException(ObdError.UNKNOWN_COMMAND, "Adapter nie obsługuje komendy $command")
        }
        return lines.joinToString("\n")
    }

    /** Wysyła zapytanie OBD (np. "010C") i zwraca odebrane wiadomości. */
    suspend fun obd(command: String, timeoutMs: Long = OBD_TIMEOUT_MS): List<IntArray> {
        // Odpowiedź na tryb XX zaczyna się od XX + 0x40 (np. 01 → 41).
        val expected = command.take(2).toIntOrNull(16)?.let { "%02X".format(it + 0x40) }
        val raw = sendRaw(command, timeoutMs) { response ->
            expected == null || !isAnswerToOtherQuery(response, command, expected)
        }
        return ResponseParser.parse(ResponseParser.cleanLines(raw, command))
    }

    /**
     * Rozpoznaje spóźnioną odpowiedź na wcześniejsze zapytanie (np. po timeoucie):
     * same poprawne dane, ale żadna linia nie zaczyna się od oczekiwanego identyfikatora.
     */
    private fun isAnswerToOtherQuery(raw: String, command: String, expected: String): Boolean {
        val lines = ResponseParser.cleanLines(raw, command).map { it.replace(" ", "").uppercase() }
        return lines.isNotEmpty() && lines.all {
            SINGLE_FRAME.matches(it) && !it.startsWith(expected) && !it.startsWith("7F")
        }
    }

    /** Aktualnie używany protokół (ATDPN). */
    suspend fun protocol(): ObdProtocol = ObdProtocol.fromDpn(at("ATDPN"))

    /** Napięcie na pinie 16 gniazda OBD, czyli w praktyce napięcie akumulatora (ATRV). */
    suspend fun batteryVoltage(): Double? =
        at("ATRV", optional = true)
            .uppercase()
            .removeSuffix("V")
            .trim()
            .replace(',', '.')
            .toDoubleOrNull()

    /**
     * Wysyła dowolny tekst i czeka na znak zachęty `>`.
     * Jeśli [accept] odrzuci odpowiedź (spóźniona odpowiedź na coś innego),
     * czeka na następną – maksymalnie dwa razy.
     */
    suspend fun sendRaw(
        command: String,
        timeoutMs: Long,
        accept: (String) -> Boolean = { true },
    ): String = mutex.withLock {
        discardStaleData()
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
        log("→ $command")
        val response = withTimeoutOrNull(timeoutMs) {
            var r = readUntilPrompt()
            var skipped = 0
            while (!accept(r) && skipped < 2) {
                log("… pominięto spóźnioną odpowiedź: " + r.trim().replace("\r", " | "))
                r = readUntilPrompt()
                skipped++
            }
            r
        }
            ?: run {
                log("✕ $command: brak odpowiedzi")
                throw ObdException(ObdError.TIMEOUT, "Brak odpowiedzi na $command")
            }
        log("← " + response.trim().replace("\r", " | ").replace("\n", ""))
        response
    }

    private suspend fun readUntilPrompt(): String {
        while (true) {
            val idx = buffer.indexOf(">")
            if (idx >= 0) {
                val text = buffer.substring(0, idx)
                buffer.delete(0, idx + 1)
                return text
            }
            val chunk = transport.incoming.receiveCatching().getOrNull()
                ?: throw ObdException(ObdError.DISCONNECTED)
            for (b in chunk) {
                val c = b.toInt() and 0xFF
                if (c != 0) buffer.append(c.toChar()) // klony potrafią wysyłać bajty 0x00
            }
        }
    }

    /** Usuwa resztki spóźnionej odpowiedzi na poprzednią komendę. */
    private fun discardStaleData() {
        buffer.clear()
        while (transport.incoming.tryReceive().isSuccess) {
            // odrzucamy
        }
    }

    companion object {
        const val RESET_TIMEOUT_MS = 5_000L
        const val AT_TIMEOUT_MS = 3_000L
        const val OBD_TIMEOUT_MS = 5_000L

        /** Pierwsze zapytanie po ATSP0 uruchamia szukanie protokołu – to może potrwać. */
        const val PROTOCOL_SEARCH_TIMEOUT_MS = 20_000L

        private val SINGLE_FRAME = Regex("^([0-9A-F]{2})+$")
    }
}
