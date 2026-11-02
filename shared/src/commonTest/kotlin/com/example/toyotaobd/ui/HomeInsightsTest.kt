package com.example.toyotaobd.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `hybrid battery without any indicator returns null`() {
        assertNull(computeHybridBatteryHealth(null, null, null, null, null))
    }

    @Test
    fun `lexus ux 250h real readings score 100`() {
        // Trames réelles UX 250h 2023 (ToyotaHybridTest) : écart éléments 0,020 V, batterie à 14,4 °C.
        val report = computeHybridBatteryHealth(cellSpread = 0.020, blockSpread = null, deltaSoc = null, resistanceMax = null, tempMax = 14.4)
        assertEquals(100, report?.score)
        assertEquals(HealthSeverity.INFO, report?.top?.severity)
    }

    @Test
    fun `disbalanced lithium cells lower the score`() {
        // Au-delà de 0,12 V d'écart entre éléments : critique.
        val report = computeHybridBatteryHealth(cellSpread = 0.13, blockSpread = null, deltaSoc = null, resistanceMax = null, tempMax = null)
        assertEquals(50, report?.score)
        assertEquals(HealthSeverity.CRITICAL, report?.top?.severity)
        // 0,06 V : avertissement léger (transitoire en forte charge).
        assertEquals(90, computeHybridBatteryHealth(cellSpread = 0.06, null, null, null, null)?.score)
    }

    @Test
    fun `nimh block spread delta soc and resistance stack`() {
        // -50 (écart blocs > 0,9 V) -25 (delta SOC > 10 %) -40 (résistance > 50 mΩ) -25 (temp > 60 °C) → 0.
        val report = computeHybridBatteryHealth(
            cellSpread = null, blockSpread = 1.1, deltaSoc = 15.0, resistanceMax = 55.0, tempMax = 62.0,
        )
        assertEquals(0, report?.score)
        assertEquals(HealthSeverity.CRITICAL, report?.top?.severity)
    }

    @Test
    fun `hot hybrid battery is flagged only past 50 degrees`() {
        // 45 °C en été avec ventilateur : normal, aucune pénalité.
        assertEquals(100, computeHybridBatteryHealth(null, null, null, null, 45.0)?.score)
        val warning = computeHybridBatteryHealth(null, null, null, null, 52.0)
        assertEquals(90, warning?.score)
        assertEquals(HealthSeverity.WARNING, warning?.top?.severity)
        val critical = computeHybridBatteryHealth(null, null, null, null, 61.0)
        assertEquals(75, critical?.score)
        assertEquals(HealthSeverity.CRITICAL, critical?.top?.severity)
    }
}
