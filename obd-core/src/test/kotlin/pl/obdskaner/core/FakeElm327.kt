package pl.obdskaner.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import pl.obdskaner.core.transport.ObdTransport

/**
 * Udawany adapter ELM327 do testów – zachowuje się jak prawdziwy układ:
 * echo do czasu ATE0, „SEARCHING…” przy pierwszym zapytaniu, dane w kawałkach.
 *
 * @param responses odpowiedzi na zapytania OBD (bez spacji, linie oddzielone `\r`);
 *                  [SILENCE] = adapter milczy (do testu timeoutu)
 */
class FakeElm327(
    private val responses: Map<String, String>,
    private val dpn: String = "A6",
    private val spaces: Boolean = false,
    private val supportsAts0: Boolean = true,
    private val firstQueryPrefix: String = "SEARCHING...\r",
    /** Spóźniona odpowiedź na inne zapytanie, wysyłana tuż przed właściwą. */
    private val staleBefore: Map<String, String> = emptyMap(),
) : ObdTransport {

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = channel
    @Volatile private var closed = false
    override val isOpen: Boolean get() = !closed

    val received = mutableListOf<String>()
    private var echo = true
    private var searched = false
    private val pending = StringBuilder()

    override suspend fun open() {}

    override suspend fun write(bytes: ByteArray) {
        pending.append(String(bytes, Charsets.US_ASCII))
        while (true) {
            val idx = pending.indexOf("\r")
            if (idx < 0) break
            val cmd = pending.substring(0, idx).trim().uppercase()
            pending.delete(0, idx + 1)
            handle(cmd)
        }
    }

    private fun handle(cmd: String) {
        received += cmd
        val echoNow = echo
        val body = respond(cmd) ?: return
        val stale = staleBefore[cmd]?.let { "$it\r\r>" }.orEmpty()
        val text = stale + (if (echoNow) "$cmd\r" else "") + body + "\r\r>"
        // Dzielimy na małe kawałki, jak przy prawdziwym Bluetooth.
        text.toByteArray(Charsets.US_ASCII).toList().chunked(5).forEach {
            channel.trySend(it.toByteArray())
        }
    }

    private fun respond(cmd: String): String? = when {
        cmd == "ATZ" -> { echo = true; "\rELM327 v1.5" }
        cmd == "ATE0" -> { echo = false; "OK" }
        cmd == "ATS0" -> if (supportsAts0) "OK" else "?"
        cmd == "ATDPN" -> dpn
        cmd == "ATRV" -> "12.6V"
        cmd.startsWith("AT") -> "OK"
        else -> {
            val r = responses[cmd] ?: "NO DATA"
            if (r == SILENCE) {
                null
            } else {
                val prefix = if (!searched) firstQueryPrefix.also { searched = true } else ""
                prefix + if (spaces) withSpaces(r) else r
            }
        }
    }

    private fun withSpaces(r: String) = r.split("\r").joinToString("\r") { line ->
        val frame = Regex("^([0-9A-F]):([0-9A-F]+)$").find(line)
        when {
            frame != null -> frame.groupValues[1] + ": " + frame.groupValues[2].chunked(2).joinToString(" ")
            Regex("^[0-9A-F]+$").matches(line) && line.length % 2 == 0 -> line.chunked(2).joinToString(" ")
            else -> line
        }
    }

    override fun close() {
        closed = true
        channel.close()
    }

    companion object {
        const val SILENCE = "<cisza>"

        /** Buduje odpowiedź na 0100/0120/… z listy obsługiwanych PID-ów. */
        fun supportMask(base: Int, pids: Set<Int>): String {
            var mask = 0L
            for (i in 0 until 32) if ((base + i + 1) in pids) mask = mask or (1L shl (31 - i))
            return "41%02X%08X".format(base, mask)
        }
    }
}
