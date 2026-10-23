@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.example.toyotaobd.bluetooth

import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.TransportIOException
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteType
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.cancellation.CancellationException

/**
 * Services « UART » des adaptateurs ELM327 BLE, par (service, notification, écriture).
 * La première entrée dont le service existe est utilisée.
 */
private val UART_CANDIDATES = listOf(
    // Nordic UART Service (nombreux adaptateurs récents).
    Triple("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", "6E400003-B5A3-F393-E0A9-E50E24DCCA9E", "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
    // Clones ELM327 BLE courants (FFF0) : les deux orientations existent selon le modèle.
    Triple("FFF0", "FFF1", "FFF2"),
    Triple("FFF0", "FFF2", "FFF1"),
    // Modules HM-10 (FFE0) : une seule caractéristique, notification et écriture.
    Triple("FFE0", "FFE1", "FFE1"),
    // Veepeak OBDCheck BLE+ (UART Texas Instruments).
    Triple("F0000110-0451-4000-B000-000000000000", "F0000111-0451-4000-B000-000000000000", "F0000112-0451-4000-B000-000000000000"),
    // Autres modules BLE courants (18F0).
    Triple("18F0", "2AF1", "2AF0"),
)

private val SERVICE_IDS = UART_CANDIDATES.map { it.first }.toSet()

/** Écriture avec accusé de réception (CBCharacteristicWriteTypeWithResponse). */
private val WRITE_WITH_RESPONSE: CBCharacteristicWriteType = 0

/** Liaison GATT : le profil « UART » exposé par les adaptateurs ELM327 Bluetooth Low Energy. */
private class BleTransport(
    private val central: CBCentralManager,
    private val peripheral: CBPeripheral,
) : ObdTransport {

    /** Délégué du périphérique : un objet CoreBluetooth séparé (les supertypes Kotlin et Objective-C ne se mélangent pas). */
    private val delegate = PeripheralDelegate()

    /** Octets reçus des notifications, en attente de lecture. */
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    /** Terminée quand la connexion GATT est établie (ou a échoué). */
    val connected = CompletableDeferred<Unit>()

    /** Terminée quand les services de l'adaptateur sont connus (ou ont échoué). */
    val services = CompletableDeferred<CBService>()

    /** Terminée quand les caractéristiques UART sont connues (ou ont échoué). */
    val characteristics = CompletableDeferred<Pair<CBCharacteristic, CBCharacteristic>>()

    /** Terminée quand les notifications sont activées (ou ont échoué). */
    val notifying = CompletableDeferred<Unit>()

    private var notifyCharacteristic: CBCharacteristic? = null
    private var writeCharacteristic: CBCharacteristic? = null

    /** À installer comme délégué du périphérique avant la connexion. */
    fun attach() {
        peripheral.delegate = delegate
    }

    // --- CBPeripheralDelegateProtocol ----------------------------------------------------

    private inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            if (didDiscoverServices != null) {
                fail(services, didDiscoverServices)
                return
            }
            val service = peripheral.services.orEmpty()
                .mapNotNull { it as? CBService }
                .firstOrNull { it.UUID.UUIDString.uppercase() in SERVICE_IDS }
            if (service != null) {
                services.complete(service)
            } else {
                services.completeExceptionally(TransportIOException("Service UART introuvable sur l'adaptateur"))
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            if (error != null) {
                fail(characteristics, error)
                return
            }
            val chars = didDiscoverCharacteristicsForService.characteristics.orEmpty().mapNotNull { c ->
                (c as? CBCharacteristic)?.let { it.UUID.UUIDString.uppercase() to it }
            }
            val pair = UART_CANDIDATES.firstNotNullOfOrNull { (_, notifyId, writeId) ->
                val notify = chars.firstOrNull { it.first == notifyId }?.second
                val write = chars.firstOrNull { it.first == writeId }?.second
                if (notify != null && write != null) notify to write else null
            }
            if (pair != null) {
                notifyCharacteristic = pair.first
                writeCharacteristic = pair.second
                characteristics.complete(pair)
            } else {
                characteristics.completeExceptionally(TransportIOException("Caractéristiques UART introuvables sur l'adaptateur"))
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) fail(notifying, error) else notifying.complete(Unit)
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            if (error != null) {
                incoming.close(TransportIOException("Réception BLE : ${error.localizedDescription}"))
                return
            }
            val data = didUpdateValueForCharacteristic.value ?: return
            val bytes = ByteArray(data.length.toInt())
            if (bytes.isNotEmpty()) {
                data.bytes?.let { src -> bytes.usePinned { dst -> memcpy(dst.addressOf(0), src, bytes.size.toULong()) } }
            }
            if (bytes.isNotEmpty()) incoming.trySend(bytes)
        }

        override fun peripheral(peripheral: CBPeripheral, didModifyServices: List<*>) {
            // Services invalidés (adaptateur hors de portée) : la liaison est perdue.
            incoming.close(TransportIOException("Liaison BLE perdue"))
        }
    }

    // --- ObdTransport --------------------------------------------------------------------

    override suspend fun read(buffer: ByteArray): Int {
        val chunk = try {
            incoming.receive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportIOException("Liaison BLE fermée", e)
        }
        chunk.copyInto(buffer, 0, 0, minOf(chunk.size, buffer.size))
        return minOf(chunk.size, buffer.size)
    }

    override suspend fun write(data: ByteArray) {
        val characteristic = writeCharacteristic ?: throw TransportIOException("Liaison BLE non prête")
        withContext(Dispatchers.Main) {
            peripheral.writeValue(
                toNSData(data),
                forCharacteristic = characteristic,
                type = WRITE_WITH_RESPONSE,
            )
        }
    }

    override fun close() {
        incoming.close()
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.Main) { central.cancelPeripheralConnection(peripheral) }
        }
    }

    // --- Erreurs -------------------------------------------------------------------------

    fun failOnConnect(error: NSError) {
        connected.completeExceptionally(TransportIOException("Connexion BLE : ${error.localizedDescription}"))
    }

    fun onConnected() {
        connected.complete(Unit)
    }

    fun onDisconnected(error: NSError?) {
        val cause = error?.let { TransportIOException("Liaison BLE perdue : ${it.localizedDescription}") }
        incoming.close(cause ?: TransportIOException("Liaison BLE fermée par l'adaptateur"))
        connected.completeExceptionally(cause ?: TransportIOException("Liaison BLE fermée"))
    }

    private fun <T> fail(deferred: CompletableDeferred<T>, error: NSError) {
        deferred.completeExceptionally(TransportIOException(error.localizedDescription))
    }

    private fun toNSData(data: ByteArray): NSData = data.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
    }
}

