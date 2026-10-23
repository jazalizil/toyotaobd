package com.example.toyotaobd

import androidx.compose.ui.window.ComposeUIViewController
import com.example.toyotaobd.bluetooth.BleBluetoothRepository
import com.example.toyotaobd.data.CustomPidStore
import com.example.toyotaobd.data.DashboardStore
import com.example.toyotaobd.platform.DefaultsStore
import com.example.toyotaobd.session.ObdSession
import com.example.toyotaobd.ui.AppDependencies
import com.example.toyotaobd.ui.ObdApp
import com.example.toyotaobd.ui.rememberObdViewModel
import com.example.toyotaobd.ui.theme.ToyotaObdTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Dépendances de l'application iOS : session OBD (horloge monotone du module partagé), Bluetooth
 * Low Energy CoreBluetooth (iOS ne connaît pas le SPP), stockage NSUserDefaults (mêmes clés que les
 * préférences Android). Pas d'adaptateur simulé sur iOS.
 */
private val deps: AppDependencies by lazy {
    AppDependencies(
        session = ObdSession(scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)),
        bluetooth = BleBluetoothRepository(),
        dashboardStore = DashboardStore(DefaultsStore()),
        pidStore = CustomPidStore(DefaultsStore()),
        simulators = emptyList(),
    )
}

/** Contrôleur racine de l'application iOS : l'interface Compose partagée dans le thème de l'app. */
fun MainViewController() = ComposeUIViewController {
    ToyotaObdTheme {
        ObdApp(rememberObdViewModel(deps))
    }
}
