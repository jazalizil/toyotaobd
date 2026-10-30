package com.example.toyotaobd

import androidx.compose.ui.window.ComposeUIViewController
import com.example.toyotaobd.bluetooth.BleBluetoothRepository
import com.example.toyotaobd.data.CustomPidStore
import com.example.toyotaobd.data.DashboardStore
import com.example.toyotaobd.dev.DevDevices
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
 * préférences Android), et les adaptateurs simulés du module partagé (comme en debug Android).
 */
private val deps: AppDependencies by lazy {
    AppDependencies(
        session = ObdSession(scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)),
        bluetooth = BleBluetoothRepository(),
        dashboardStore = DashboardStore(DefaultsStore()),
        pidStore = CustomPidStore(DefaultsStore()),
        simulators = DevDevices.simulators,
    )
}

/**
 * Contrôleur racine de l'application iOS : l'interface Compose partagée dans le thème de l'app.
 * Le contenu Compose couvre tout l'écran (les insets de la zone sûre sont exposés via
 * `WindowInsets` et consommés par `safeDrawingPadding` à la racine de [ObdApp]).
 */
fun MainViewController() = ComposeUIViewController {
    ToyotaObdTheme {
        ObdApp(rememberObdViewModel(deps))
    }
}
