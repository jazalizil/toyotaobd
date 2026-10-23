package com.example.toyotaobd.ui

import androidx.compose.runtime.Composable

/**
 * Corrige un problème Bluetooth : demande les autorisations (Android) ou ouvre les réglages
 * du système (iOS, où CoreBluetooth demande l'autorisation à la première utilisation).
 */
@Composable
expect fun rememberBluetoothIssueFixer(): (BluetoothIssue) -> Unit

/** Retour système (bouton physique ou geste Android, bouton de la barre de titre sinon). */
@Composable
expect fun ObdBackHandler(onBack: () -> Unit)

/**
 * Demande une fois l'autorisation de notification du service de connexion (Android 13+) :
 * facultative, refusée, la connexion reste maintenue mais la notification est masquée.
 */
@Composable
expect fun RequestNotificationPermissionOnce()
