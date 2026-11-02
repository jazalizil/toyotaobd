package com.example.toyotaobd.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import platform.UIKit.UIApplicationOpenSettingsURLString

@Composable
actual fun rememberBluetoothIssueFixer(): (BluetoothIssue) -> Unit {
    // LocalUriHandler (Compose Multiplatform) ouvre l'URL via l'API moderne de la plateforme :
    // UIApplication.open(_:options:completionHandler:) sur iOS, Intent ACTION_VIEW sur Android.
    val uriHandler = LocalUriHandler.current
    return { issue ->
        when (issue) {
            // CoreBluetooth demande l'autorisation à la première utilisation ; refusée définitivement,
            // seul le panneau Bluetooth des réglages permet de la rétablir.
            BluetoothIssue.PERMISSIONS, BluetoothIssue.DISABLED -> uriHandler.openUri(UIApplicationOpenSettingsURLString)
            BluetoothIssue.UNAVAILABLE -> Unit
        }
    }
}

/** Pas de notification de service de premier plan sur iOS. */
@Composable
actual fun RequestNotificationPermissionOnce() = Unit
