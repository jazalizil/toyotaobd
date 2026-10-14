package com.example.toyotaobd.session

import com.example.toyotaobd.obd.Elm327
import com.example.toyotaobd.obd.ElmTimeoutException
import com.example.toyotaobd.obd.ObdClient
import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.ProtocolChoice
import com.example.toyotaobd.obd.VehicleInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface ConnectionState {
    data class Disconnected(val error: String? = null) : ConnectionState
    data class Connecting(val deviceName: String, val step: String) : ConnectionState
    data class Connected(val deviceName: String, val info: VehicleInfo) : ConnectionState
}

data class LiveState(
    val pids: List<Pid> = emptyList(),
    val values: Map<Int, Double?> = emptyMap(),
    val voltage: Double? = null,
)

/** Ligne de la console : '>' envoyé, '<' reçu, '!' information ou erreur. */
data class ConsoleLine(val direction: Char, val text: String)

/**
 * Connexion OBD de l'application, indépendante des écrans : elle survit à l'activité pour que
 * le widget (téléphone ou voiture) et la notification restent à jour en arrière-plan.
 *
 * Kotlin pur : les effets Android sont injectés ([onConnected] démarre le service de premier plan,
 * [refreshWidgets] redessine les widgets, au plus une fois toutes les [widgetRefreshMs]).
 * L'historique est daté par [clockMs], monotone : un recalage de l'heure du téléphone ne déforme pas les courbes.
 */
