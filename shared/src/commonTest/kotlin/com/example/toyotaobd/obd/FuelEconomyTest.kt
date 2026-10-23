package com.example.toyotaobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelEconomyTest {

    private val derivation = Pids.FUEL_ECONOMY.derivation!!

    private fun compute(vararg values: Pair<Pid, Double?>) = derivation.compute(values.associate { (pid, v) -> pid.code to v })

    @Test
    fun `litres per 100 km from the fuel rate PID`() {
        assertEquals(10.0, compute(Pids.SPEED to 60.0, Pids.FUEL_RATE to 6.0)!!, 1e-9)
        assertEquals(0.0, compute(Pids.SPEED to 50.0, Pids.FUEL_RATE to 0.0)!!, 1e-9) // hybride en électrique
    }

    @Test
    fun `without PID 5E the fuel rate is estimated from the air flow`() {
        // 10 g/s d'air : 10 × 3600 / (14,7 × 745) ≈ 3,287 L/h, à 50 km/h ≈ 6,57 L/100 km.
        assertEquals(3.287, Pids.fuelRateFromMaf(10.0), 0.001)
        assertEquals(6.574, compute(Pids.SPEED to 50.0, Pids.MAF to 10.0)!!, 0.001)
        // Le PID 5E l'emporte s'il est lu.
        assertEquals(10.0, compute(Pids.SPEED to 60.0, Pids.FUEL_RATE to 6.0, Pids.MAF to 10.0)!!, 1e-9)
    }

    @Test
    fun `no value when stopped or without readings`() {
        assertNull(compute(Pids.SPEED to 3.0, Pids.FUEL_RATE to 0.8)) // ralenti à l'arrêt
        assertNull(compute(Pids.SPEED to null, Pids.FUEL_RATE to 6.0))
        assertNull(compute(Pids.SPEED to 60.0))
    }

    @Test
    fun `available with the speed and a fuel or air flow`() {
        assertTrue(derivation.available(setOf(Pids.SPEED.code, Pids.FUEL_RATE.code)))
        assertTrue(derivation.available(setOf(Pids.SPEED.code, Pids.MAF.code))) // Lexus UX
        assertFalse(derivation.available(setOf(Pids.SPEED.code)))
        assertFalse(derivation.available(setOf(Pids.FUEL_RATE.code, Pids.MAF.code)))
    }
}
