package pl.obdskaner.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pl.obdskaner.core.FakeElm327.Companion.supportMask
import pl.obdskaner.core.elm.Elm327
import pl.obdskaner.core.elm.ObdProtocol
import pl.obdskaner.core.gmlan.GmlanCompatibility
import pl.obdskaner.core.gmlan.GmlanProgress
import pl.obdskaner.core.gmlan.GmlanScanner
import pl.obdskaner.core.obd.ObdClient
import pl.obdskaner.core.obd.Pids

class GmlanScannerTest {

    private val obd = mapOf(
        "0100" to supportMask(0x00, setOf(0x0C)),
        "010C" to "410C1AF8",
    )

    /** Udawany Saab 9-3: silnik, skrzynia, dwa inne sterowniki na P-bus. */
    private fun saab() = mapOf(
        0x7E0 to FakeGmlanModule(dtcs = listOf(Triple(0x0133, 0x00, 0x12))),       // P0133-00
        0x7E2 to FakeGmlanModule(dtcs = listOf(Triple(0xC100, 0x00, 0x10)), pendingFirst = true), // U0100-00
        0x243 to FakeGmlanModule(dtcs = listOf(Triple(0x4035, 0x01, 0x82), Triple(0x0571, 0x1C, 0x10))), // C0035-01, P0571-1C
        0x24C to FakeGmlanModule(),
    )

    private suspend fun connected(modules: Map<Int, FakeGmlanModule>, spaces: Boolean = false): Triple<ObdClient, FakeElm327, GmlanScanner> {
        val fake = FakeElm327(obd, spaces = spaces, gmlanModules = modules)
        val client = ObdClient(Elm327(fake))
        client.connect()
        return Triple(client, fake, GmlanScanner(client.elm, client.protocol))
    }

    @Test
    fun `skan znajduje sterowniki i ich bledy`() = runBlocking {
        val (_, _, scanner) = connected(saab())
        val modules = scanner.scan()

        assertEquals(listOf(0x243, 0x24C, 0x7E0, 0x7E2), modules.map { it.address.request })

        val abs = modules[0]
        assertEquals(listOf("C0035-01", "P0571-1C"), abs.dtcs.map { it.fullCode })
        assertTrue(abs.dtcs[0].isCurrent)
        assertTrue(abs.dtcs[0].warningLamp)
        assertFalse(abs.dtcs[0].isHistory)
        assertTrue(abs.dtcs[1].isHistory)
        assertTrue(abs.complete)
        assertNull(abs.error)

        assertTrue("sterownik bez błędów", modules[1].dtcs.isEmpty() && modules[1].complete)

        val ecm = modules[2]
        assertEquals("Silnik (ECM)", ecm.name)
        assertEquals("P0133-00", ecm.dtcs.single().fullCode)
        assertEquals("Sonda lambda (bank 1, czujnik 1) – wolna reakcja", ecm.dtcs.single().description)

        val tcm = modules[3]
        assertEquals("Skrzynia biegów (TCM)", tcm.name)
        assertEquals("U0100-00", tcm.dtcs.single().fullCode)
    }

    @Test
    fun `odpowiedzi ze spacjami`() = runBlocking {
        val (_, _, scanner) = connected(saab(), spaces = true)
        val modules = scanner.scan()
        assertEquals(4, modules.size)
        assertEquals(2, modules[0].dtcs.size)
    }

    @Test
    fun `zajety sterownik - ponowna proba`() = runBlocking {
        val (_, _, scanner) = connected(
            mapOf(0x243 to FakeGmlanModule(dtcs = listOf(Triple(0x4035, 0x01, 0x02)), busyTimes = 2)),
        )
        val module = scanner.scan().single()
        assertNull(module.error)
        assertEquals("C0035-01", module.dtcs.single().fullCode)
    }

    @Test
    fun `sterownik bez uslugi odczytu bledow`() = runBlocking {
        val (_, _, scanner) = connected(mapOf(0x245 to FakeGmlanModule(supportsA9 = false)))
        val module = scanner.scan().single()
        assertEquals("Sterownik nie obsługuje tej usługi", module.error)
    }

