package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.bluetooth.ble.BleAdapter
import com.example.toyotaobd.bluetooth.ble.BlePeripheral
import com.example.toyotaobd.bluetooth.ble.PeripheralCache
import com.example.toyotaobd.bluetooth.ports.BluetoothPermissions
import com.example.toyotaobd.bluetooth.ports.BluetoothRadio
import com.example.toyotaobd.bluetooth.ports.PairedDevicesProvider
import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.TransportIOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [BluetoothRepository] au-dessus d'un [BleAdapter] (Kable en production, fake dans les tests).
 *
 * **Responsabilité unique** : orchestrer la découverte, la liste des appareils appairés et la
 * connexion ; l'état de la radio, les autorisations et la liste appairée sont délégués aux
 * ports plateforme ([BluetoothRadio], [BluetoothPermissions], [PairedDevicesProvider]), et
 * tout le BLE est délégué à [BleAdapter] (**inversion de dépendances**).
 *
 * Les périphériques découverts sont mis en cache ([PeripheralCache]) : se connecter à un
 * appareil déjà vu ne relance pas de scan ; sinon un scan borné ([scanTimeout]) le recherche.
 */
class KableBluetoothRepository(
    private val ble: BleAdapter,
    private val radio: BluetoothRadio,
    private val permissions: BluetoothPermissions,
    private val pairedDevices: PairedDevicesProvider,
    private val scanTimeout: Duration = DEFAULT_SCAN_TIMEOUT,
    private val cache: PeripheralCache = PeripheralCache(),
) : BluetoothRepository {

    override val isAvailable: Boolean get() = radio.isAvailable
    override val isEnabled: Boolean get() = radio.isEnabled
    override val radioState: StateFlow<BluetoothRadio.State> get() = radio.state
    override val requiredPermissions: Array<String> get() = permissions.required

    override fun hasPermissions(): Boolean = permissions.isGranted()

    override fun pairedDevices(): List<BtDevice> =
        pairedDevices.paired().sortedWith(compareByDescending<BtDevice> { it.looksLikeObd }.thenBy { it.name })

    override fun discover(): Flow<BtDevice> {
        val bondedAddresses = pairedDevices.paired().mapTo(mutableSetOf()) { it.address }
        return ble.scan()
            .onEach { cache.put(it.device.address, it) }
            .map { it.device.copy(bonded = it.device.address in bondedAddresses) }
    }

    override suspend fun connect(address: String): ObdTransport {
        val peripheral = cache.get(address)
            ?: scanFor(address)
            ?: throw TransportIOException("Adaptateur Bluetooth introuvable à proximité : $address")
        return peripheral.connect()
    }

    /** Scan borné à la recherche d'un appareil précis (appareil appairé non encore annoncé). */
    private suspend fun scanFor(address: String): BlePeripheral? =
        withTimeoutOrNull(scanTimeout) {
            ble.scan().firstOrNull { it.device.address == address }
        }

    companion object {
        /** 10 s de scan pour retrouver un appareil appairé qui n'a pas encore été annoncé. */
        val DEFAULT_SCAN_TIMEOUT: Duration = 10.seconds
    }
}
