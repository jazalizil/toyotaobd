package com.example.toyotaobd.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.toyotaobd.bluetooth.BluetoothRepository
import com.example.toyotaobd.data.CustomPidStore
import com.example.toyotaobd.data.DashboardStore
import com.example.toyotaobd.dev.SimulatedDevice
import com.example.toyotaobd.session.ObdSession

/**
 * Dépendances de la plateforme, assemblées par le point d'entrée de chaque cible (Application
 * Android, contrôleur iOS) : dépôt Bluetooth, stockage persistant, adaptateurs simulés.
 */
class AppDependencies(
    val session: ObdSession,
    val bluetooth: BluetoothRepository,
    val dashboardStore: DashboardStore,
    val pidStore: CustomPidStore,
    val simulators: List<SimulatedDevice>,
)

/** ViewModel de l'application, créé une fois et conservé lors des changements de configuration. */
@Composable
fun rememberObdViewModel(deps: AppDependencies): ObdViewModel = viewModel(
    factory = viewModelFactory {
        initializer {
            ObdViewModel(
                session = deps.session,
                bluetooth = deps.bluetooth,
                pidStore = deps.pidStore,
                dashboardStore = deps.dashboardStore,
                simulators = deps.simulators,
            )
        }
    },
)