    @Test
    fun `brak znacznika konca listy`() = runBlocking {
        val (_, _, scanner) = connected(
            mapOf(0x243 to FakeGmlanModule(dtcs = listOf(Triple(0x4035, 0x01, 0x02)), endMarker = false)),
        )
        val module = scanner.scan().single()
        assertFalse(module.complete)
        assertEquals(1, module.dtcs.size)
    }

    @Test
    fun `po skanie dziala zwykle OBD`() = runBlocking {
        val (client, fake, scanner) = connected(saab())
        scanner.scan()

        assertTrue("adapter zresetowany po skanie", fake.received.lastIndexOf("ATZ") > fake.received.indexOf("ATCAF0"))
        assertTrue(fake.received.contains("ATSP6"))
        assertTrue(fake.autoFormat)
        assertFalse(fake.headersOn)
        assertEquals(1726.0, client.read(Pids.RPM)!!.value, 0.001)
    }

    @Test
    fun `skan pyta tylko adresy diagnostyczne`() = runBlocking {
        val (_, fake, scanner) = connected(saab())
        scanner.scan()
        val allowed = (0x241..0x25F).toSet() + (0x7E0..0x7E7).toSet()
        assertTrue(fake.rawHeaders.isNotEmpty())
        assertTrue(fake.rawHeaders.all { it in allowed })
    }

    @Test
    fun `postep skanu`() = runBlocking {
        val (_, _, scanner) = connected(saab())
        val events = mutableListOf<GmlanProgress>()
        scanner.scan { events += it }
        assertEquals(39, events.count { it is GmlanProgress.Probing })
        assertEquals(4, events.count { it is GmlanProgress.Reading })
    }

    @Test
    fun `brak sterownikow GMLAN`() = runBlocking {
        val (client, _, scanner) = connected(emptyMap())
        assertTrue(scanner.scan().isEmpty())
        assertNotNull(client.read(Pids.RPM))
    }

    @Test
    fun `skan tylko na CAN 500`() = runBlocking {
        val fake = FakeElm327(obd)
        val scanner = GmlanScanner(Elm327(fake), ObdProtocol.ISO_9141_2)
        try {
            scanner.scan()
            fail("powinien być wyjątek")
        } catch (e: ObdException) {
            assertTrue(e.message!!.contains("CAN 500"))
        }
        assertTrue("nic nie wysłano", fake.received.isEmpty())
    }

    @Test
    fun `filtry ATCF i ATCM`() {
        assertEquals(0x5E8 to 0x5FF, GmlanScanner.filterFor(listOf(0x7E8, 0x5E8)))
        assertEquals(0x640 to 0x7E0, GmlanScanner.PROBE_GROUP_GMLAN)
        assertEquals(0x7E8 to 0x7F8, GmlanScanner.PROBE_GROUP_OBD)
        val (f, m) = GmlanScanner.filterFor(listOf(0x641, 0x541))
        assertTrue((0x641 and m) == f && (0x541 and m) == f)
        assertFalse("zwykła ramka nie przechodzi", (0x1A0 and m) == f)
    }

    @Test
    fun `ramka z adaptera`() {
        val f = GmlanScanner.parseFrame("7E8 03 7F A9 78 00 00 00 00")!!
        assertEquals(0x7E8, f.id)
        assertEquals(8, f.data.size)
        assertEquals(0x78, f[3])
        assertNull(GmlanScanner.parseFrame("NO DATA"))
        assertNull(GmlanScanner.parseFrame("7E"))
    }

    @Test
    fun `zgodnosc auta`() {
        assertEquals(GmlanCompatibility.SUPPORTED, GmlanCompatibility.of(ObdProtocol.CAN_11_500, "YS3FB45S041012345"))
        assertEquals(GmlanCompatibility.SUPPORTED, GmlanCompatibility.of(ObdProtocol.CAN_11_500, "W0L0ZCF6941012345"))
        assertEquals(GmlanCompatibility.OTHER_MAKE, GmlanCompatibility.of(ObdProtocol.CAN_11_500, "WVWZZZ1KZAM012345"))
        assertEquals(GmlanCompatibility.NEEDS_CONFIRMATION, GmlanCompatibility.of(ObdProtocol.CAN_11_500, null))
        assertEquals(GmlanCompatibility.WRONG_PROTOCOL, GmlanCompatibility.of(ObdProtocol.ISO_9141_2, "YS3FB45S041012345"))
    }
}
