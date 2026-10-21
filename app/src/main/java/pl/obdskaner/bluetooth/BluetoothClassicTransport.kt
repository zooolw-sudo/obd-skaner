package pl.obdskaner.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import pl.obdskaner.core.transport.StreamTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Połączenie z adapterem ELM327 przez klasyczny Bluetooth (profil SPP – port szeregowy).
 * Uprawnienie BLUETOOTH_CONNECT sprawdza UI przed wywołaniem.
 */
@SuppressLint("MissingPermission")
class BluetoothClassicTransport(private val device: BluetoothDevice) : StreamTransport() {

    @Volatile
    private var socket: BluetoothSocket? = null

    override fun connectBlocking(): Pair<InputStream, OutputStream> {
        val s = try {
            connectWith { device.createRfcommSocketToServiceRecord(SPP_UUID) }
        } catch (first: IOException) {
            // Część tanich klonów łączy się tylko w trybie „insecure”.
            try {
                connectWith { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
            } catch (second: IOException) {
                second.addSuppressed(first)
                throw second
            }
        }
        socket = s
        return s.inputStream to s.outputStream
    }

    private inline fun connectWith(create: () -> BluetoothSocket): BluetoothSocket {
        val s = create()
        try {
            s.connect()
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return s
    }

    override fun disconnectBlocking() {
        socket?.close()
        socket = null
    }

    companion object {
        /** Standardowy identyfikator usługi „Serial Port Profile”. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
