package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.bluetooth.ports.BluetoothRadio
import com.example.toyotaobd.obd.ObdTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accès aux adaptateurs OBD sans fil (port de la couche Bluetooth, injecté dans
 * [com.example.toyotaobd.ui.AppDependencies]).
 *
 * Implémentation : Bluetooth Low Energy via la librairie Kable, commune à Android et iOS
 * ([KableBluetoothRepository] au-dessus de [com.example.toyotaobd.bluetooth.ble.BleAdapter]).
 * Les adaptateurs ELM327 BLE exposant un profil « UART » (Nordic UART, FFF0, HM-10/FFE0,
 * UART Texas Instruments des Veepeak, 18F0...) sont reconnus.
 *
 * Les méthodes supposent que [hasPermissions] est vrai : sans autorisation, les appels
 * échouent (SecurityException sur Android, autorisation CoreBluetooth refusée sur iOS).
 */
interface BluetoothRepository {
    /** L'appareil dispose d'une radio Bluetooth. */
    val isAvailable: Boolean

    /** La radio est allumée (lecture ponctanée — l'UI doit collecter [radioState]). */
    val isEnabled: Boolean

    /**
     * État de la radio en flux réactif : le Bluetooth peut être activé/coupé depuis les réglages
     * système pendant que l'application tourne.
     */
    val radioState: StateFlow<BluetoothRadio.State>

    /** Autorisations à demander à l'utilisateur (vide sur iOS : demandées par le système). */
    val requiredPermissions: Array<String>

    fun hasPermissions(): Boolean

    /** Adaptateurs déjà appairés (Android) ou déjà connus du système. */
    fun pairedDevices(): List<BtDevice>

    /** Recherche des appareils à proximité ; la recherche s'arrête quand le flux est annulé. */
    fun discover(): Flow<BtDevice>

    /**
     * Ouvre la liaison vers l'adaptateur à l'adresse [address] (adresse MAC sur Android,
     * identifiant UUID sur iOS).
     */
    suspend fun connect(address: String): ObdTransport
}
