package pl.obdskaner.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import pl.obdskaner.core.gmlan.GmlanAddress
import pl.obdskaner.core.transport.ObdTransport

/**
 * Udawany sterownik GMLAN.
 *
 * @param dtcs        błędy: (kod 16-bit, typ usterki, status), np. (0x0133, 0x00, 0x12) = P0133-00
 * @param pendingFirst najpierw odpowiedź „7F A9 78” (odpowiedź w toku), potem lista
 * @param busyTimes   ile razy odpowiedzieć „zajęty” (7F A9 21) przed podaniem listy
 * @param supportsA9  false = odmowa „usługa nieobsługiwana” (7F A9 11)
 * @param endMarker   false = brak ramki kończącej listę
 */
class FakeGmlanModule(
    val dtcs: List<Triple<Int, Int, Int>> = emptyList(),
    val pendingFirst: Boolean = false,
    var busyTimes: Int = 0,
    val supportsA9: Boolean = true,
    val endMarker: Boolean = true,
)

/**
 * Udawany adapter ELM327 do testów – zachowuje się jak prawdziwy układ:
 * echo do czasu ATE0, „SEARCHING…” przy pierwszym zapytaniu, dane w kawałkach.
 * Obsługuje też tryb surowych ramek (ATH1, ATCAF0, ATSH, filtry ATCF/ATCM)
 * z udawanymi sterownikami GMLAN.
 *
 * @param responses odpowiedzi na zapytania OBD (bez spacji, linie oddzielone `\r`);
 *                  [SILENCE] = adapter milczy (do testu timeoutu)
 * @param gmlanModules sterowniki GMLAN według adresu zapytania (np. 0x243)
 */
class FakeElm327(
    private val responses: Map<String, String>,
    private val dpn: String = "A6",
    private val spaces: Boolean = false,
    private val supportsAts0: Boolean = true,
    private val firstQueryPrefix: String = "SEARCHING...\r",
    /** Spóźniona odpowiedź na inne zapytanie, wysyłana tuż przed właściwą. */
    private val staleBefore: Map<String, String> = emptyMap(),
    private val gmlanModules: Map<Int, FakeGmlanModule> = emptyMap(),
) : ObdTransport {

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = channel
    @Volatile private var closed = false
    override val isOpen: Boolean get() = !closed

    val received = mutableListOf<String>()

    /** Nagłówki (adresy), na które wysłano surowe ramki. */
    val rawHeaders = mutableListOf<Int>()

    private var echo = true
    private var searched = false
    private val pending = StringBuilder()

    // Stan trybu surowego
    var headersOn = false
        private set
    var autoFormat = true
        private set
    private var header = 0x7DF
    private var canFilter: Int? = null
    private var canMask: Int? = null

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
        cmd == "ATZ" -> {
            echo = true
            headersOn = false
            autoFormat = true
            header = 0x7DF
            canFilter = null
            canMask = null
            "\rELM327 v1.5"
        }
        cmd == "ATE0" -> { echo = false; "OK" }
        cmd == "ATS0" -> if (supportsAts0) "OK" else "?"
        cmd == "ATDPN" -> dpn
        cmd == "ATRV" -> "12.6V"
        cmd == "ATH1" -> { headersOn = true; "OK" }
        cmd == "ATH0" -> { headersOn = false; "OK" }
        cmd == "ATCAF0" -> { autoFormat = false; "OK" }
        cmd == "ATCAF1" -> { autoFormat = true; "OK" }
        cmd.startsWith("ATSH") -> { header = cmd.removePrefix("ATSH").toInt(16); "OK" }
        cmd.startsWith("ATCF") && !cmd.startsWith("ATCFC") -> { canFilter = cmd.removePrefix("ATCF").toInt(16); "OK" }
        cmd.startsWith("ATCM") -> { canMask = cmd.removePrefix("ATCM").toInt(16); "OK" }
        cmd.startsWith("AT") -> "OK"
        !autoFormat -> rawFrame(cmd)
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

    // ── GMLAN ────────────────────────────────────────────────────────────────────

    private fun rawFrame(hex: String): String {
        rawHeaders += header
        val data = hex.chunked(2).map { it.toInt(16) }
        val module = gmlanModules[header] ?: return "NO DATA"
        val a = GmlanAddress.forRequest(header)
        val out = mutableListOf<Pair<Int, String>>()

        when (data.getOrNull(1)) {
            0x3E -> out += a.response to "017E000000000000"
            0xA9 -> when {
                !module.supportsA9 -> out += a.response to "037FA91100000000"
                module.busyTimes > 0 -> {
                    module.busyTimes--
                    out += a.response to "037FA92100000000"
                }
                else -> {
                    if (module.pendingFirst) out += a.response to "037FA97800000000"
                    // Ramka z innego modułu, którą przepuszcza szeroki filtr – skaner musi ją pominąć.
                    out += (0x400 or (a.response and 0xFF)) to "0102030405060708"
                    for ((code, ftb, status) in module.dtcs) {
                        out += a.uudt to "81%04X%02X%02X000000".format(code, ftb, status)
                    }
                    if (module.endMarker) out += a.uudt to "8100000000000000"
                }
            }
            else -> out += a.response to "037F%02X1100000000".format(data.getOrElse(1) { 0 })
        }

        val lines = out
            .filter { (id, _) -> passesFilter(id) }
            .map { (id, payload) ->
                val body = if (spaces) payload.chunked(2).joinToString(" ") else payload
                if (headersOn) "%03X".format(id) + (if (spaces) " " else "") + body else body
            }
        return if (lines.isEmpty()) "NO DATA" else lines.joinToString("\r")
    }

    private fun passesFilter(id: Int): Boolean {
        val f = canFilter ?: return true
        val m = canMask ?: return true
        return (id and m) == (f and m)
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
