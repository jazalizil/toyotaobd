package com.example.toyotaobd.dev.sim

import com.example.toyotaobd.obd.Dtc
import com.example.toyotaobd.obd.DtcMode
import com.example.toyotaobd.obd.Elm327
import com.example.toyotaobd.obd.HeaderFormat
import com.example.toyotaobd.obd.MonitorStatus
import com.example.toyotaobd.obd.ObdClient
import com.example.toyotaobd.obd.ObdException
import com.example.toyotaobd.obd.ObdProtocol
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.ProtocolChoice
import com.example.toyotaobd.obd.ResponseParser
import com.example.toyotaobd.obd.Toyota
import com.example.toyotaobd.obd.ToyotaHybrid
import com.example.toyotaobd.obd.EnhancedPid
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** L'application complète (Elm327 + ObdClient) face au simulateur, sans délais. */
class SimulatorTest {
    private val elms = mutableListOf<Elm327>()

    /** Horloge contrôlée : 20 s après la connexion, la voiture accélère (moteur thermique en marche). */
    private var now = 0L

    /** Commandes envoyées à l'adaptateur, dans l'ordre. */
    private val sent = mutableListOf<String>()

    private fun connect(vehicle: SimVehicle, dropAfterMs: Long? = null): ObdClient {
        val emulator = Elm327Emulator(vehicle, clock = { now })
        now = 20_000
        val transport = SimulatedTransport(emulator, latencyScale = 0.0, dropAfterMs = dropAfterMs)
        val elm = Elm327(transport) { direction, text -> if (direction == '>') synchronized(sent) { sent += text } }
        return ObdClient(elm.also { elms += it })
    }

    private suspend fun ObdClient.read(pid: Pid): Double? =
        readEnhancedPayloads(listOfNotNull(pid.request))[pid.request]?.let(pid::decodeEnhanced)

    @After
    fun tearDown() = elms.forEach { it.close() }

    @Test
    fun `hybrid CAN vehicle initializes with VIN and supported PIDs`() = runBlocking<Unit> {
        val info = connect(SimVehicles.hybridCan()).initialize(ProtocolChoice.TOYOTA_AUTO)

        assertEquals("ELM327 v1.5", info.adapterVersion)
        assertEquals(ObdProtocol.CAN_11_500, info.protocol)
        assertEquals(SimVehicles.HYBRID_VIN, info.vin)
        // Tout le tableau de bord sauf les valeurs TNGA (7D2, 747 absents), la consommation (calculée)
        // et 4 PID non simulés.
        val tngaEcus = setOf(Toyota.HYBRID_UDS, Toyota.HV_BATTERY_UDS)
        val expected = Pids.DASHBOARD.filter { it.request?.ecu !in tngaEcus && it.derivation == null }
        assertTrue(expected.all { it.code in info.supportedPids || it.code in setOf(0x08, 0x09, 0x21, 0x5C) })
        assertTrue(Pids.HYBRID_BATTERY.code in info.supportedPids) // annoncé par le calculateur hybride (7EA)
        assertTrue(Pids.DASHBOARD.filter { it.request?.ecu in tngaEcus }.none { it.code in info.supportedPids })
        // 7D2 et 747 absents : une seule requête chacun, la recherche ne s'attarde pas.
        assertEquals(1, requestsTo("7D2").size)
        assertEquals(1, requestsTo("747").size)
    }

    /** Requêtes envoyées pendant que l'en-tête [header] était actif. */
    private fun requestsTo(header: String): List<String> {
        var current = "7DF"
        return sent.filter { command ->
            if (command.startsWith("ATSH")) current = command.removePrefix("ATSH")
            current == header && !command.startsWith("AT")
        }
    }

    @Test
    fun `HSD 3 hybrid data while accelerating`() = runBlocking<Unit> {
        val client = connect(SimVehicles.hybridCan())
        client.initialize(ProtocolChoice.TOYOTA_AUTO)

        val current = client.read(ToyotaHybrid.HSD3_HV_CURRENT)!!
        assertTrue(current in 45.0..75.0) // décharge pendant l'accélération
        assertEquals(225 - 0.25 * current, client.read(ToyotaHybrid.HSD3_HV_VOLTAGE)!!, 1.0) // 2181, 5 trames
        assertEquals(35.0 * 95, client.read(ToyotaHybrid.MG2_RPM)!!, 50.0)
        assertEquals(600.0, client.read(ToyotaHybrid.HSD3_VH)!!, 0.5)
        assertEquals(0.17, client.read(ToyotaHybrid.BLOCK_SPREAD)!!, 0.01)
    }

