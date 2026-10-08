package com.example.toyotaobd.car

import com.example.toyotaobd.data.CardStyle
import com.example.toyotaobd.data.DashboardCard
import com.example.toyotaobd.data.DashboardLayout
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.ToyotaHybrid
import com.example.toyotaobd.session.LiveState
import com.example.toyotaobd.session.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRowsTest {

    private val live = LiveState(
        pids = listOf(Pids.RPM, Pids.SPEED, Pids.HYBRID_BATTERY, ToyotaHybrid.HV_POWER, Pids.COOLANT_TEMP),
        values = mapOf(Pids.RPM.code to 1980.0, Pids.SPEED.code to null, Pids.COOLANT_TEMP.code to 82.0),
    )

    private fun rows(layout: DashboardLayout, section: DashboardSection, max: Int = 6) = carRows(layout, live, section, max)

    @Test
    fun `rows follow the phone dashboard and show value, n-d or waiting`() {
        assertEquals(
            listOf(
                CarRow(Pids.RPM.code, "Régime moteur", "1980 tr/min"),
                CarRow(Pids.SPEED.code, "Vitesse", "n/d"), // lue, sans réponse
                CarRow(Pids.HYBRID_BATTERY.code, "Charge batterie hybride", "…"), // pas encore lue
                CarRow(ToyotaHybrid.HV_POWER.code, "Puissance batterie HV", "…"),
            ),
            rows(DashboardLayout.DEFAULT, DashboardSection.MAIN),
        )
        assertEquals(listOf("82 °C"), rows(DashboardLayout.DEFAULT, DashboardSection.ENGINE).map { it.text })
        assertEquals(emptyList<CarRow>(), rows(DashboardLayout.DEFAULT, DashboardSection.HYBRID))
    }

    @Test
    fun `user order and hidden cards apply, within the car list limit`() {
        val layout = DashboardLayout(
            listOf(
                DashboardCard(ToyotaHybrid.HV_POWER.code, CardStyle.LINE),
                DashboardCard(Pids.RPM.code, CardStyle.VALUE),
                DashboardCard(Pids.SPEED.code, CardStyle.VALUE),
            ),
        )
        assertEquals(
            listOf(ToyotaHybrid.HV_POWER.code, Pids.RPM.code, Pids.SPEED.code),
            rows(layout, DashboardSection.MAIN).map { it.code },
        )
        assertEquals(listOf(ToyotaHybrid.HV_POWER.code), rows(layout, DashboardSection.MAIN, max = 1).map { it.code })
        assertEquals(emptyList<CarRow>(), rows(layout, DashboardSection.MAIN, max = 0))
    }

    @Test
    fun `carousel rows have the same titles for every value, with or without readings`() {
        val samples = listOf(Sample(0, 750.0), Sample(1_000, 2_000.0), Sample(2_000, 1_000.0))
        for (pid in Pids.DASHBOARD) {
            for (history in listOf(emptyList(), samples)) {
                val rows = carDetailRows(pid, live, history, windowMinutes = 5, position = 0, count = 3)
                assertEquals(pid.name, CAR_DETAIL_TITLES, rows.map { it.title })
            }
        }
    }

    @Test
    fun `carousel rows show the value, its name and position, then dated extremes and the mean`() {
        val samples = listOf(Sample(0, 750.0), Sample(1_000, 2_000.0), Sample(3_000, 1_000.0))
        val rows = carDetailRows(Pids.RPM, live, samples, windowMinutes = 5, position = 1, count = 7)
        assertEquals(listOf("1980 tr/min", "Régime moteur · 2/7"), rows[0].texts)
        assertEquals(listOf("750 tr/min · il y a 3 s"), rows[1].texts)
        assertEquals(listOf("2000 tr/min · il y a 2 s"), rows[2].texts)
        assertEquals(listOf("1583 tr/min", "sur 5 min"), rows[3].texts) // (750 × 1 s + 2000 × 2 s) / 3 s
        // Pas encore de lecture : la ligne reste, avec « … ».
        val empty = carDetailRows(Pids.HYBRID_BATTERY, live, emptyList(), windowMinutes = 5, position = 0, count = 1)
        assertEquals(listOf("…", "Charge batterie hybride · 1/1"), empty[0].texts)
        assertEquals(listOf("…"), empty[1].texts)
    }

    @Test
    fun `carousel image is sized in dp and capped for the host link`() {
        assertEquals(360 to 240, carImageSize(density = 1f))
        val (width, height) = carImageSize(density = 2.5f)
        assertTrue(width * height <= 150_000)
        assertEquals(1.5, width / height.toDouble(), 0.01) // proportions gardées
    }
}
