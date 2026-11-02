package com.example.toyotaobd.bluetooth.ble

import com.example.toyotaobd.bluetooth.BtDevice
import com.example.toyotaobd.obd.ObdTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Périphérique BLE découvert pendant un scan, avec la façon de s'y connecter. Masque la
 * librairie BLE sous-jacente (Kable) : **inversion de dépendances**, la logique métier
 * ([com.example.toyotaobd.bluetooth.KableBluetoothRepository]) ne dépend que de ce port.
 */
interface BlePeripheral {
    val device: BtDevice

    /** Établit la connexion et renvoie le lien série vers l'adaptateur. */
    suspend fun connect(): ObdTransport
}

/** Accès au scan BLE (port). Implémentation : [KableBleAdapter]. */
interface BleAdapter {
    /**
     * Flux des périphériques découverts ; le scan démarre à la collecte et s'arrête quand le
     * flux est annulé.
     */
    fun scan(): Flow<BlePeripheral>
}

/**
 * Cache des périphériques déjà vus (par adresse), pour se connecter à un appareil listé
 * sans relancer un scan. Protégé par un [Mutex] : partageable entre coroutines sur toutes
 * les cibles KMP.
 */
class PeripheralCache {
    private val mutex = Mutex()
    private val peripherals = mutableMapOf<String, BlePeripheral>()

    suspend fun put(address: String, peripheral: BlePeripheral) {
        mutex.withLock { peripherals[address] = peripheral }
    }

    suspend fun get(address: String): BlePeripheral? =
        mutex.withLock { peripherals[address] }
}
