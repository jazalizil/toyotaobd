package com.example.toyotaobd.obd

import com.example.toyotaobd.obd.ResponseParser.hexBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décodeurs hybrides face à des trames réelles (en-têtes activés) : Lexus UX 250h 2023 relevé par
 * OBDb (calculateurs 7D2 → 7DA et 7B0 → 7B8) et Toyota Auris hybride relevée dans ELM327-emulator (7E2 → 7EA).
 */
class ToyotaHybridTest {

    /** Données qui suivent l'écho, après réassemblage ISO-TP des [frames]. */
    private fun payload(command: String, vararg frames: String): List<Int> {
        val message = ResponseParser.parse(frames.joinToString("\r"), HeaderFormat.CAN_11, command).single()
        return ObdClient.payloadAfterEcho(message.data, command.hexBytes())!!
    }

    private fun Pid.of(payload: List<Int>): Double = decodeEnhanced(payload)!!

    @Test
    fun `TNGA battery voltage, signed current and power`() {
        val traction = payload("221F9A", "7DA1009621F9A0E0030", "7DA2100043100000000")
        assertEquals(192.0, ToyotaHybrid.HV_VOLTAGE.of(traction), 0.01)
        assertEquals(107.3, ToyotaHybrid.HV_CURRENT.of(traction), 0.01)
        assertEquals(20.6, ToyotaHybrid.HV_POWER.of(traction), 0.01)

        val charging = payload("221F9A", "7DA1009621F9A0E0035", "7DA21C0FFF400000000")
        assertEquals(215.0, ToyotaHybrid.HV_VOLTAGE.of(charging), 0.01)
        assertEquals(-1.2, ToyotaHybrid.HV_CURRENT.of(charging), 0.01)
    }

    @Test
    fun `TNGA motor, boost converter and charge`() {
        val motor = payload("2210A1", "7DA100C6210A17C777F", "7DA21EC03007FF00400")
        assertEquals(-905.0, ToyotaHybrid.MOTOR_RPM.of(motor), 0.01)
        assertEquals(-2.0, ToyotaHybrid.MOTOR_TORQUE.of(motor), 0.01)

        val boost = payload("2210B0", "7DA076210B001960259")
        assertEquals(203.0, ToyotaHybrid.VL.of(boost), 0.01)
        assertEquals(300.5, ToyotaHybrid.VH.of(boost), 0.01)

        assertEquals(28.6, ToyotaHybrid.SOC.of(payload("221F5B", "7DA04621F5B49")), 0.05)
        val socRange = payload("22106C", "7DA0662106C363936")
        assertEquals(28.5, ToyotaHybrid.SOC_MAX.of(socRange), 0.01)
        assertEquals(27.0, ToyotaHybrid.SOC_MIN.of(socRange), 0.01)
        assertEquals(-226.375, ToyotaHybrid.REGEN_TORQUE.of(payload("221069", "7DA0762106978ED78ED")), 0.001)
    }

    @Test
    fun `TNGA temperatures and fan`() {
        val battery = payload("221814", "7DA10096218143F3540", "7DA21733F6800000000")
        assertEquals(14.4, ToyotaHybrid.BATTERY_TEMP_MAX.of(battery), 0.05)
        assertEquals(13.2, ToyotaHybrid.BATTERY_TEMP_MIN.of(battery), 0.05)
        assertEquals(98.5, ToyotaHybrid.BATTERY_FAN.of(payload("221802", "7DA076218024203C503")), 0.01)
        assertEquals(76.0, ToyotaHybrid.TRANSAXLE_OIL.of(payload("221040", "7DA066210408B6074")), 0.01)
        assertEquals(51.0, ToyotaHybrid.INVERTER_COOLANT.of(payload("2210B2", "7DA046210B25B")), 0.01)
        assertEquals(15.0, ToyotaHybrid.MOTOR_INVERTER_TEMP.of(payload("2210A5", "7DA066210A5373343")), 0.01)
    }

    @Test
    fun `HSD 3 motors and boost converter`() {
        val mg1 = payload("2161", "7EA 07 61 61 3D 3C 3D 8F 4D")
        assertEquals(3917.0, ToyotaHybrid.MG1_RPM.of(mg1), 0.01)
        assertEquals(21.0, ToyotaHybrid.MG1_TEMP.of(mg1), 0.01)
        assertEquals(-0.375, ToyotaHybrid.MG1_TORQUE.of(payload("2167", "7EA 07 61 67 7F FD 7F FA 00")), 0.001)

        val boost = payload("2174", "7EA 10 0B 61 74 46 39 37 50", "7EA 21 00 01 C8 03 E8 00 00")
        assertEquals(228.0, ToyotaHybrid.HSD3_VL.of(boost), 0.01)
        assertEquals(500.0, ToyotaHybrid.HSD3_VH.of(boost), 0.01)
        assertEquals(30.0, ToyotaHybrid.HSD3_BOOST_TEMP.of(boost), 0.01)
        assertEquals(18.0, ToyotaHybrid.HSD3_INVERTER_COOLANT.of(payload("2175", "7EA 10 08 61 75 20 0D 2F 3A", "7EA 21 C5 C0 00 00 00 00 00")), 0.01)
    }

