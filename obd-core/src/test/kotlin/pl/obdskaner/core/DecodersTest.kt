package pl.obdskaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.obdskaner.core.elm.ObdProtocol
import pl.obdskaner.core.elm.ResponseParser
import pl.obdskaner.core.obd.DtcDecoder
import pl.obdskaner.core.obd.DtcDescriptions
import pl.obdskaner.core.obd.Pids

class DecodersTest {

    @Test
    fun `dekodowanie kodow bledow`() {
        assertEquals("P0133", DtcDecoder.decode(0x01, 0x33))
        assertEquals("C0123", DtcDecoder.decode(0x41, 0x23))
        assertEquals("B1234", DtcDecoder.decode(0x92, 0x34))
        assertEquals("U0100", DtcDecoder.decode(0xC1, 0x00))
        assertEquals("P2463", DtcDecoder.decode(0x24, 0x63))
    }

    @Test
    fun `wzory PID`() {
        assertEquals(1726.0, Pids.RPM.decode(intArrayOf(0x1A, 0xF8)), 0.001)
        assertEquals(-40.0, Pids.COOLANT_TEMP.decode(intArrayOf(0x00)), 0.001)
        assertEquals(0.0, Pids.SHORT_FUEL_TRIM_1.decode(intArrayOf(0x80)), 0.001)
        assertEquals(-100.0, Pids.LONG_FUEL_TRIM_1.decode(intArrayOf(0x00)), 0.001)
        assertEquals(100.0, Pids.THROTTLE.decode(intArrayOf(0xFF)), 0.001)
        assertEquals(12.6, Pids.MODULE_VOLTAGE.decode(intArrayOf(0x31, 0x38)), 0.001)
        assertEquals(2.55, Pids.MAF.decode(intArrayOf(0x00, 0xFF)), 0.001)
    }

    @Test
    fun `protokol z ATDPN`() {
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("6"))
        assertEquals(ObdProtocol.J1939, ObdProtocol.fromDpn("A"))
        assertEquals(ObdProtocol.J1939, ObdProtocol.fromDpn("AA"))
        assertEquals(ObdProtocol.KWP_FAST, ObdProtocol.fromDpn("A5"))
        assertEquals(ObdProtocol.UNKNOWN, ObdProtocol.fromDpn(""))
    }

    @Test
    fun `kilka sterownikow odpowiada naraz`() {
        val lines = ResponseParser.cleanLines("0100\r4100BE3FA813\r410098188001\r", "0100")
        val messages = ResponseParser.parse(lines)
        assertEquals(2, messages.size)
        assertEquals(0xBE, messages[0][2])
        assertEquals(0x98, messages[1][2])
    }

    @Test
    fun `opisy kodow`() {
        assertEquals("Wypadanie zapłonu – cylinder 4", DtcDescriptions.describe("P0304"))
        assertTrue(DtcDescriptions.describe("P1234").contains("kod producenta"))
        assertTrue(DtcDescriptions.describe("P0399").contains("zapłon"))
    }
}
