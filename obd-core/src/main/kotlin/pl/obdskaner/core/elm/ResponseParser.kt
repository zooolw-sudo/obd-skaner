package pl.obdskaner.core.elm

import pl.obdskaner.core.ObdError
import pl.obdskaner.core.ObdException
import pl.obdskaner.core.describeNegativeResponse

/**
 * Zamienia tekstową odpowiedź ELM327 na listę wiadomości (bajtów).
 *
 * Obsługuje:
 * - echo komendy, „SEARCHING…”, „BUS INIT: …OK”,
 * - odpowiedzi ze spacjami i bez (ATS0),
 * - wiele sterowników odpowiadających naraz (każda linia = osobna wiadomość),
 * - odpowiedzi wieloramkowe CAN (ISO-TP) w formacie ELM327 przy ATH0:
 *   ```
 *   014
 *   0:490201575657
 *   1:5A5A5A314B5A41
 *   2:4D303132333435
 *   ```
 *   gdzie pierwsza linia to łączna długość w bajtach (hex).
 *
 * Każda wiadomość to [IntArray] z wartościami 0–255, zaczynająca się od
 * identyfikatora odpowiedzi (np. 0x41 dla trybu 01).
 */
object ResponseParser {

    private val hexLine = Regex("^[0-9A-F]+$")
    private val frameLine = Regex("^([0-9A-F]):([0-9A-F]*)$")
    private val lengthLine = Regex("^[0-9A-F]{3}$")

    /** Dzieli surową odpowiedź na linie i usuwa z niej „szum”. */
    fun cleanLines(raw: String, sentCommand: String): List<String> {
        val echo = normalize(sentCommand)
        return raw.split('\r', '\n')
            .map { stripNoise(it.trim()) }
            .filter { it.isNotEmpty() }
            .filter { normalize(it) != echo }
    }

    /**
     * Przetwarza linie odpowiedzi na zapytanie OBD.
     * @throws ObdException gdy nie ma żadnych poprawnych danych.
     */
    fun parse(lines: List<String>): List<IntArray> {
        val errors = mutableListOf<ObdError>()
        val messages = mutableListOf<IntArray>()
        var multi: MultiFrame? = null

        for (raw in lines) {
            val error = errorOf(raw)
            if (error != null) {
                errors += error
                continue
            }
            val line = normalize(raw)
            when {
                lengthLine.matches(line) -> {
                    multi?.let { messages += it.finish() }
                    multi = MultiFrame(line.toInt(16))
                }

                frameLine.matches(line) -> {
                    val data = frameLine.find(line)!!.groupValues[2]
                    if (data.length % 2 != 0) continue
                    val target = multi ?: MultiFrame(-1).also { multi = it }
                    target.append(hexToInts(data))
                    if (target.isComplete) {
                        messages += target.finish()
                        multi = null
                    }
                }

                hexLine.matches(line) && line.length % 2 == 0 -> {
                    multi?.let { messages += it.finish() }
                    multi = null
                    messages += hexToInts(line)
                }

                // Nieznana linia (np. śmieci z taniego klona) – pomijamy.
                else -> Unit
            }
        }
        multi?.let { messages += it.finish() }

        // Odpowiedzi negatywne: 7F <usługa> <kod>. Kod 0x78 = „odpowiedź w toku”.
        val (negative, positive) = messages
            .filter { it.isNotEmpty() }
            .partition { it[0] == 0x7F }

        if (positive.isNotEmpty()) return positive

        val nrc = negative.firstOrNull { it.size >= 3 && it[2] != 0x78 }?.get(2)
        if (nrc != null) {
            throw ObdException(
                ObdError.NEGATIVE_RESPONSE,
                describeNegativeResponse(nrc),
                negativeResponseCode = nrc,
            )
        }
        throw ObdException(errors.firstOrNull() ?: ObdError.NO_DATA)
    }

    /** Rozpoznaje komunikaty błędów ELM327. */
    fun errorOf(line: String): ObdError? {
        val n = normalize(line)
        return when {
            n == "NODATA" -> ObdError.NO_DATA
            n.startsWith("UNABLETOCONNECT") -> ObdError.UNABLE_TO_CONNECT
            n.startsWith("BUSINIT") && n.contains("ERROR") -> ObdError.BUS_INIT
            n == "CANERROR" -> ObdError.CAN_ERROR
            n == "BUSERROR" || n == "BUSBUSY" || n == "FBERROR" -> ObdError.BUS_ERROR
            n.contains("DATAERROR") || n.contains("RXERROR") -> ObdError.DATA_ERROR
            n == "BUFFERFULL" -> ObdError.BUFFER_FULL
            n == "STOPPED" -> ObdError.STOPPED
            n == "LVRESET" -> ObdError.LOW_VOLTAGE
            n == "?" -> ObdError.UNKNOWN_COMMAND
            n == "ERROR" || n.startsWith("ERR") || n == "ACTALERT" -> ObdError.UNKNOWN
            else -> null
        }
    }

    private fun stripNoise(line: String): String {
        var l = line
        if (l.uppercase().startsWith("SEARCHING")) {
            l = l.substringAfter("...", "")
        }
        if (l.uppercase().startsWith("BUS INIT")) {
            if (l.uppercase().contains("ERROR")) return "BUS INIT ERROR"
            l = l.substringAfter(":").replace(".", "").trim()
            if (l.uppercase().startsWith("OK")) l = l.substring(2)
        }
        return l.trim()
    }

    private fun normalize(s: String) = s.replace(" ", "").uppercase()

    private fun hexToInts(hex: String): IntArray =
        IntArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }

    /** Składa odpowiedź z kolejnych ramek CAN i obcina dopełnienie. */
    private class MultiFrame(private val expected: Int) {
        private val data = ArrayList<Int>()

        fun append(bytes: IntArray) {
            bytes.forEach { data += it }
        }

        val isComplete: Boolean get() = expected > 0 && data.size >= expected

        fun finish(): IntArray {
            val size = if (expected > 0) minOf(expected, data.size) else data.size
            return IntArray(size) { data[it] }
        }
    }
}
