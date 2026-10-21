package pl.obdskaner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pl.obdskaner.core.FakeElm327.Companion.supportMask
import pl.obdskaner.core.elm.Elm327
import pl.obdskaner.core.elm.ObdProtocol
import pl.obdskaner.core.obd.DtcKind
import pl.obdskaner.core.obd.ObdClient
import pl.obdskaner.core.obd.Pids

class ObdClientTest {

    private val supported = setOf(0x01, 0x04, 0x05, 0x0C, 0x0D, 0x0F, 0x11, 0x20, 0x31, 0x40, 0x42)

    /** Typowe auto z CAN 11 bit 500 kbaud. */
    private val canCar = mapOf(
        "0100" to supportMask(0x00, supported),
        "0120" to supportMask(0x20, supported),
        "0140" to supportMask(0x40, supported),
        "0101" to "41018307E100",          // MIL włączona, 3 błędy
        "010C" to "410C1AF8",              // 1726 obr/min
        "0105" to "41057B",                // 83 °C
        "010D" to "410D32",                // 50 km/h
        "0142" to "41423138",              // 12,6 V
        // 4 błędy, odpowiedź wieloramkowa z dopełnieniem AA
        "03" to "00A\r0:430401330171\r1:0300C100AAAAAA",
        "07" to "4700",
        "0A" to "NO DATA",
        "04" to "44",
        // VIN WVWZZZ1KZAM012345 w 3 ramkach
        "0902" to "014\r0:490201575657\r1:5A5A5A314B5A41\r2:4D303132333435",
    )

    /** Starsze auto na K-line (ISO 9141-2), odpowiedzi ze spacjami. */
    private val klineCar = mapOf(
        "0100" to supportMask(0x00, setOf(0x01, 0x05, 0x0C)),
        "03" to "43013301710300\r43C10000000000",
        "0902" to "49020100000057\r49020256575A5A\r490203" + "5A314B5A\r490204414D3031\r49020532333435",
        "010C" to "410C0FA0",              // 1000 obr/min
    )

    private fun client(
        responses: Map<String, String>,
        dpn: String = "A6",
        spaces: Boolean = false,
        supportsAts0: Boolean = true,
        firstQueryPrefix: String = "SEARCHING...\r",
    ): Pair<ObdClient, FakeElm327> {
        val fake = FakeElm327(responses, dpn, spaces, supportsAts0, firstQueryPrefix)
        return ObdClient(Elm327(fake)) to fake
    }

    @Test
    fun `polaczenie z autem CAN`() = runBlocking {
        val (client, fake) = client(canCar)
        val info = client.connect()

        assertEquals("ELM327 v1.5", info.adapterVersion)
        assertEquals(ObdProtocol.CAN_11_500, info.protocol)
        assertEquals(supported, info.supportedPids)
        assertEquals("WVWZZZ1KZAM012345", info.vin)
        assertEquals(12.6, info.batteryVoltage!!, 0.001)
        assertTrue("inicjalizacja zaczyna się od ATZ", fake.received.first() == "ATZ")
        assertTrue(fake.received.containsAll(listOf("ATE0", "ATH0", "ATSP0", "0100", "0120", "0140")))
    }

    @Test
    fun `dane na zywo`() = runBlocking {
        val (client, _) = client(canCar)
        client.connect()

        assertEquals(1726.0, client.read(Pids.RPM)!!.value, 0.001)
        assertEquals(83.0, client.read(Pids.COOLANT_TEMP)!!.value, 0.001)
        assertEquals(50.0, client.read(Pids.SPEED)!!.value, 0.001)
        assertEquals("12,6", client.read(Pids.MODULE_VOLTAGE)!!.formatted)
        assertNull("nieobsługiwany PID", client.read(Pids.OIL_TEMP))
    }

    @Test
    fun `bledy CAN wieloramkowe`() = runBlocking {
        val (client, _) = client(canCar)
        client.connect()

        val stored = client.readDtcs(DtcKind.STORED)
        assertEquals(listOf("P0133", "P0171", "P0300", "U0100"), stored.map { it.code })
        assertEquals("Sonda lambda (bank 1, czujnik 1) – wolna reakcja", stored[0].description)
        assertTrue(client.readDtcs(DtcKind.PENDING).isEmpty())
        assertTrue(client.readDtcs(DtcKind.PERMANENT).isEmpty())

        val status = client.readMonitorStatus()!!
        assertTrue(status.milOn)
        assertEquals(3, status.dtcCount)

        client.clearDtcs()
    }

    @Test
    fun `starsze auto na K-line ze spacjami`() = runBlocking {
        val (client, _) = client(
            klineCar,
            dpn = "A3",
            spaces = true,
            supportsAts0 = false,
            firstQueryPrefix = "BUS INIT: ...OK\r",
        )
        val info = client.connect()

        assertEquals(ObdProtocol.ISO_9141_2, info.protocol)
        assertEquals(setOf(0x01, 0x05, 0x0C), info.supportedPids)
        assertEquals("WVWZZZ1KZAM012345", info.vin)
        assertEquals(1000.0, client.read(Pids.RPM)!!.value, 0.001)
        assertEquals(
            listOf("P0133", "P0171", "P0300", "U0100"),
            client.readDtcs(DtcKind.STORED).map { it.code },
        )
    }

    @Test
    fun `zgaszony zaplon`() = runBlocking {
        val (client, _) = client(mapOf("0100" to "UNABLE TO CONNECT"))
        try {
            client.connect()
            fail("powinien być wyjątek")
        } catch (e: ObdException) {
            assertEquals(ObdError.UNABLE_TO_CONNECT, e.error)
        }
    }

    @Test
    fun `kasowanie przy pracujacym silniku`() = runBlocking {
        val (client, _) = client(canCar + ("04" to "7F0422"))
        client.connect()
        try {
            client.clearDtcs()
            fail("powinien być wyjątek")
        } catch (e: ObdException) {
            assertEquals(ObdError.NEGATIVE_RESPONSE, e.error)
            assertEquals(0x22, e.negativeResponseCode)
        }
    }

    @Test
    fun `spozniona odpowiedz nie myli wynikow`() = runBlocking {
        val fake = FakeElm327(canCar, staleBefore = mapOf("03" to "410C1AF8"))
        val client = ObdClient(Elm327(fake))
        client.connect()
        assertEquals(4, client.readDtcs(DtcKind.STORED).size)
    }

    @Test
    fun `timeout gdy adapter milczy`() = runBlocking {
        val fake = FakeElm327(mapOf("0100" to FakeElm327.SILENCE))
        val elm = Elm327(fake)
        val start = System.currentTimeMillis()
        try {
            elm.obd("0100", timeoutMs = 300)
            fail("powinien być wyjątek")
        } catch (e: ObdException) {
            assertEquals(ObdError.TIMEOUT, e.error)
        }
        assertTrue(System.currentTimeMillis() - start < 2_000)
    }

    @Test
    fun `rozlaczenie w trakcie`() = runBlocking {
        val fake = FakeElm327(mapOf("0100" to FakeElm327.SILENCE))
        val elm = Elm327(fake)
        fake.close()
        try {
            elm.obd("0100", timeoutMs = 1_000)
            fail("powinien być wyjątek")
        } catch (e: ObdException) {
            assertEquals(ObdError.DISCONNECTED, e.error)
        }
        assertFalse(fake.isOpen)
    }
}
