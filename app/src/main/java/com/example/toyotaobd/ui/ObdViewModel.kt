package com.example.toyotaobd.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.toyotaobd.bluetooth.BluetoothRepository
import com.example.toyotaobd.bluetooth.BtDevice
import com.example.toyotaobd.data.CustomPidStore
import com.example.toyotaobd.data.DashboardLayout
import com.example.toyotaobd.data.DashboardStore
import com.example.toyotaobd.dev.DevDevices
import com.example.toyotaobd.dev.SimulatedDevice
import com.example.toyotaobd.obd.Dtc
import com.example.toyotaobd.obd.DtcMode
import com.example.toyotaobd.obd.EnhancedPid
import com.example.toyotaobd.obd.EnhancedReading
import com.example.toyotaobd.obd.MonitorStatus
import com.example.toyotaobd.obd.ProtocolChoice
import com.example.toyotaobd.obd.Toyota
import com.example.toyotaobd.obdSession
import com.example.toyotaobd.session.ConnectionState
import com.example.toyotaobd.session.ConsoleLine
import com.example.toyotaobd.session.LiveState
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.session.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class DeviceListState(
    val paired: List<BtDevice> = emptyList(),
    val discovered: List<BtDevice> = emptyList(),
    val scanning: Boolean = false,
    val error: String? = null,
)

data class DtcState(
    val loading: Boolean = false,
    val status: MonitorStatus? = null,
    val codes: Map<DtcMode, List<Dtc>> = emptyMap(),
    val readOnce: Boolean = false,
    val message: String? = null,
)

data class EnhancedState(
    val pids: List<EnhancedPid> = Toyota.PRESETS,
    val readings: Map<String, EnhancedReading> = emptyMap(),
    val loading: Set<String> = emptySet(),
)

/**
 * État des écrans. La connexion elle-même vit dans [com.example.toyotaobd.session.ObdSession],
 * à l'échelle de l'application : elle n'est pas fermée quand l'activité disparaît.
 */
class ObdViewModel(app: Application) : AndroidViewModel(app) {

    private val session = app.obdSession
    private val bluetooth = BluetoothRepository(app)
    private val pidStore = CustomPidStore(app)
    private val dashboardStore = DashboardStore(app)

    val connection: StateFlow<ConnectionState> = session.connection
    val live: StateFlow<LiveState> = session.live
    val history: StateFlow<Map<Int, List<Sample>>> = session.history
    val console: StateFlow<List<ConsoleLine>> = session.console

    private val _devices = MutableStateFlow(DeviceListState())
    val devices: StateFlow<DeviceListState> = _devices.asStateFlow()

    private val _protocolChoice = MutableStateFlow(ProtocolChoice.TOYOTA_AUTO)
    val protocolChoice: StateFlow<ProtocolChoice> = _protocolChoice.asStateFlow()

    private val _dtc = MutableStateFlow(DtcState())
    val dtc: StateFlow<DtcState> = _dtc.asStateFlow()

    private val _enhanced = MutableStateFlow(EnhancedState(pids = Toyota.PRESETS + pidStore.load()))
    val enhanced: StateFlow<EnhancedState> = _enhanced.asStateFlow()

    private val _dashboard = MutableStateFlow(dashboardStore.load())
    val dashboard: StateFlow<DashboardLayout> = _dashboard.asStateFlow()

    private var discoveryJob: Job? = null

    /** Adaptateurs simulés : non vide uniquement dans les builds debug. */
    val simulators: List<SimulatedDevice> = DevDevices.simulators

    val bluetoothAvailable: Boolean get() = bluetooth.isAvailable
    fun hasPermissions(): Boolean = bluetooth.hasPermissions()
    fun isBluetoothEnabled(): Boolean = bluetooth.isEnabled

    init {
        // Les lectures affichées ne valent que pour la connexion en cours.
        viewModelScope.launch {
            session.connection.collect { state ->
                if (state !is ConnectionState.Connected) {
                    _dtc.value = DtcState()
                    _enhanced.update { it.copy(readings = emptyMap(), loading = emptySet()) }
                }
            }
        }
    }

