package com.example.toyotaobd.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartScaleTest {

    @Test
    fun `bounds are rounded to clean ticks`() {
        val scale = ChartScale.of(650.0, 3_187.0, 0.0..7_000.0)
        assertEquals(listOf(0.0, 2_000.0, 4_000.0), scale.ticks)
        assertEquals("2000", scale.label(2_000.0))
    }

    @Test
    fun `steady value is not magnified and stays within the usual range`() {
        // Moteur thermique coupé : 0 tr/min, pas de graduation négative.
        assertEquals(ChartScale(0.0, 1_000.0, 500.0), ChartScale.of(0.0, 0.0, 0.0..7_000.0))
        // Liquide stable à 86 °C : au moins 12 °C d'amplitude (dixième de la plage).
        assertEquals(ChartScale(80.0, 100.0, 10.0), ChartScale.of(85.0, 87.0, 0.0..120.0))
    }

    @Test
    fun `decimal steps keep their decimals`() {
        val scale = ChartScale.of(14.1, 14.2, 10.0..16.0)
        assertEquals(0.5, scale.step, 1e-9)
        // Point décimal, indépendant de la langue (String.fmt).
        assertEquals("13.5", scale.label(13.5))
    }

    @Test
    fun `negative values around zero`() {
        assertEquals(ChartScale(-5.0, 5.0, 5.0), ChartScale.of(-3.1, 4.5, -25.0..25.0))
        assertEquals(0f, ChartScale(-5.0, 5.0, 5.0).fraction(-12.0))
    }
}
