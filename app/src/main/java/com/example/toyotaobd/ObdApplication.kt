package com.example.toyotaobd

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import com.example.toyotaobd.bluetooth.AndroidBluetoothRepository
import com.example.toyotaobd.data.CustomPidStore
import com.example.toyotaobd.data.DashboardStore
import com.example.toyotaobd.dev.DevDevices
import com.example.toyotaobd.platform.SharedPreferencesStore
import com.example.toyotaobd.service.ObdConnectionService
import com.example.toyotaobd.session.ObdSession
import com.example.toyotaobd.ui.AppDependencies
import com.example.toyotaobd.widget.DashboardWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ObdApplication : Application() {

    /** Connexion OBD partagée par les écrans, le widget et le service de premier plan. */
    val session: ObdSession by lazy {
        ObdSession(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            onConnected = ::startConnectionService,
            refreshWidgets = { DashboardWidget().updateAll(this) },
            // Compte aussi la veille profonde : une interrogation suspendue écran éteint coupe la courbe.
            clockMs = SystemClock::elapsedRealtime,
        )
    }

    /**
     * Dépendances de l'interface Compose partagée : session OBD, Bluetooth SPP, stockage du tableau
     * de bord et des PID personnalisés (mêmes fichiers de préférences qu'avant la migration), et
     * adaptateurs simulés des builds debug.
     */
    val deps: AppDependencies by lazy {
        AppDependencies(
            session = session,
            bluetooth = AndroidBluetoothRepository(this),
            dashboardStore = DashboardStore(SharedPreferencesStore(this, "dashboard")),
            pidStore = CustomPidStore(SharedPreferencesStore(this, "custom_pids")),
            simulators = DevDevices.simulators,
        )
    }

    /**
     * Garde la connexion active écran éteint ou application en arrière-plan (Android Auto).
     * Android 14+ n'autorise un service « connectedDevice » qu'avec BLUETOOTH_CONNECT : sans elle
     * (simulateur en debug), la session tourne simplement tant que le processus vit.
     */
    private fun startConnectionService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, ObdConnectionService::class.java))
        } catch (e: Exception) {
            // Ex. application passée en arrière-plan pendant la connexion (Android 12+).
            Log.w(TAG, "Service de premier plan non démarré", e)
        }
    }

    private companion object {
        const val TAG = "ObdApplication"
    }
}

val Context.obdSession: ObdSession get() = (applicationContext as ObdApplication).session
