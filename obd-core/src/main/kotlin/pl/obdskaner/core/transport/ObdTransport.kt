package pl.obdskaner.core.transport

import kotlinx.coroutines.channels.ReceiveChannel
import java.io.Closeable

/**
 * Kanał bajtów między aplikacją a adapterem (Bluetooth, WiFi, BLE…).
 *
 * Rdzeń nic nie wie o Androidzie – dostaje tylko ten interfejs. Dzięki temu
 * całą logikę OBD można testować na komputerze z udawanym adapterem.
 */
interface ObdTransport : Closeable {

    /** Nawiązuje połączenie z adapterem. */
    suspend fun open()

    /** Wysyła bajty do adaptera. */
    suspend fun write(bytes: ByteArray)

    /**
     * Kawałki danych przychodzących z adaptera, w kolejności odbioru.
     * Kanał zamyka się, gdy połączenie zostanie przerwane.
     */
    val incoming: ReceiveChannel<ByteArray>

    val isOpen: Boolean
}
