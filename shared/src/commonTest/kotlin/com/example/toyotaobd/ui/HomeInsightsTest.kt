package com.example.toyotaobd.ui

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class HomeInsightsTest {

    @Test
    fun `no issue gives a perfect score`() {
        val report = computeHealth(coolantTempC = 90.0, voltage = 14.2, fuelLevelPct = 60.0, dtcCount = 0, dtcKnown = true)
        assertEquals(100, report.score)
        assertEquals(1, report.issues.size)
        assertEquals(HealthSeverity.INFO, report.top?.severity)
    }

    @Test
    fun `stored dtc are critical and lower the score`() {
        val report = computeHealth(null, 14.2, 60.0, dtcCount = 2, dtcKnown = true)
        assertEquals(80, report.score)
        assertEquals(HealthSeverity.CRITICAL, report.top?.severity)
        assertTrue(report.top!!.message.contains("2 codes"))
    }

    @Test
    fun `dtc are ignored until codes were read`() {
        val report = computeHealth(null, 14.2, 60.0, dtcCount = 5, dtcKnown = false)
        assertEquals(100, report.score)
    }

    @Test
    fun `hot coolant is flagged`() {
        assertEquals(HealthSeverity.WARNING, computeHealth(102.0, 14.2, 60.0, 0, true).top?.severity)
        assertEquals(HealthSeverity.CRITICAL, computeHealth(115.0, 14.2, 60.0, 0, true).top?.severity)
    }

    @Test
    fun `low battery voltage and low fuel are warnings`() {
        val report = computeHealth(90.0, 12.0, 8.0, 0, true)
        assertEquals(75, report.score)
        assertEquals(2, report.issues.size)
        assertTrue(report.issues.all { it.severity == HealthSeverity.WARNING })
    }

    @Test
    fun `stacked issues lower the score but stay bounded`() {
        // -40 (codes) -25 (liquide très chaud) -15 (tension) -10 (carburant) = 90 → score 10, jamais négatif.
        val report = computeHealth(120.0, 11.5, 2.0, dtcCount = 9, dtcKnown = true)
        assertEquals(10, report.score)
        assertEquals(HealthSeverity.CRITICAL, report.top?.severity)
    }
}