    // --- Appareils -----------------------------------------------------------------------

    fun refreshDevices() {
        if (!hasPermissions() || !isBluetoothEnabled()) return
        _devices.update { it.copy(paired = bluetooth.pairedDevices()) }
    }

    fun startDiscovery() {
        discoveryJob?.cancel()
        _devices.update { it.copy(discovered = emptyList(), scanning = true, error = null) }
        discoveryJob = viewModelScope.launch {
            try {
                bluetooth.discover().collect { device ->
                    _devices.update { state ->
                        val known = (state.paired + state.discovered).any { it.address == device.address }
                        if (known) state else state.copy(discovered = state.discovered + device)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _devices.update { it.copy(error = e.message) }
            } finally {
                _devices.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
    }

    fun selectProtocol(choice: ProtocolChoice) {
        _protocolChoice.value = choice
    }

    // --- Connexion -----------------------------------------------------------------------

    fun connect(device: BtDevice) {
        stopDiscovery()
        session.connect(device.name, "Connexion Bluetooth…", _protocolChoice.value) { bluetooth.connect(device.address) }
    }

    /** Connexion à un adaptateur simulé (builds debug) : même chemin que le Bluetooth, sans socket. */
    fun connectSimulator(device: SimulatedDevice) {
        stopDiscovery()
        session.connect(device.name, "Ouverture du simulateur…", _protocolChoice.value) { device.open() }
    }

    fun cancelConnection() = session.cancelConnection()

    fun disconnect() = session.disconnect()

    // --- Tableau de bord -----------------------------------------------------------------

    fun editDashboard(transform: (DashboardLayout) -> DashboardLayout) {
        _dashboard.update(transform)
        dashboardStore.save(_dashboard.value)
    }

    // --- Codes défaut --------------------------------------------------------------------

    fun readDtcs() {
        viewModelScope.launch {
            _dtc.update { it.copy(loading = true, message = null) }
            val status = session.withClient { it.readMonitorStatus() }.getOrNull()
            val codes = DtcMode.entries.associateWith { mode ->
                session.withClient { it.readDtcs(mode) }.getOrDefault(emptyList())
            }
            _dtc.update { it.copy(loading = false, status = status, codes = codes, readOnce = true) }
        }
    }

    fun clearDtcs() {
        viewModelScope.launch {
            _dtc.update { it.copy(loading = true, message = null) }
            val result = session.withClient { it.clearDtcs() }
            val message = when {
                result.getOrDefault(false) -> "Codes effacés."
                else -> "Effacement refusé (${result.exceptionOrNull()?.toUserMessage() ?: "moteur arrêté, contact mis ?"})."
            }
            _dtc.update { it.copy(loading = false, message = message) }
            readDtcs()
        }
    }

    // --- PID Toyota / Lexus --------------------------------------------------------------

    fun readEnhanced(pid: EnhancedPid) {
        viewModelScope.launch {
            _enhanced.update { it.copy(loading = it.loading + pid.id) }
            val reading = session.withClient { it.readEnhanced(pid) }
                .getOrElse { EnhancedReading(error = it.toUserMessage()) }
            _enhanced.update { it.copy(loading = it.loading - pid.id, readings = it.readings + (pid.id to reading)) }
        }
    }

    fun addCustomPid(name: String, header: String, command: String, formula: String, unit: String) {
        val pid = EnhancedPid(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { command.uppercase() },
            header = header.uppercase(),
            command = command.uppercase(),
            formula = formula,
            unit = unit,
        )
        _enhanced.update { it.copy(pids = it.pids + pid) }
        saveCustomPids()
    }

    fun deleteCustomPid(id: String) {
        _enhanced.update { it.copy(pids = it.pids.filterNot { pid -> pid.id == id }) }
        saveCustomPids()
    }

    private fun saveCustomPids() = pidStore.save(_enhanced.value.pids.filterNot { it.builtIn })

    // --- Console -------------------------------------------------------------------------

    fun enterConsole() = session.enterConsole()

    fun leaveConsole() = session.leaveConsole()

    fun sendConsole(input: String) = session.sendConsole(input)

    fun clearConsole() = session.clearConsole()
}
