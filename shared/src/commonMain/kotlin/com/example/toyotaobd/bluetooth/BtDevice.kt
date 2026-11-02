package com.example.toyotaobd.bluetooth

/**
 * Un appareil Bluetooth vu par l'application. [address] est l'identifiant de connexion :
 * adresse MAC sur Android, UUID CoreBluetooth sur iOS (tous deux représentés par une chaîne
 * via l'[com.juul.kable.Identifier] de Kable).
 */
data class BtDevice(val name: String, val address: String, val bonded: Boolean) {
    /** Nom typique d'un adaptateur OBD (ELM327, Vgate, OBDLink...). */
    val looksLikeObd: Boolean
        get() = OBD_NAME_HINTS.any { name.contains(it, ignoreCase = true) }

    private companion object {
        val OBD_NAME_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI")
    }
}
