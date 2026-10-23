package com.example.toyotaobd.ui

import androidx.compose.runtime.Composable
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

@Composable
actual fun rememberBluetoothIssueFixer(): (BluetoothIssue) -> Unit = { issue ->
    when (issue) {
        // CoreBluetooth demande l'autorisation à la première utilisation ; refusée définitivement,
        // seul le panneau Bluetooth des réglages permet de la rétablir.
        BluetoothIssue.PERMISSIONS, BluetoothIssue.DISABLED -> openBluetoothSettings()
        BluetoothIssue.UNAVAILABLE -> Unit
    }
}

private fun openBluetoothSettings() {
    NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { url ->
        UIApplication.sharedApplication.openURL(url)
    }
}

/** Pas de bouton retour système : l'écran de détail affiche le sien dans la barre de titre. */
@Composable
actual fun ObdBackHandler(onBack: () -> Unit) = Unit

/** Pas de notification de service de premier plan sur iOS. */
@Composable
actual fun RequestNotificationPermissionOnce() = Unit
