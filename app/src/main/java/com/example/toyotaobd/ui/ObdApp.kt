package com.example.toyotaobd.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.toyotaobd.bluetooth.BluetoothRepository
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
fun ObdApp(vm: ObdViewModel = viewModel()) {
    var permissionsGranted by remember { mutableStateOf(vm.hasPermissions()) }
    var bluetoothEnabled by remember { mutableStateOf(vm.isBluetoothEnabled()) }

    // Re-vérifie au retour dans l'application (Bluetooth activé depuis les réglages, etc.).
    LifecycleResumeEffect(Unit) {
        permissionsGranted = vm.hasPermissions()
        bluetoothEnabled = vm.isBluetoothEnabled()
        if (permissionsGranted && bluetoothEnabled) vm.refreshDevices()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = vm.hasPermissions()
        bluetoothEnabled = vm.isBluetoothEnabled()
        vm.refreshDevices()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothEnabled = vm.isBluetoothEnabled()
        vm.refreshDevices()
    }

    val issue = when {
        !vm.bluetoothAvailable -> BluetoothIssue.UNAVAILABLE
        !permissionsGranted -> BluetoothIssue.PERMISSIONS
        !bluetoothEnabled -> BluetoothIssue.DISABLED
        else -> null
    }
    val fixIssue: () -> Unit = {
        when (issue) {
            BluetoothIssue.PERMISSIONS -> permissionLauncher.launch(BluetoothRepository.REQUIRED_PERMISSIONS)
            BluetoothIssue.DISABLED -> enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> Unit
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (issue != null && vm.simulators.isEmpty()) {
            Message(issue.message, issue.action, fixIssue)
        } else {
            val connection by vm.connection.collectAsStateWithLifecycle()
            when (val state = connection) {
                is ConnectionState.Connected -> MainScreen(vm, state)
                else -> ConnectScreen(vm, state, issue, fixIssue)
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
