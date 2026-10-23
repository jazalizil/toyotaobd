package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.obd.ObdTransport
import kotlinx.coroutines.flow.Flow

data class BtDevice(val name: String, val address: String, val bonded: Boolean) {
    /** Nom typique d'un adaptateur OBD (ELM327, Vgate, OBDLink...). */
    val looksLikeObd: Boolean
        get() = OBD_NAME_HINTS.any { name.contains(it, ignoreCase = true) }

    private companion object {
        val OBD_NAME_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI")
    }
}

/**
 * Accès aux adaptateurs OBD sans fil. Implémentations : Bluetooth classique RFCOMM/SPP
 * (Android, [com.example.toyotaobd.bluetooth.AndroidBluetoothRepository]) et Bluetooth Low
 * Energy GATT (iOS, [com.example.toyotaobd.bluetooth.BleBluetoothRepository]).
 *
 * Les méthodes supposent que [hasPermissions] est vrai : sans autorisation, les appels
 * échouent (SecurityException sur Android, CBManagerAuthorization refusée sur iOS).
 */
interface BluetoothRepository {
    /** L'appareil dispose d'une radio Bluetooth. */
    val isAvailable: Boolean

    /** La radio est allumée. */
    val isEnabled: Boolean

    /** Autorisations à demander à l'utilisateur (vide sur iOS : demandées par le système). */
    val requiredPermissions: Array<String>

    fun hasPermissions(): Boolean

    /** Adaptateurs déjà appairés (Android) ou déjà connus du système. */
    fun pairedDevices(): List<BtDevice>

    /** Recherche des appareils à proximité ; la recherche s'arrête quand le flux est annulé. */
    fun discover(): Flow<BtDevice>

    /**
     * Ouvre la liaison série vers l'adaptateur à l'adresse [address] (adresse MAC sur Android,
     * identifiant UUID sur iOS).
     */
    suspend fun connect(address: String): ObdTransport
}
