package com.example.toyotaobd.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {

    @Test
    fun `statistics of regular readings`() {
        // 10, 20, 30, 20 toutes les secondes.
        val samples = listOf(Sample(0, 10.0), Sample(1_000, 20.0), Sample(2_000, 30.0), Sample(3_000, 20.0))
        val stats = samples.stats(maxGapMs = 15_000)!!
        assertEquals(4, stats.count)
        assertEquals(Sample(0, 10.0), stats.min)
        assertEquals(Sample(2_000, 30.0), stats.max)
        assertEquals(20.0, stats.mean, 1e-9) // (10 + 20 + 30) / 3 : la dernière lecture n'a pas encore de durée
        assertEquals(20.0, stats.median, 1e-9)
        assertEquals(7.0711, stats.standardDeviation, 1e-4)
        assertEquals(20.0, stats.amplitude, 1e-9)
        assertEquals(10.0, stats.variation, 1e-9)
        assertEquals(1_000L, stats.meanIntervalMs)
        assertEquals(3_000L, stats.durationMs)
    }

    @Test
    fun `the mean is weighted by time and ignores gaps`() {
        // 10 pendant 3 s, 40 pendant 1 s, puis un trou de 60 s (liaison coupée) avant 100.
        val samples = listOf(Sample(0, 10.0), Sample(3_000, 40.0), Sample(4_000, 40.0), Sample(64_000, 100.0))
        val stats = samples.stats(maxGapMs = 15_000)!!
        assertEquals((10.0 * 3 + 40.0 * 1) / 4, stats.mean, 1e-9)
        assertEquals(2_000L, stats.meanIntervalMs) // (3 s + 1 s) / 2, le trou exclu
        assertEquals(Sample(64_000, 100.0), stats.max)
        // Plusieurs lectures au minimum : la plus récente est retenue.
        assertEquals(Sample(4_000, 40.0), listOf(Sample(0, 50.0), Sample(1_000, 40.0), Sample(4_000, 40.0)).stats(15_000)!!.min)
    }

    @Test
    fun `a single reading or none`() {
        val single = listOf(Sample(5, 42.0)).stats(maxGapMs = 15_000)!!
        assertEquals(42.0, single.mean, 1e-9)
        assertEquals(0.0, single.standardDeviation, 1e-9)
        assertNull(single.meanIntervalMs)
        assertNull(emptyList<Sample>().stats(maxGapMs = 15_000))
    }
}
