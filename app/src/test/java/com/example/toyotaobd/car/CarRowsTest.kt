package com.example.toyotaobd.car

import com.example.toyotaobd.data.CardStyle
import com.example.toyotaobd.data.DashboardCard
import com.example.toyotaobd.data.DashboardLayout
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.ToyotaHybrid
import com.example.toyotaobd.session.LiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRowsTest {

    private val live = LiveState(
        pids = listOf(Pids.RPM, Pids.SPEED, Pids.HYBRID_BATTERY, ToyotaHybrid.HV_POWER, Pids.COOLANT_TEMP),
        values = mapOf(Pids.RPM.code to 1980.0, Pids.SPEED.code to null, Pids.COOLANT_TEMP.code to 82.0),
    )

    @Test
    fun `rows cover every section of the phone dashboard and show value, n-d or waiting`() {
        assertEquals(
            listOf(
                CarRow(Pids.RPM.code, "Régime moteur", "1980 tr/min", CardStyle.GAUGE),
                CarRow(Pids.SPEED.code, "Vitesse", "n/d", CardStyle.VALUE), // lue, sans réponse
                CarRow(Pids.HYBRID_BATTERY.code, "Charge batterie hybride", "…", CardStyle.GAUGE), // pas encore lue
                CarRow(ToyotaHybrid.HV_POWER.code, "Puissance batterie HV", "…", CardStyle.VALUE),
                CarRow(Pids.COOLANT_TEMP.code, "Liquide de refroidissement", "82 °C", CardStyle.GAUGE), // infos thermiques, à la suite
            ),
            carRows(DashboardLayout.DEFAULT, live),
        )
    }

    @Test
    fun `user order and hidden cards apply, sections stay in order`() {
        val layout = DashboardLayout(
            listOf(
                DashboardCard(Pids.COOLANT_TEMP.code, CardStyle.GAUGE),
                DashboardCard(ToyotaHybrid.HV_POWER.code, CardStyle.LINE),
                DashboardCard(Pids.RPM.code, CardStyle.VALUE),
                DashboardCard(Pids.SPEED.code, CardStyle.VALUE),
            ),
        )
        assertEquals(
            listOf(ToyotaHybrid.HV_POWER.code, Pids.RPM.code, Pids.SPEED.code, Pids.COOLANT_TEMP.code),
            carRows(layout, live).map { it.code },
        )
        assertEquals(emptyList<CarRow>(), carRows(DashboardLayout(emptyList()), live))
    }

    @Test
    fun `cards take the style of the default dashboard, or their own outside of it`() {
        val custom = Pid(0x1F00, "Valeur constructeur", "", 1, range = 0.0..1.0)
        val layout = DashboardLayout(
            listOf(
                DashboardCard(Pids.RPM.code, CardStyle.LINE),
                DashboardCard(Pids.SPEED.code, CardStyle.GAUGE),
                DashboardCard(custom.code, CardStyle.LINE),
            ),
        )
        assertEquals(
            listOf(CardStyle.GAUGE, CardStyle.VALUE, CardStyle.LINE),
            carRows(layout, live.copy(pids = live.pids + custom)).map { it.style },
        )
    }

    @Test
    fun `a section keeps only its own cards`() {
        assertEquals(
            listOf(Pids.RPM.code, Pids.SPEED.code, Pids.HYBRID_BATTERY.code, ToyotaHybrid.HV_POWER.code),
            carRows(DashboardLayout.DEFAULT, live, DashboardSection.MAIN).map { it.code },
        )
        assertEquals(listOf("82 °C"), carRows(DashboardLayout.DEFAULT, live, DashboardSection.ENGINE).map { it.text })
        assertEquals(emptyList<CarRow>(), carRows(DashboardLayout.DEFAULT, live, DashboardSection.HYBRID))
    }

    @Test
    fun `one card fills the area, two are stacked, three or four form a 2x2 grid`() {
        // Zone utile de 1920 × 720 px sous les onglets, écran à 2 px par dp (marges 32 px), ligne de 50 px.
        fun grid(cells: Int) = carGrid(cells, left = 0, top = 100, right = 1920, bottom = 820, density = 2f, lineHeight = 50f)
        assertEquals(listOf(CarFrame(left = 32, right = 1888, chartTop = 132, chartBottom = 722, textBottom = 788)), grid(1))
        assertEquals(
            listOf(CarFrame(32, 1888, 132, 378, 444), CarFrame(32, 1888, 476, 722, 788)),
            grid(2),
        )
        val four = listOf(
            CarFrame(32, 944, 132, 378, 444), CarFrame(976, 1888, 132, 378, 444),
            CarFrame(32, 944, 476, 722, 788), CarFrame(976, 1888, 476, 722, 788),
        )
        assertEquals(four, grid(4))
        assertEquals(four.take(3), grid(3)) // même grille : la dernière page d'une section ne se réarrange pas
        assertEquals(four, grid(9))
        assertEquals(emptyList<CarFrame>(), grid(0))
    }
}
