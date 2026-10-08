package com.example.toyotaobd.session

import com.example.toyotaobd.dev.sim.Elm327Emulator
import com.example.toyotaobd.dev.sim.SimVehicle
import com.example.toyotaobd.dev.sim.SimVehicles
import com.example.toyotaobd.dev.sim.SimulatedTransport
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.ProtocolChoice
import com.example.toyotaobd.obd.ToyotaHybrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** ObdSession face au simulateur, sur un fil unique comme le thread principal de l'application. */
class ObdSessionTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val connectedCalls = AtomicInteger()
    private val widgetRefreshes = AtomicInteger()

    private val session = ObdSession(
        scope = scope,
        onConnected = { connectedCalls.incrementAndGet() },
        refreshWidgets = { widgetRefreshes.incrementAndGet() },
        widgetRefreshMs = 50,
    )

    @After
    fun tearDown() {
        runBlocking { onSession { session.disconnect() } }
        scope.cancel()
        executor.shutdownNow()
    }

    private suspend fun <T> onSession(block: () -> T): T = withContext(dispatcher) { block() }

    private fun startConnecting(dropAfterMs: Long?, vehicle: () -> SimVehicle, latencyScale: Double) {
        session.connect("Simulateur", "…", ProtocolChoice.TOYOTA_AUTO) {
            SimulatedTransport(Elm327Emulator(vehicle()), latencyScale = latencyScale, dropAfterMs = dropAfterMs)
        }
    }

    private suspend fun connect(dropAfterMs: Long? = null, vehicle: () -> SimVehicle = SimVehicles::hybridCan) {
        onSession { startConnecting(dropAfterMs, vehicle, latencyScale = 0.02) }
        withTimeout(5_000) { session.connection.first { it is ConnectionState.Connected } }
    }

    @Test
    fun `cancelling during the hybrid data search stays disconnected`() = runBlocking<Unit> {
        // Délais réels : la recherche des données hybrides dure plusieurs centaines de millisecondes.
        onSession { startConnecting(dropAfterMs = null, vehicle = SimVehicles::uxHybrid, latencyScale = 1.0) }
        withTimeout(10_000) {
            session.connection.first { (it as? ConnectionState.Connecting)?.step == "Recherche des données hybrides…" }
        }
        onSession { session.cancelConnection() }
        delay(1_500)
        assertEquals(ConnectionState.Disconnected(), session.connection.value)
        assertEquals(LiveState(), session.live.value)
        assertEquals(0, connectedCalls.get())
    }

    @Test
    fun `polling fills every dashboard value and starts the service once`() = runBlocking<Unit> {
        connect()
        val live = withTimeout(5_000) {
            session.live.first { live -> live.pids.isNotEmpty() && live.pids.all { it.code in live.values } && live.voltage != null }
        }
        assertNotNull(live.values[Pids.COOLANT_TEMP.code])
        assertEquals(1, connectedCalls.get())
        assertTrue(widgetRefreshes.get() > 0)
    }

    /**
     * Vérifie qu'aucune requête d'interrogation ne passe entre les commandes AT de l'utilisateur et
     * leur annulation. Avec ce dispatcher, le verrou de l'adaptateur suffit à garantir cet ordre :
     * le test ne détecte pas une reprise anticipée, que [ObdSession.leaveConsole] évite par construction.
     */
    @Test
    fun `console pauses polling and AT changes are undone before the next poll`() = runBlocking<Unit> {
        connect()
        withTimeout(5_000) { session.live.first { it.values.isNotEmpty() } }

        onSession { session.enterConsole() }
        delay(200) // la lecture en cours se termine
        val pausedAt = session.console.value.size
        delay(300)
        assertEquals(emptyList<ConsoleLine>(), session.console.value.drop(pausedAt).filter { it.direction == '>' })

        onSession {
            session.sendConsole("ATH0")
            session.sendConsole("ATSH7E1")
        }
        delay(100)
        onSession { session.leaveConsole() }

        // Journal arrêté à la première requête d'interrogation qui suit le rétablissement des réglages
        // (le premier ATSH7DF après la commande de l'utilisateur : l'interrogation hybride en envoie aussi).
        fun restoreAfterUser(log: List<String>): Int {
            val userChange = log.lastIndexOf("ATSH7E1")
            return log.withIndex().firstOrNull { (i, text) -> userChange >= 0 && i > userChange && text == "ATSH7DF" }?.index ?: -1
        }
        val log = withTimeout(5_000) {
            session.console.first { lines ->
                val sent = lines.filter { it.direction == '>' }.map { it.text }
                val restore = restoreAfterUser(sent)
                restore >= 0 && sent.drop(restore).any { it.startsWith("01") || it == "ATRV" }
            }
        }.filter { it.direction == '>' }.map { it.text }
        val userChange = log.lastIndexOf("ATSH7E1")
        val restore = restoreAfterUser(log)
        val betweenUserAndRestore = log.subList(userChange + 1, restore)
        assertTrue("ATH1" in betweenUserAndRestore)
        assertTrue(betweenUserAndRestore.none { it.startsWith("01") || it == "ATRV" })
    }

    @Test
    fun `fuel economy is computed each cycle from the polled values`() = runBlocking<Unit> {
        connect()
        // Le simulateur démarre à l'arrêt (pas de valeur sous 5 km/h) : on vérifie le calcul à chaque cycle.
        val live = withTimeout(5_000) { session.live.first { it.values.containsKey(Pids.FUEL_ECONOMY.code) } }
        assertTrue(Pids.FUEL_ECONOMY in live.pids)
        assertEquals(Pids.FUEL_ECONOMY.derivation!!.compute(live.values), live.values[Pids.FUEL_ECONOMY.code])
    }

    @Test
    fun `Lexus UX dashboard follows the hybrid battery`() = runBlocking<Unit> {
        connect(vehicle = SimVehicles::uxHybrid)
        val live = withTimeout(5_000) {
            session.live.first { it.values[ToyotaHybrid.HV_POWER.code] != null && it.values[ToyotaHybrid.BATTERY_TEMP_MAX.code] != null }
        }
        assertNotNull(live.values[Pids.HYBRID_BATTERY.code])
        assertTrue(live.pids.none { it == ToyotaHybrid.SOC }) // copie de 5B, non interrogée
        withTimeout(5_000) { session.history.first { (it[ToyotaHybrid.HV_CURRENT.code]?.size ?: 0) >= 2 } }
    }

    @Test
    fun `history keeps each value in time order until disconnection`() = runBlocking<Unit> {
        connect()
        val history = withTimeout(5_000) { session.history.first { (it[Pids.RPM.code]?.size ?: 0) >= 3 } }
        val rpm = history.getValue(Pids.RPM.code)
        assertEquals(rpm.sortedBy { it.timeMs }, rpm)
        assertTrue(history.keys.all { code -> session.live.value.pids.any { it.code == code } })

        onSession { session.disconnect() }
        assertEquals(emptyMap<Int, List<Sample>>(), session.history.value)
    }

    @Test
    fun `disconnect resets state and redraws widgets`() = runBlocking<Unit> {
        connect()
        withTimeout(5_000) { session.live.first { it.values.isNotEmpty() } }
        val before = widgetRefreshes.get()

        onSession { session.disconnect() }
        assertEquals(ConnectionState.Disconnected(), session.connection.value)
        assertEquals(LiveState(), session.live.value)
        withTimeout(2_000) { while (widgetRefreshes.get() == before) delay(10) }
    }

    @Test
    fun `lost link is reported`() = runBlocking<Unit> {
        connect(dropAfterMs = 800)
        val state = withTimeout(5_000) { session.connection.first { it is ConnectionState.Disconnected } }
        assertTrue((state as ConnectionState.Disconnected).error!!.startsWith("Liaison perdue"))
        assertEquals(LiveState(), session.live.value)
    }
}
