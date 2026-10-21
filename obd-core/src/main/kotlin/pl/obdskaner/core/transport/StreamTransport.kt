package pl.obdskaner.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import pl.obdskaner.core.ObdError
import pl.obdskaner.core.ObdException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Wspólna baza dla połączeń opartych na strumieniach (Bluetooth Classic, gniazdo TCP).
 *
 * Odczyt działa w osobnym wątku i wrzuca dane do kanału. Blokującego `read()`
 * ze strumienia Bluetooth nie da się przerwać timeoutem korutyny, a odbiór
 * z kanału – tak.
 *
 * Jedna instancja służy do jednego połączenia. Po [close] trzeba utworzyć nową.
 */
abstract class StreamTransport : ObdTransport {

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = channel

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var open = false
    override val isOpen: Boolean get() = open

    /** Otwiera połączenie (blokująco, na wątku IO) i zwraca jego strumienie. */
    protected abstract fun connectBlocking(): Pair<InputStream, OutputStream>

    /** Zamyka połączenie. Musi odblokować wątek czytający. */
    protected abstract fun disconnectBlocking()

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            val (input, out) = try {
                connectBlocking()
            } catch (e: IOException) {
                throw ObdException(ObdError.CONNECTION_FAILED, e.message)
            }
            output = out
            open = true
            thread(name = "obd-reader", isDaemon = true) { readLoop(input) }
        }
    }

    private fun readLoop(input: InputStream) {
        val buffer = ByteArray(512)
        try {
            while (open) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) channel.trySend(buffer.copyOf(n))
            }
        } catch (_: IOException) {
            // Zerwane połączenie albo close() – kończymy odczyt.
        } finally {
            open = false
            channel.close()
        }
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            val out = output ?: throw ObdException(ObdError.DISCONNECTED)
            try {
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                throw ObdException(ObdError.DISCONNECTED, e.message)
            }
        }
    }

    override fun close() {
        open = false
        output = null
        runCatching { disconnectBlocking() }
        channel.close()
    }
}
