package com.example.toyotaobd.session

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {

    @Test
    fun `samples older than the window are dropped`() {
        val history = listOf(Sample(0, 1.0), Sample(1_000, 2.0), Sample(2_000, 3.0))
        assertEquals(
            listOf(Sample(1_000, 2.0), Sample(2_000, 3.0), Sample(2_500, 4.0)),
            history.appendWithin(Sample(2_500, 4.0), windowMs = 1_500),
        )
    }

    @Test
    fun `everything is dropped after a long pause`() {
        val history = listOf(Sample(0, 1.0), Sample(1_000, 2.0))
        assertEquals(listOf(Sample(60_000, 3.0)), history.appendWithin(Sample(60_000, 3.0), windowMs = 1_500))
        assertEquals(listOf(Sample(5, 1.0)), emptyList<Sample>().appendWithin(Sample(5, 1.0), windowMs = 1_500))
    }

    @Test
    fun `the oldest samples are dropped beyond the count cap`() {
        val history = List(5) { Sample(it * 1_000L, it.toDouble()) }
        assertEquals(
            listOf(Sample(3_000, 3.0), Sample(4_000, 4.0), Sample(5_000, 5.0)),
            history.appendWithin(Sample(5_000, 5.0), windowMs = 60_000, maxSamples = 3),
        )
        assertEquals(listOf(Sample(5_000, 5.0)), history.appendWithin(Sample(5_000, 5.0), windowMs = 60_000, maxSamples = 0))
    }

    @Test
    fun `history is split where readings are too far apart`() {
        val history = listOf(Sample(0, 1.0), Sample(1_000, 2.0), Sample(20_000, 3.0), Sample(21_000, 4.0), Sample(40_000, 5.0))
        assertEquals(
            listOf(
                listOf(Sample(0, 1.0), Sample(1_000, 2.0)),
                listOf(Sample(20_000, 3.0), Sample(21_000, 4.0)),
                listOf(Sample(40_000, 5.0)),
            ),
            history.segments(maxGapMs = 15_000),
        )
        assertEquals(listOf(history.take(2)), history.take(2).segments(maxGapMs = 1_000))
        assertEquals(emptyList<List<Sample>>(), emptyList<Sample>().segments(maxGapMs = 1_000))
    }
}