    @Test
    fun `HSD 3 battery`() {
        val blocks = payload(
            "2181",
            "7EA 10 22 61 81 37 7C 37 1A", "7EA 21 38 08 38 08 38 BC 38", "7EA 22 BC 37 95 37 95 37 33",
            "7EA 23 37 22 38 49 38 5A 37", "7EA 24 53 37 95 AF 3B 09 74",
        )
        assertEquals(32, blocks.size)
        assertEquals(242.0, ToyotaHybrid.HSD3_HV_VOLTAGE.of(blocks), 0.01)

        val battery = payload("2198", "7EA 10 0A 61 98 74 E9 5A A1", "7EA 21 00 79 79 72 00 00 00")
        assertEquals(-28.39, ToyotaHybrid.HSD3_HV_CURRENT.of(battery), 0.001)
        assertEquals(-19.0, ToyotaHybrid.CHARGE_LIMIT.of(battery), 0.01)
        assertEquals(16.5, ToyotaHybrid.DISCHARGE_LIMIT.of(battery), 0.01)
        assertEquals(0.0, ToyotaHybrid.DELTA_SOC.of(battery), 0.01)

        val range = payload("2192", "7EA 10 11 61 92 33 F7 04 35", "7EA 21 26 0D 0E 00 00 00 00", "7EA 22 00 00 00 00 00 00 00")
        assertEquals(16.24, ToyotaHybrid.BLOCK_MIN.of(range), 0.005)
        assertEquals(16.61, ToyotaHybrid.BLOCK_MAX.of(range), 0.005)
        assertEquals(0.37, ToyotaHybrid.BLOCK_SPREAD.of(range), 0.005)

        val resistance = payload("2195", "7EA 10 10 61 95 17 16 15 16", "7EA 21 16 16 16 16 16 16 16", "7EA 22 16 16 17 00 00 00 00")
        assertEquals(23.0, ToyotaHybrid.RESISTANCE_MAX.of(resistance), 0.01)

        val temps = payload("2187", "7EA 10 0A 61 87 3D C2 40 73", "7EA 21 41 5E 40 8C 00 00 00")
        assertEquals(15.34, ToyotaHybrid.HSD3_BATTERY_TEMP_MAX.of(temps), 0.01)
        assertEquals(14.42, ToyotaHybrid.HSD3_BATTERY_TEMP_MIN.of(temps), 0.01)
    }

    @Test
    fun `steering angle from the brake ECU`() {
        val right = payload("2147", "7B81009614700007193", "7B8219D000100000000")
        assertEquals(502.1, ToyotaHybrid.STEERING_ANGLE.of(right), 0.01)
        val left = payload("2147", "7B81009614700007376", "7B82108000100000000")
        assertEquals(-255.2, ToyotaHybrid.STEERING_ANGLE.of(left), 0.01)
        assertEquals(35.0, ToyotaHybrid.HSD3_BATTERY_FAN.of(listOf(0x46, 0x80)), 0.01)
    }

    @Test
    fun `truncated or empty answers give no value`() {
        // Première trame seule (contrôle de flux refusé) : tension présente, courant absent.
        assertNull(ToyotaHybrid.HV_CURRENT.decodeEnhanced(listOf(0x0E, 0x00, 0x30)))
        assertNull(ToyotaHybrid.CELL_SPREAD.decodeEnhanced(listOf(0x00, 0x00, 0xFF, 0xFF)))
        val cells = listOf(0xAE, 0x14, 0xAD, 0x0F, 0x00, 0x00) // 3,400 V, 3,380 V, emplacement vide
        assertEquals(3.380, ToyotaHybrid.CELL_MIN.of(cells), 0.001)
        assertEquals(0.020, ToyotaHybrid.CELL_SPREAD.of(cells), 0.001)
    }

    @Test
    fun `hybrid values have their own codes`() {
        val codes = Pids.DASHBOARD.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(ToyotaHybrid.ALL.all { it.code >= 0x100 && it.request != null })
        // Toutes les valeurs constructeur sont au tableau de bord, certaines dans les infos principales.
        assertTrue(Pids.DASHBOARD.filter { it.request != null }.containsAll(ToyotaHybrid.ALL))
        assertTrue(listOf(Pids.HYBRID_BATTERY, ToyotaHybrid.HV_POWER, ToyotaHybrid.MOTOR_RPM, ToyotaHybrid.REGEN_TORQUE).all { it in Pids.MAIN })
        // Valeurs hybrides, quelle que soit leur section : 5B et calculateurs hybrides, pas le volant (7B0) ni le mode 01.
        assertTrue(Pids.isHybridValue(Pids.HYBRID_BATTERY) && Pids.isHybridValue(ToyotaHybrid.MOTOR_RPM) && Pids.isHybridValue(ToyotaHybrid.MG2_RPM))
        assertTrue(listOf(ToyotaHybrid.STEERING_ANGLE, Pids.RPM, Pids.FUEL_ECONOMY).none(Pids::isHybridValue))
    }
}
