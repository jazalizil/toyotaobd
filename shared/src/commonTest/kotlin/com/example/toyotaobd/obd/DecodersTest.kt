package com.example.toyotaobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodersTest {

    @Test
    fun `DTC byte decoding covers all four systems`() {
        assertEquals("P0133", Dtc.decode(0x01, 0x33))
        assertEquals("P0A80", Dtc.decode(0x0A, 0x80))
        assertEquals("P1349", Dtc.decode(0x13, 0x49))
        assertEquals("C0123", Dtc.decode(0x41, 0x23))
        assertEquals("B1234", Dtc.decode(0x92, 0x34))
        assertEquals("U0100", Dtc.decode(0xC1, 0x00))
    }

    @Test
    fun `CAN DTC response has a count byte`() {
        val messages = listOf(
            EcuMessage("7E8", listOf(0x43, 0x01, 0x01, 0x33)),
            EcuMessage("7EA", listOf(0x43, 0x02, 0x0A, 0x80, 0x01, 0x71)),
        )
        assertEquals(
            listOf(Dtc("P0133", "7E8"), Dtc("P0A80", "7EA"), Dtc("P0171", "7EA")),
            Dtc.parse(messages, DtcMode.STORED, isCan = true),
        )
    }

    @Test
    fun `legacy DTC response has no count byte and is zero padded`() {
        val messages = listOf(EcuMessage("10", listOf(0x43, 0x01, 0x33, 0x04, 0x20, 0x00, 0x00)))
        assertEquals(
            listOf(Dtc("P0133", "10"), Dtc("P0420", "10")),
            Dtc.parse(messages, DtcMode.STORED, isCan = false),
        )
    }

    @Test
    fun `pending mode only reads 47 responses`() {
        val messages = listOf(EcuMessage("7E8", listOf(0x43, 0x01, 0x01, 0x33)))
        assertEquals(emptyList<Dtc>(), Dtc.parse(messages, DtcMode.PENDING, isCan = true))
    }

    @Test
    fun `standard PID formulas`() {
        assertEquals(1726.0, Pids.RPM.decode(listOf(0x1A, 0xF8)), 0.001)
        assertEquals(50.0, Pids.COOLANT_TEMP.decode(listOf(90)), 0.001)
        assertEquals(-100.0, Pids.STFT_B1.decode(listOf(0)), 0.001)
        assertEquals(0.0, Pids.STFT_B1.decode(listOf(128)), 0.001)
        assertEquals(12.5, Pids.MODULE_VOLTAGE.decode(listOf(0x30, 0xD4)), 0.001)
        assertEquals(100.0, Pids.THROTTLE.decode(listOf(255)), 0.001)
    }

    @Test
    fun `supported PID bitmap`() {
        val supported = Pids.decodeSupported(0x00, listOf(0xBE, 0x3F, 0xA8, 0x13))
        assertTrue(0x01 in supported)
        assertFalse(0x02 in supported)
        assertTrue(0x0C in supported)
        assertTrue(0x0D in supported)
        assertTrue(0x20 in supported)
        assertEquals(setOf(0x21), Pids.decodeSupported(0x20, listOf(0x80, 0, 0, 0)))
    }

    @Test
    fun `monitor status`() {
        assertEquals(MonitorStatus(milOn = true, dtcCount = 3), MonitorStatus.decode(0x83))
        assertEquals(MonitorStatus(milOn = false, dtcCount = 0), MonitorStatus.decode(0x00))
    }

    @Test
    fun `formula evaluation`() {
        assertEquals(80.0, Formula("((A*256)+B)/80").evaluate(listOf(0x19, 0x00)), 0.001)
        assertEquals(50.0, Formula("a - 40").evaluate(listOf(90)), 0.001)
        assertEquals(-14.0, Formula("-(C+4)").evaluate(listOf(0, 0, 10)), 0.001)
        assertEquals(7.0, Formula("1+2*3").evaluate(emptyList()), 0.001)
        assertEquals(1.5, Formula("A/2").evaluate(listOf(3)), 0.001)
        assertEquals(3, Formula("((A*256)+C)/80").bytesNeeded)
    }

    @Test
    fun `formula errors`() {
        assertThrows(FormulaException::class.java) { Formula("") }
        assertThrows(FormulaException::class.java) { Formula("(A+1") }
        assertThrows(FormulaException::class.java) { Formula("AB") }
        assertThrows(FormulaException::class.java) { Formula("A % 2") }
        assertThrows(FormulaException::class.java) { Formula("B").evaluate(listOf(1)) }
        assertThrows(FormulaException::class.java) { Formula("A/B").evaluate(listOf(1, 0)) }
    }

    @Test
    fun `enhanced response strips the positive echo`() {
        val formula = Formula("((A*256)+B)/80")
        val reading = ObdClient.decodeEnhanced(listOf(0x62, 0x1E, 0x1C, 0x19, 0x00), listOf(0x22, 0x1E, 0x1C), formula)
        assertEquals(listOf(0x19, 0x00), reading.payload)
        assertEquals(80.0, reading.value!!, 0.001)

        val mode21 = ObdClient.decodeEnhanced(listOf(0x61, 0x82, 0x5A, 0x00, 0x64), listOf(0x21, 0x82), Formula("C-40"))
        assertEquals(60.0, mode21.value!!, 0.001)
    }

    @Test
    fun `enhanced negative response`() {
        val reading = ObdClient.decodeEnhanced(listOf(0x7F, 0x21, 0x31), listOf(0x21, 0x82), Formula("A"))
        assertNull(reading.value)
        assertTrue(reading.error!!.contains("0x31"))
    }

    @Test
    fun `custom PID validation`() {
        assertNull(Toyota.validate("7E1", "2182", "A-40"))
        assertNull(Toyota.validate("7e0", "221e1c", "((A*256)+B)/80"))
        assertTrue(Toyota.validate("7E", "2182", "A")!!.startsWith("En-tête"))
        assertTrue(Toyota.validate("7E0", "21", "A")!!.startsWith("Commande"))
        assertTrue(Toyota.validate("7E0", "2182", "A+")!!.startsWith("Formule"))
    }

    @Test
    fun `ECU addressing`() {
        assertEquals("7E9", Toyota.TRANSMISSION.responseId)
        assertEquals("7B8", Ecu("7B0", "ABS").responseId)
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.ISO_9141_2, ObdProtocol.fromDpn("3"))
    }
}
