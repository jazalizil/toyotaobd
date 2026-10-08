package com.example.toyotaobd.data

import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.ToyotaHybrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutTest {

    private val layout = DashboardLayout(
        cards = listOf(
            DashboardCard(0x0C, CardStyle.LINE, wide = true),
            DashboardCard(0x5C, CardStyle.GAUGE),
            DashboardCard(0x0D, CardStyle.VALUE),
            DashboardCard(0x05, CardStyle.GAUGE),
        ),
        windowMinutes = 15,
    )

    @Test
    fun `default layout shows every dashboard value`() {
        assertEquals(Pids.DASHBOARD.map { it.code }, DashboardLayout.DEFAULT.cards.map { it.pid })
        assertEquals(CardStyle.LINE, DashboardLayout.DEFAULT.card(Pids.FUEL_RATE.code)?.style)
        assertEquals(CardStyle.GAUGE, DashboardLayout.DEFAULT.card(ToyotaHybrid.STEERING_ANGLE.code)?.style) // volant animé
    }

    @Test
    fun `every value has a section and the default layout follows the section order`() {
        val sections = Pids.DASHBOARD.map { DashboardSection.of(it.code) }
        assertEquals(sections.sorted(), sections)
        assertEquals(DashboardSection.MAIN, DashboardSection.of(Pids.SPEED.code))
        assertEquals(DashboardSection.MAIN, DashboardSection.of(Pids.HYBRID_BATTERY.code))
        assertEquals(DashboardSection.MAIN, DashboardSection.of(ToyotaHybrid.STEERING_ANGLE.code))
        assertEquals(DashboardSection.MAIN, DashboardSection.of(ToyotaHybrid.MOTOR_RPM.code)) // placé dans les infos principales
        assertEquals(DashboardSection.MAIN, DashboardSection.of(Pids.FUEL_ECONOMY.code))
        assertEquals(DashboardSection.HYBRID, DashboardSection.of(ToyotaHybrid.HV_CURRENT.code))
        assertEquals(DashboardSection.ENGINE, DashboardSection.of(Pids.COOLANT_TEMP.code))
        assertEquals(DashboardSection.ENGINE, DashboardSection.of(0x01)) // PID du mode 01 hors tableau de bord
    }

    @Test
    fun `tabs are the sections the vehicle provides, in order`() {
        assertEquals(
            DashboardSection.entries,
            DashboardSection.present(listOf(Pids.COOLANT_TEMP.code, ToyotaHybrid.HV_CURRENT.code, Pids.SPEED.code)),
        )
        // Voiture à essence : pas d'onglet « Hybrides ».
        assertEquals(
            listOf(DashboardSection.MAIN, DashboardSection.ENGINE),
            DashboardSection.present(listOf(Pids.RPM.code, Pids.MAF.code)),
        )
        assertEquals(emptyList<DashboardSection>(), DashboardSection.present(emptyList()))
    }

    @Test
    fun `cards are grouped by section, in the saved order within each`() {
        val saved = DashboardLayout(
            listOf(
                DashboardCard(Pids.COOLANT_TEMP.code, CardStyle.GAUGE),
                DashboardCard(ToyotaHybrid.HV_POWER.code, CardStyle.LINE),
                DashboardCard(ToyotaHybrid.HV_CURRENT.code, CardStyle.VALUE),
                DashboardCard(Pids.SPEED.code, CardStyle.VALUE),
                DashboardCard(Pids.RPM.code, CardStyle.LINE),
            ),
        )
        val all = saved.cards.map { it.pid }.toSet()
        assertEquals(
            mapOf(
                DashboardSection.MAIN to listOf(ToyotaHybrid.HV_POWER.code, Pids.SPEED.code, Pids.RPM.code),
                DashboardSection.HYBRID to listOf(ToyotaHybrid.HV_CURRENT.code),
                DashboardSection.ENGINE to listOf(Pids.COOLANT_TEMP.code),
            ),
            saved.bySection(all).mapValues { (_, cards) -> cards.map { it.pid } },
        )
        // Véhicule non hybride : pas de section hybride.
        assertEquals(
            listOf(DashboardSection.MAIN, DashboardSection.ENGINE),
            saved.bySection(setOf(Pids.RPM.code, Pids.COOLANT_TEMP.code)).keys.toList(),
        )
        // Monter la vitesse ne la fait pas passer devant une carte d'une autre section.
        val mainPids = setOf(ToyotaHybrid.HV_POWER.code, Pids.SPEED.code, Pids.RPM.code)
        val moved = saved.move(Pids.SPEED.code, -1, mainPids)
        assertEquals(listOf(Pids.SPEED.code, ToyotaHybrid.HV_POWER.code), moved.bySection(all).getValue(DashboardSection.MAIN).take(2).map { it.pid })
        assertEquals(Pids.COOLANT_TEMP.code, moved.cards.first().pid)
    }

    @Test
    fun `encoding round-trips`() {
        assertEquals("4|15|0C:LW,5C:G,0D:V,05:G", layout.encode())
        assertEquals(layout, DashboardLayout.decode(layout.encode()))
        assertEquals(DashboardLayout.DEFAULT, DashboardLayout.decode(DashboardLayout.DEFAULT.encode()))
    }

    @Test
    fun `unreadable text is rejected`() {
        assertNull(DashboardLayout.decode(null))
        assertNull(DashboardLayout.decode("5|5|0C:L"))
        assertNull(DashboardLayout.decode("X|5|0C:L"))
        assertNull(DashboardLayout.decode("3|7|0C:L"))
        assertNull(DashboardLayout.decode("3|5|0C:X"))
        assertNull(DashboardLayout.decode("3|5|ZZ:L"))
        assertEquals(DashboardLayout(emptyList(), 5), DashboardLayout.decode("4|5|"))
    }

    @Test
    fun `a format 3 layout gains the fuel economy card`() {
        // Ajoutée avec son style par défaut.
        assertEquals(
            listOf(DashboardCard(0x0C, CardStyle.LINE), DashboardLayout.DEFAULT.card(Pids.FUEL_ECONOMY.code)),
            DashboardLayout.decode("3|5|0C:L")!!.cards,
        )
    }

    @Test
    fun `hybrid values keep their code above 0xFF`() {
        val hybrid = DashboardLayout(listOf(DashboardCard(0x101, CardStyle.GAUGE), DashboardCard(0x5B, CardStyle.VALUE)))
        assertEquals("4|5|101:G,5B:V", hybrid.encode())
        assertEquals(hybrid, DashboardLayout.decode(hybrid.encode()))
    }

    @Test
    fun `an older layout gains the newer cards, a hidden one stays hidden afterwards`() {
        val migrated = DashboardLayout.decode("1|15|0C:LW,0D:V")!!
        assertEquals(15, migrated.windowMinutes)
        assertEquals(listOf(0x0C, 0x0D), migrated.cards.take(2).map { it.pid })
        // Tout ce qui est apparu depuis : charge 5B, valeurs constructeur (hybride, volant), consommation.
        assertEquals(
            Pids.DASHBOARD.filter { it == Pids.HYBRID_BATTERY || it.request != null || it == Pids.FUEL_ECONOMY }.map { it.code }.toSet(),
            migrated.cards.drop(2).map { it.pid }.toSet(),
        )
        // Y compris les valeurs hybrides rangées dans les infos principales.
        val moved = listOf(Pids.HYBRID_BATTERY, ToyotaHybrid.HV_POWER, ToyotaHybrid.MOTOR_RPM, ToyotaHybrid.MOTOR_TORQUE, ToyotaHybrid.REGEN_TORQUE)
        assertTrue(moved.all { migrated.card(it.code) == DashboardLayout.DEFAULT.card(it.code) })
        // Format 2 : seuls l'angle du volant et le ventilateur HSD 3 sont ajoutés.
        assertEquals(
            listOf(0x0C, ToyotaHybrid.HSD3_BATTERY_FAN.code, ToyotaHybrid.STEERING_ANGLE.code, Pids.FUEL_ECONOMY.code),
            DashboardLayout.decode("2|5|0C:L")!!.cards.map { it.pid },
        )
        assertEquals(DashboardLayout.DEFAULT.card(ToyotaHybrid.HV_CURRENT.code), migrated.card(ToyotaHybrid.HV_CURRENT.code))
        // Une valeur hybride déjà présente garde sa place et son style.
        assertEquals(DashboardCard(0x5B, CardStyle.LINE), DashboardLayout.decode("1|5|5B:L")!!.cards.first())

        val hidden = migrated.remove(ToyotaHybrid.HV_CURRENT.code)
        assertEquals(hidden, DashboardLayout.decode(hidden.encode()))
    }

    @Test
    fun `move skips cards hidden for this vehicle`() {
        // Huile moteur (5C) non supportée : la vitesse passe devant le régime sans la déplacer.
        val shown = setOf(0x0C, 0x0D, 0x05)
        val moved = layout.move(0x0D, -1, shown)
        assertEquals(listOf(0x0D, 0x5C, 0x0C, 0x05), moved.cards.map { it.pid })
        assertEquals(layout, layout.move(0x0C, -1, shown))
        assertEquals(layout, layout.move(0x05, 1, shown))
    }

    @Test
    fun `cards can be hidden, shown again and restyled`() {
        val edited = layout.remove(0x0D).add(0x0D).update(0x0D) { it.copy(style = CardStyle.LINE, wide = true) }
        assertEquals(DashboardCard(0x0D, CardStyle.LINE, wide = true), edited.cards.last())
        assertEquals(layout, layout.add(0x0C))
    }
}
