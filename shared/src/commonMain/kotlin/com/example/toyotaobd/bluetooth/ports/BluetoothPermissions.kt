package com.example.toyotaobd.bluetooth.ports

/**
 * Autorisations Bluetooth requises par l'application (port plateforme : les permissions
 * d'exécution Android, l'autorisation CoreBluetooth sur iOS demandée par le système).
 */
interface BluetoothPermissions {
    /** Autorisations à demander à l'utilisateur (vide sur iOS). */
    val required: Array<String>

    /** Toutes les autorisations requises sont accordées. */
    fun isGranted(): Boolean
}
