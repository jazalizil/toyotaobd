package com.example.toyotaobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.toyotaobd.session.ConnectionState

/** Problème qui empêche d'utiliser un adaptateur Bluetooth réel. */
enum class BluetoothIssue(val message: String, val action: String?) {
    UNAVAILABLE("Cet appareil ne dispose pas du Bluetooth.", null),
    PERMISSIONS("L'application a besoin des autorisations Bluetooth pour trouver et se connecter à l'adaptateur OBD.", "Autoriser"),
    DISABLED("Le Bluetooth est désactivé.", "Activer le Bluetooth"),
}

/**
 * Racine : permissions → Bluetooth activé → écran de connexion ou écrans véhicule.
 * Dans les builds debug, les adaptateurs simulés restent accessibles même sans Bluetooth
 * (émulateur, permissions refusées) : le problème est alors signalé dans l'écran de connexion.
 */
@Composable
fun ObdApp(vm: ObdViewModel) {
    var permissionsGranted by remember { mutableStateOf(vm.hasPermissions()) }
    // Réactif : la radio peut être allumée depuis les réglages système pendant que l'app tourne.
    val bluetoothEnabled by vm.bluetoothEnabled.collectAsStateWithLifecycle()

    // Re-vérifie les autorisations au retour dans l'application (accordées dans les réglages :
    // la reprise de l'écran suffit, le correcteur de problème est fourni par la plateforme).
    LifecycleResumeEffect(Unit) {
        permissionsGranted = vm.hasPermissions()
        if (permissionsGranted && bluetoothEnabled) vm.refreshDevices()
        onPauseOrDispose { }
    }

    val fixIssue = rememberBluetoothIssueFixer()

    val issue = when {
        !vm.bluetoothAvailable -> BluetoothIssue.UNAVAILABLE
        !permissionsGranted -> BluetoothIssue.PERMISSIONS
        !bluetoothEnabled -> BluetoothIssue.DISABLED
        else -> null
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Sur iOS, le contrôleur laisse le Compose couvrir tout l'écran (ignoreSafeArea) : la zone
        // sûre (encoche, barre d'accueil) est appliquée ici au contenu, le fond reste plein écran.
        // Les écrans qui appliquent déjà safeDrawingPadding consomment des insets à zéro.
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (issue != null && vm.simulators.isEmpty()) {
                Message(issue.message, issue.action) { fixIssue(issue) }
            } else {
                val connection by vm.connection.collectAsStateWithLifecycle()
                when (val state = connection) {
                    is ConnectionState.Connected -> MainScreen(vm, state)
                    else -> ConnectScreen(vm, state, issue) { issue?.let(fixIssue) }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (action != null) Button(onClick = onAction) { Text(action) }
    }
}