/**
 * Accès au Bluetooth Low Energy (CoreBluetooth). iOS ne propose pas le Bluetooth classique :
 * cette implémentation s'adresse aux adaptateurs ELM327 BLE exposant un service « UART »
 * (Nordic UART, FFF0, FFE0/HM-10, TI UART des Veepeak, 18F0...).
 */
class BleBluetoothRepository : BluetoothRepository {

    /** Délégué du gestionnaire central : route les événements de connexion. */
    private inner class ManagerDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) = Unit

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            (didConnectPeripheral.delegate as? BleTransport)?.onConnected()
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?,
        ) {
            (didFailToConnectPeripheral.delegate as? BleTransport)?.onDisconnected(error)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            (didDisconnectPeripheral.delegate as? BleTransport)?.onDisconnected(error)
        }
    }

    private val delegate = ManagerDelegate()

    /** Créé au premier usage : la création déclenche la demande d'autorisation Bluetooth. */
    private val manager: CBCentralManager by lazy { CBCentralManager(delegate, null) }

    override val isAvailable: Boolean = true

    override val isEnabled: Boolean
        get() = manager.state == CBManagerStatePoweredOn

    override val requiredPermissions: Array<String> = emptyArray()

    override fun hasPermissions(): Boolean =
        CBManager.authorization == CBManagerAuthorizationAllowedAlways

    /** iOS n'a pas d'appairage système : la liste est vide, la recherche suffit. */
    override fun pairedDevices(): List<BtDevice> = emptyList()

    override fun discover(): Flow<BtDevice> = callbackFlow {
        val scanner = object : NSObject(), CBCentralManagerDelegateProtocol {
            override fun centralManagerDidUpdateState(central: CBCentralManager) {
                if (central.state == CBManagerStatePoweredOn) {
                    central.scanForPeripheralsWithServices(null, null)
                } else {
                    close(TransportIOException("Bluetooth indisponible ou non autorisé"))
                }
            }

            override fun centralManager(
                central: CBCentralManager,
                didDiscoverPeripheral: CBPeripheral,
                advertisementData: Map<Any?, *>,
                RSSI: NSNumber,
            ) {
                val name = didDiscoverPeripheral.name ?: return
                trySend(BtDevice(name, didDiscoverPeripheral.identifier.UUIDString, bonded = false))
            }
        }
        val central = CBCentralManager(scanner, null)
        awaitClose { central.stopScan() }
    }

    override suspend fun connect(address: String): ObdTransport {
        val transport = withTimeout(CONNECT_TIMEOUT_MS) {
            val peripheral = withContext(Dispatchers.Main) {
                manager // crée le gestionnaire (et la demande d'autorisation) au besoin
                val uuid = NSUUID(uUIDString = address)
                manager.retrievePeripheralsWithIdentifiers(listOf(uuid))
                    .firstOrNull() as? CBPeripheral
                    ?: throw TransportIOException("Adaptateur introuvable : $address")
            }
            val transport = BleTransport(manager, peripheral)
            withContext(Dispatchers.Main) {
                transport.attach()
                manager.connectPeripheral(peripheral, null)
            }
            transport.connected.await()
            withContext(Dispatchers.Main) { peripheral.discoverServices(null) }
            val service = transport.services.await()
            withContext(Dispatchers.Main) { peripheral.discoverCharacteristics(null, forService = service) }
            val (notify, _) = transport.characteristics.await()
            withContext(Dispatchers.Main) { peripheral.setNotifyValue(true, forCharacteristic = notify) }
            transport.notifying.await()
            transport
        }
        return transport
    }

    companion object {
        /** 15 s pour se connecter, découvrir les services et activer les notifications. */
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