    @Test
    fun `Lexus UX reads hybrid data from 7D2 and the charge from mode 01`() = runBlocking<Unit> {
        val client = connect(SimVehicles.uxHybrid())
        val info = client.initialize(ProtocolChoice.TOYOTA_AUTO)

        assertEquals(SimVehicles.UX_VIN, info.vin)
        assertTrue(Pids.HYBRID_BATTERY.code in info.supportedPids)
        // Valeurs de 7D2 et 7B0 (où qu'elles soient rangées), sauf la copie 1F5B (5B annoncé) ; pas de
        // batterie lithium (747) ni de HSD 3 (7E2 répond 7F 21 11).
        val answered = Pids.DASHBOARD.filter { it.request?.ecu == Toyota.HYBRID_UDS || it.request?.ecu == Toyota.BRAKES } - ToyotaHybrid.SOC
        assertEquals(answered.map { it.code }.toSet(), info.supportedPids.filter { it >= 0x100 }.toSet())
        assertTrue(sent.none { it == "221F5B" })
        // Toutes les requêtes 7D2 sont essayées, sauf la copie 1F5B.
        val tngaRequests = Pids.DASHBOARD.mapNotNull { it.request }.filter { it.ecu == Toyota.HYBRID_UDS }.map { it.command }.toSet()
        assertEquals(tngaRequests - "221F5B", requestsTo("7D2").toSet())
        assertEquals(1, requestsTo("7E2").size) // service 21 refusé en bloc (7F 21 11) : HSD 3 absent

        val current = client.read(ToyotaHybrid.HV_CURRENT)!!
        assertTrue(current in 45.0..75.0)
        assertTrue(client.read(ToyotaHybrid.HV_POWER)!! > 9)
        assertEquals(35.0 * 95, client.read(ToyotaHybrid.MOTOR_RPM)!!, 50.0)
        assertEquals(110.0, client.read(ToyotaHybrid.MOTOR_TORQUE)!!, 11.0) // 2e trame de 10A1
        assertEquals(30 * kotlin.math.sin(20.0 / 7), client.read(ToyotaHybrid.STEERING_ANGLE)!!, 0.1) // 7B0, réponse 7B8

        // Filtre et contrôle de flux posés pour 7DA puis rétablis : la diffusion 7DF fonctionne toujours.
        assertTrue(listOf("ATCRA7DA", "ATFCSH7D2", "ATFCSM1").all { it in sent })
        assertEquals("ATFCSM0", sent.last { it.startsWith("ATFCSM") })
        assertTrue(client.readPid(Pids.RPM)!! > 0)
        assertEquals(35.0, client.readPid(Pids.SPEED)!!, 1.0)
    }

    @Test
    fun `live values are plausible while accelerating`() = runBlocking<Unit> {
        val client = connect(SimVehicles.hybridCan())
        client.initialize(ProtocolChoice.TOYOTA_AUTO)

        assertTrue(client.readPid(Pids.RPM)!! in 2_000.0..4_000.0)
        assertEquals(35.0, client.readPid(Pids.SPEED)!!, 1.0)
        assertEquals(44.0, client.readPid(Pids.COOLANT_TEMP)!!, 1.0)
        assertTrue(client.readBatteryVoltage()!! in 13.5..14.5)
        assertNull(client.readPid(Pids.OIL_TEMP)) // non supporté, comme sur beaucoup de Toyota
    }

    @Test
    fun `hybrid engine is off when stopped`() = runBlocking<Unit> {
        val client = connect(SimVehicles.hybridCan())
        client.initialize(ProtocolChoice.TOYOTA_AUTO)
        now = 85_000 // phase d'arrêt du cycle
        assertEquals(0.0, client.readPid(Pids.RPM)!!, 0.001)
        assertEquals(0.0, client.readPid(Pids.SPEED)!!, 0.001)
    }

    @Test
    fun `DTCs from several ECUs, clearing keeps permanent codes`() = runBlocking<Unit> {
        val client = connect(SimVehicles.hybridCan())
        client.initialize(ProtocolChoice.TOYOTA_AUTO)

        assertEquals(MonitorStatus(milOn = true, dtcCount = 2), client.readMonitorStatus())
        assertEquals(listOf(Dtc("P0420", "7E8"), Dtc("P0A80", "7EA")), client.readDtcs(DtcMode.STORED))
        assertEquals(listOf(Dtc("P0171", "7E8")), client.readDtcs(DtcMode.PENDING))
        assertEquals(listOf(Dtc("P0420", "7E8")), client.readDtcs(DtcMode.PERMANENT))

        assertTrue(client.clearDtcs())
        assertEquals(emptyList<Dtc>(), client.readDtcs(DtcMode.STORED))
        assertEquals(emptyList<Dtc>(), client.readDtcs(DtcMode.PENDING))
        assertEquals(listOf(Dtc("P0420", "7E8")), client.readDtcs(DtcMode.PERMANENT))
        assertEquals(MonitorStatus(milOn = false, dtcCount = 0), client.readMonitorStatus())
        assertEquals(0.0, client.readPid(Pids.DISTANCE_SINCE_CLEAR)!!, 0.001)
    }

