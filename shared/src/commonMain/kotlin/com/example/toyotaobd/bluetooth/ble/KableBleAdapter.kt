@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class) // Identifier = Uuid côté Apple

package com.example.toyotaobd.bluetooth.ble

import com.example.toyotaobd.bluetooth.BtDevice
import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.TransportIOException
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Implémentation Kable (Kotlin Multiplatform : Android + iOS) du port [BleAdapter].
 *
 * Le scan repose sur [Scanner.advertisements] (démarré à la collecte, arrêté à l'annulation) ;
 * la connexion construit un [Peripheral] à partir de l'[Advertisement] reçu au scan, attend
 * la connexion et la découverte des services, puis résout le profil UART de l'adaptateur.
 */
class KableBleAdapter(
    private val scanner: Scanner<Advertisement> = Scanner { },
    private val resolver: UartProfileResolver = DefaultUartProfileResolver,
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
) : BleAdapter {

    override fun scan(): Flow<BlePeripheral> =
        scanner.advertisements.map { advertisement ->
            KableBlePeripheral(advertisement, resolver, connectTimeout)
        }

    companion object {
        /** 15 s pour se connecter et découvrir les services. */
        val DEFAULT_CONNECT_TIMEOUT: Duration = 15.seconds
    }
}

/** Un [BlePeripheral] vu par Kable : la connexion recrée le [Peripheral] depuis l'annonce. */
private class KableBlePeripheral(
    private val advertisement: Advertisement,
    private val resolver: UartProfileResolver,
    private val connectTimeout: Duration,
) : BlePeripheral {

    override val device: BtDevice = BtDevice(
        name = advertisement.name?.takeIf { it.isNotBlank() }
            ?: advertisement.identifier.toString(),
        address = advertisement.identifier.toString(),
        bonded = false, // l'état bonded est posé par le dépôt à partir de la liste appairée
    )

    override suspend fun connect(): ObdTransport {
        val peripheral = Peripheral(advertisement)
        try {
            // connect() suspend jusqu'à la connexion + découverte des services ; on borne l'attente.
            withTimeout(connectTimeout) { peripheral.connect() }
        } catch (e: TimeoutCancellationException) {
            peripheral.close()
            throw TransportIOException(
                "Délai de connexion Bluetooth dépassé (${connectTimeout.inWholeSeconds} s)",
                e,
            )
        } catch (e: CancellationException) {
            peripheral.close()
            throw e
        } catch (e: Exception) {
            peripheral.close()
            throw TransportIOException(
                "Connexion BLE impossible : ${e.message ?: e::class.simpleName}",
                e,
            )
        }
        val services = peripheral.services.value.orEmpty().map { it.toGattService() }
        val uart = resolver.resolve(services)
        if (uart == null) {
            peripheral.close()
            throw TransportIOException("Service UART introuvable sur l'adaptateur « ${device.name} »")
        }
        return KableTransport(peripheral, uart)
    }
}