class ObdSession(
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit = {},
    private val refreshWidgets: suspend () -> Unit = {},
    private val widgetRefreshMs: Long = 2_000,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _history = MutableStateFlow<Map<Int, List<Sample>>>(emptyMap())

    /**
     * Valeurs de chaque PID depuis la connexion (courbes du tableau de bord, page de détail), sur
     * [HISTORY_MS] et [MAX_SAMPLES] valeurs au plus.
     */
    val history: StateFlow<Map<Int, List<Sample>>> = _history.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    /** Interrogation en continu suspendue (console au premier plan, éditeur du tableau de bord affiché). */
    private val livePaused = MutableStateFlow(false)

    /** Éditeur du tableau de bord affiché (feuille du téléphone) : voir [enterDashboardEditor]. */
    private val editorVisible = MutableStateFlow(false)

    @Volatile private var elm: Elm327? = null
    @Volatile private var client: ObdClient? = null
    private var connectJob: Job? = null
    private var liveJob: Job? = null
    private var consoleChangedSettings = false
    private var consoleVisible = false

    init {
        // Le dernier état l'emporte ; la déconnexion déclenche aussi un rafraîchissement.
        scope.launch {
            combine(connection, live) { _, _ -> }.conflate().collect {
                runCatching { refreshWidgets() }
                delay(widgetRefreshMs)
            }
        }
    }

    // --- Connexion -----------------------------------------------------------------------

    fun connect(name: String, firstStep: String, protocol: ProtocolChoice, open: suspend () -> ObdTransport) {
        if (_connection.value !is ConnectionState.Disconnected) return
        _console.value = emptyList()
        connectJob = scope.launch {
            _connection.value = ConnectionState.Connecting(name, firstStep)
            var newElm: Elm327? = null
            try {
                newElm = Elm327(open(), ::log)
                val newClient = ObdClient(newElm)
                val info = newClient.initialize(protocol) { step ->
                    _connection.value = ConnectionState.Connecting(name, step)
                }
                elm = newElm
                client = newClient
                // Sans liste de capacités du mode 01, tous ses PID sont essayés ; les valeurs constructeur ont été
                // vérifiées ; les valeurs calculées le sont si leurs PID sources sont interrogés.
                val unknown = info.supportedPids.none { it < 0x100 }
                val polled = Pids.DASHBOARD.filter {
                    it.derivation == null && (it.code in info.supportedPids || (unknown && it.request == null))
                }
                val polledCodes = polled.map { it.code }.toSet()
                _live.value = LiveState(pids = Pids.DASHBOARD.filter { it in polled || it.derivation?.available?.invoke(polledCodes) == true })
                _history.value = emptyMap()
                _connection.value = ConnectionState.Connected(name, info)
                watchDisconnection(newElm)
                startPolling(newClient)
                onConnected()
            } catch (e: CancellationException) {
                newElm?.close()
                throw e
            } catch (e: Exception) {
                newElm?.close()
                _connection.value = ConnectionState.Disconnected(e.toUserMessage())
            }
        }
    }

    fun cancelConnection() {
        connectJob?.cancel()
        _connection.value = ConnectionState.Disconnected()
    }

    fun disconnect(error: String? = null) {
        liveJob?.cancel()
        connectJob?.cancel()
        val current = elm
        elm = null
        client = null
        current?.close()
        livePaused.value = consoleVisible
        consoleChangedSettings = false
        _connection.value = ConnectionState.Disconnected(error)
        _live.value = LiveState()
        _history.value = emptyMap()
    }

    private fun watchDisconnection(watched: Elm327) {
        scope.launch {
            val cause = watched.disconnected.await()
            if (elm === watched) disconnect("Liaison perdue${cause?.message?.let { " : $it" }.orEmpty()}")
        }
    }

    /**
     * Exécute une requête OBD. Une IOException signifie que la liaison est perdue :
     * on repasse à l'état déconnecté. Les autres erreurs (délai, NO DATA...) sont renvoyées.
     */
    suspend fun <T> withClient(block: suspend (ObdClient) -> T): Result<T> {
        val current = client ?: return Result.failure(IOException("Non connecté"))
        return try {
            Result.success(block(current))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (client === current) disconnect("Liaison perdue : ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Données en direct ---------------------------------------------------------------

    /** Interroge les PID en boucle tant que la liaison est ouverte, sauf pendant une pause (console). */
    private fun startPolling(owner: ObdClient) {
        liveJob?.cancel()
        liveJob = scope.launch {
            var cycle = 0
            while (isActive && client === owner) {
                if (livePaused.value) {
                    livePaused.first { !it }
                    continue
                }
                if (editorVisible.value) {
                    editorVisible.first { !it }
                    continue
                }
                // Tension et valeurs lentes (températures...) : un cycle sur cinq, dont le premier.
                val slowCycle = cycle++ % SLOW_CYCLES == 0
                if (slowCycle) {
                    withClient { it.readBatteryVoltage() }.getOrNull()?.let { v -> _live.update { it.copy(voltage = v) } }
                }
                val (enhanced, others) = _live.value.pids.partition { it.request != null }
                val (derived, standard) = others.partition { it.derivation != null }
                for (pid in standard) {
                    if (livePaused.value || editorVisible.value) break
                    val value = withClient { it.readPid(pid) }.getOrNull()
                    if (client !== owner) return@launch
                    record(pid, value)
                }
                // Valeurs calculées (consommation aux 100 km...), à partir des lectures de ce cycle.
                for (pid in derived) record(pid, pid.derivation?.compute?.invoke(_live.value.values))
                // Valeurs constructeur : une requête par réponse (1F9A donne tension, courant et puissance),
                // un calculateur à la fois. Une requête est envoyée si l'une de ses valeurs est due.
                val due = enhanced.filter { slowCycle || !it.slow }.mapNotNull { it.request }.toSet()
                for (pids in enhanced.filter { it.request in due }.groupBy { it.request?.ecu }.values) {
                    if (livePaused.value || editorVisible.value) break
                    val payloads = withClient { it.readEnhancedPayloads(pids.mapNotNull(Pid::request)) }.getOrNull()
                    if (client !== owner) return@launch
                    for (pid in pids) record(pid, payloads?.get(pid.request)?.let(pid::decodeEnhanced))
                }
                delay(CYCLE_PAUSE_MS)
            }
        }
    }

    private fun record(pid: Pid, value: Double?) {
        _live.update { it.copy(values = it.values + (pid.code to value)) }
        if (value != null) {
            val sample = Sample(clockMs(), value)
            _history.update { it + (pid.code to it[pid.code].orEmpty().appendWithin(sample, HISTORY_MS, MAX_SAMPLES)) }
        }
    }

    // --- Console -------------------------------------------------------------------------

    /** La console est affichée : l'interrogation s'arrête pour garder le journal lisible. */
    fun enterConsole() {
        consoleVisible = true
        livePaused.value = true
    }

    /**
     * La console n'est plus affichée. Si des commandes AT ont été envoyées, les réglages sont
     * rétablis AVANT la reprise de l'interrogation (sinon elle lirait avec `ATH0`, `ATSH7E1`...).
     */
    fun leaveConsole() {
        consoleVisible = false
        scope.launch {
            if (consoleChangedSettings) {
                consoleChangedSettings = false
                withClient { it.restoreDefaults() }
                log('!', "Réglages de l'adaptateur rétablis (en-têtes, écho, diffusion 7DF).")
            }
            livePaused.value = consoleVisible // la console a pu être rouverte entre-temps
        }
    }

    fun sendConsole(input: String) {
        val command = input.trim().uppercase()
        if (command.isEmpty()) return
        if (command.startsWith("AT")) consoleChangedSettings = true
        scope.launch {
            withClient { it.raw(command) }.onFailure { log('!', it.toUserMessage()) }
        }
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    // --- Éditeur du tableau de bord ------------------------------------------------------

    /**
     * L'éditeur du tableau de bord est affiché (feuille sur l'écran du téléphone) : l'interrogation
     * s'arrête, comme pour la console. À chaque lecture, le tableau de bord derrière la feuille
     * (graphiques MPAndroidChart), le widget et la notification se redessinent sur le fil principal
     * et rendent son défilement saccadé ; l'éditeur n'affiche pas de valeurs. Refermé, l'interrogation
     * reprend (les courbes sautent l'interruption, voir [segments]).
     */
    fun enterDashboardEditor() {
        editorVisible.value = true
    }

    fun leaveDashboardEditor() {
        editorVisible.value = false
    }

    private fun log(direction: Char, text: String) {
        _console.update { (it + ConsoleLine(direction, text)).takeLast(MAX_CONSOLE_LINES) }
    }

    companion object {
        /**
         * Historique conservé par PID : un long trajet, pour la page de détail (« depuis le démarrage »).
         * Avec un vrai ELM327 (un cycle toutes les 2 à 4 s), 3 h font 2 700 à 5 400 valeurs par PID,
         * soit quelques Mo pour une quarantaine de PID ; [MAX_SAMPLES] borne le simulateur, plus rapide.
         */
        const val HISTORY_MS = 3 * 60 * 60_000L
        const val MAX_SAMPLES = 6_000
        private const val MAX_CONSOLE_LINES = 300
        private const val CYCLE_PAUSE_MS = 100L
        private const val SLOW_CYCLES = 5
    }
}

fun Throwable.toUserMessage(): String = when (this) {
    is ElmTimeoutException -> message ?: "Délai dépassé"
    is SecurityException -> "Permission Bluetooth refusée"
    else -> message ?: javaClass.simpleName
}
