package com.example.toyotaobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseParserTest {

    @Test
    fun `strips noise, echo and NUL bytes`() {
        val raw = "0100\r\u0000SEARCHING...\rBUS INIT: ...OK\r41 00 BE 3F A8 13\r\r"
        assertEquals(listOf("41 00 BE 3F A8 13"), ResponseParser.lines(raw, echo = "0100"))
    }

    @Test
    fun `headers off single frame from two ECUs`() {
        val messages = ResponseParser.parse("41 00 BE 3F A8 13\r41 00 80 00 00 01\r", HeaderFormat.NONE)
        assertEquals(
            listOf(
                EcuMessage("", listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13)),
                EcuMessage("", listOf(0x41, 0x00, 0x80, 0x00, 0x00, 0x01)),
            ),
            messages,
        )
    }

    @Test
    fun `headers off multi-frame VIN is reassembled`() {
        val raw = "014\r0: 49 02 01 4A 54 44\r1: 4B 42 32 30 55 39 33\r2: 30 31 32 33 34 35 36\r"
        val messages = ResponseParser.parse(raw, HeaderFormat.NONE)
        assertEquals(1, messages.size)
        assertEquals(20, messages[0].data.size)
        assertEquals(listOf(0x49, 0x02, 0x01), messages[0].data.take(3))
    }

    @Test
    fun `CAN 11 bit headers separate ECUs and drop padding`() {
        val raw = "7E8 06 41 00 BE 3F A8 13 00\r7EA 06 41 00 80 00 00 01 AA\r"
        val messages = ResponseParser.parse(raw, HeaderFormat.CAN_11)
        assertEquals(
            listOf(
                EcuMessage("7E8", listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13)),
                EcuMessage("7EA", listOf(0x41, 0x00, 0x80, 0x00, 0x00, 0x01)),
            ),
            messages,
        )
    }

    @Test
    fun `CAN 11 bit without spaces is parsed too`() {
        val messages = ResponseParser.parse("7E804410C1AF8000000\r", HeaderFormat.CAN_11)
        assertEquals(listOf(EcuMessage("7E8", listOf(0x41, 0x0C, 0x1A, 0xF8))), messages)
    }

    @Test
    fun `CAN ISO-TP multi-frame interleaved between two ECUs`() {
        val raw = listOf(
            "7E8 10 0B 61 01 01 02 03 04",
            "7EA 10 09 61 01 AA BB CC DD",
            "7E8 21 05 06 07 08 09 00 00",
            "7EA 21 EE FF 11 00 00 00 00",
        ).joinToString("\r")
        val messages = ResponseParser.parse(raw, HeaderFormat.CAN_11)
        assertEquals(
            listOf(
                EcuMessage("7E8", listOf(0x61, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 9)),
                EcuMessage("7EA", listOf(0x61, 0x01, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x11)),
            ),
            messages,
        )
    }

    @Test
    fun `CAN 29 bit header`() {
        val messages = ResponseParser.parse("18 DA F1 10 03 41 0D 32\r", HeaderFormat.CAN_29)
        assertEquals(listOf(EcuMessage("18DAF110", listOf(0x41, 0x0D, 0x32))), messages)
    }

    @Test
    fun `ISO 9141 headers and checksum are removed`() {
        val messages = ResponseParser.parse("48 6B 10 41 0D 32 F3\r", HeaderFormat.LEGACY)
        assertEquals(listOf(EcuMessage("10", listOf(0x41, 0x0D, 0x32))), messages)
    }

    @Test
    fun `error detection`() {
        assertEquals("NO DATA", ResponseParser.errorOf("NO DATA\r"))
        assertEquals("UNABLE TO CONNECT", ResponseParser.errorOf("SEARCHING...\rUNABLE TO CONNECT\r"))
        assertEquals("Commande non reconnue (?)", ResponseParser.errorOf("?\r"))
        assertEquals("CAN ERROR", ResponseParser.errorOf("CAN ERROR\r"))
        assertEquals("BUS INIT: ...ERROR", ResponseParser.errorOf("BUS INIT: ...ERROR\r"))
        assertNull(ResponseParser.errorOf("41 0D 00\r"))
    }

    @Test
    fun `error lines are not parsed as data`() {
        assertEquals(emptyList<EcuMessage>(), ResponseParser.parse("NO DATA\r", HeaderFormat.CAN_11))
        assertEquals(emptyList<EcuMessage>(), ResponseParser.parse("STOPPED\r", HeaderFormat.NONE))
    }
}