    @Test
    fun `Toyota presets and a multi-frame enhanced PID`() = runBlocking<Unit> {
        val client = connect(SimVehicles.hybridCan())
        client.initialize(ProtocolChoice.TOYOTA_AUTO)

        Toyota.PRESETS.forEach { preset ->
            assertEquals(preset.command, 36.0, client.readEnhanced(preset).value!!, 0.5)
        }
        val engineBlock = client.readEnhanced(EnhancedPid("t", "Bloc moteur", "7E0", "2101", "A-40", "°C"))
        assertEquals(20, engineBlock.payload.size)
        assertEquals(44.0, engineBlock.value!!, 1.0)

        val unknown = client.readEnhanced(EnhancedPid("u", "Inconnu", "7E1", "2199", "A", ""))
        assertTrue(unknown.error!!.contains("0x31"))
        val absent = client.readEnhanced(EnhancedPid("a", "Combiné", "7C0", "2101", "A", ""))
        assertEquals("NO DATA", absent.error)

        // La diffusion 7DF fonctionne toujours après les lectures ciblées.
        assertTrue(client.readPid(Pids.RPM)!! > 0)
    }

    @Test
    fun `old ISO 9141 vehicle`() = runBlocking<Unit> {
        val client = connect(SimVehicles.legacyIso9141())
        val info = client.initialize(ProtocolChoice.TOYOTA_AUTO)

        assertEquals(ObdProtocol.ISO_9141_2, info.protocol)
        assertNull(info.vin)
        // Pas de requête constructeur hors CAN 11 bits : aucune donnée hybride essayée.
        assertTrue(info.supportedPids.none { it >= 0x100 })
        assertTrue(sent.none { it.startsWith("ATSH") || it.startsWith("21") || it.startsWith("22") })
        assertTrue(client.readPid(Pids.RPM)!! > 0)
        assertNull(client.readPid(Pids.MAF))
        assertEquals(
            listOf("P0420", "P0300", "P0171", "P0135"),
            client.readDtcs(DtcMode.STORED).map { it.code },
        )
        assertThrows(ObdException::class.java) {
            runBlocking { client.readEnhanced(Toyota.PRESETS.first()) }
        }
    }

    @Test
    fun `ignition off and wrong protocol give clear errors`() = runBlocking<Unit> {
        val off = runCatching { connect(SimVehicles.hybridCan(ignitionOn = false)).initialize(ProtocolChoice.AUTO) }
        assertTrue(off.exceptionOrNull()!!.message!!.contains("UNABLE TO CONNECT"))

        val wrong = runCatching { connect(SimVehicles.hybridCan()).initialize(ProtocolChoice.ISO_9141) }
        assertTrue(wrong.exceptionOrNull()!!.message!!.contains("BUS INIT: ...ERROR"))
    }

    @Test
    fun `unstable link reports a disconnection`() = runBlocking<Unit> {
        val client = connect(SimVehicles.hybridCan(), dropAfterMs = 1_000)
        client.initialize(ProtocolChoice.TOYOTA_AUTO)

        val cause = withTimeout(5_000) { elms.last().disconnected.await() }
        assertTrue(cause is IOException)
        assertThrows(IOException::class.java) { runBlocking { client.readPid(Pids.RPM) } }
    }

    @Test
    fun `emulator output formats match the parser`() {
        val emulator = Elm327Emulator(SimVehicles.hybridCan(), clock = { 20_000 })

        assertTrue(emulator.handle("ATI").text.startsWith("ATI\r")) // écho actif par défaut
        emulator.handle("ATE0")
        assertEquals("OK\r\r", emulator.handle("ATH0").text)

        // En-têtes désactivés : VIN multi-trames au format « 014 / 0: / 1: / 2: ».
        val vinRaw = emulator.handle("0902").text
        assertTrue(vinRaw.contains("\r014\r0: 49 02 01"))
        val vin = ResponseParser.parse(vinRaw, HeaderFormat.NONE).single().data.drop(3).map { it.toChar() }.joinToString("")
        assertEquals(SimVehicles.HYBRID_VIN, vin)

        // En-têtes activés, sans espaces : trames CAN brutes, une par calculateur.
        emulator.handle("ATH1")
        emulator.handle("ATS0")
        val supported = ResponseParser.parse(emulator.handle("0100").text, HeaderFormat.CAN_11)
        assertEquals(listOf("7E8", "7E9", "7EA"), supported.map { it.ecu })

        // Le filtre de réception ne laisse passer que le calculateur demandé.
        emulator.handle("ATCRA7EA")
        assertEquals(listOf("7EA"), ResponseParser.parse(emulator.handle("0100").text, HeaderFormat.CAN_11).map { it.ecu })
        emulator.handle("ATCRA")

        assertEquals("?\r\r", emulator.handle("ATXYZ").text)
        assertEquals("?\r\r", emulator.handle("HELLO").text)
    }
}
