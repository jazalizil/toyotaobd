package com.example.toyotaobd.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `flag comma groups thousands for d`() {
        // Régression : le drapeau « , » n'était jamais consommé (length du StringBuilder),
        // ce qui levait « Gabarit inconnu : %," depuis StatsCard (« Lectures »).
        assertEquals("1 234 567", "%,d".fmt(1_234_567))
        assertEquals("42", "%,d".fmt(42))
        assertEquals("-1 234", "%,d".fmt(-1_234))
    }

    @Test
    fun `zero flag and width pad integers`() {
        assertEquals("0C", "%02X".fmt(0x0C))
        assertEquals("1f", "%02x".fmt(0x1F))
        assertEquals("A=0C", "%c=%02X".fmt('A', 0x0C))
        assertEquals("05", "%02d".fmt(5))
        assertEquals("-05", "%03d".fmt(-5))
    }

    @Test
    fun `precision controls decimals`() {
        assertEquals("3.1", "%.1f".fmt(3.14))
        assertEquals("3", "%.0f".fmt(3.14))
        assertEquals("14.20", "%.2f".fmt(14.2))
    }

    @Test
    fun `literal percent and multi arg patterns`() {
        assertEquals("100%", "%.0f%%".fmt(100.0))
        assertEquals("12 min 05 s", "%d min %02d s".fmt(12, 5))
    }
}
