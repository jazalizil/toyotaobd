package com.example.toyotaobd.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeTest {

    @Test
    fun `gauge fills from the start of the range, bounded to it`() {
        assertEquals(0f..0.25f, gaugeFill(0.0..120.0, 30.0))
        assertEquals(0f..1f, gaugeFill(0.0..120.0, 150.0))
        assertEquals(0f..0f, gaugeFill(0.0..120.0, -10.0))
    }

    @Test
    fun `range on both sides of zero fills from zero`() {
        // Correction de mélange de -25 à +25 % : -12,5 % remplit le quart gauche du milieu.
        assertEquals(0.25f..0.5f, gaugeFill(-25.0..25.0, -12.5))
        assertEquals(0.5f..0.75f, gaugeFill(-25.0..25.0, 12.5))
    }
}
