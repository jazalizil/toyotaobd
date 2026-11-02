@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.toyotaobd.bluetooth.ble

import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.TransportIOException
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch

/**
 * Lien série au-dessus d'un périphérique BLE connecté via Kable : les notifications de la
 * caractéristique « notify » du profil UART alimentent un canal lu par [read], et [write]
 * écrit sur la caractéristique « write » (avec ou sans accusé de réception selon le profil).
 *
 * La perte de connexion (état [State.Disconnected] du périphérique ou erreur d'observation)
 * ferme le canal avec une [TransportIOException] comme cause : [read] la relance alors.
 */
class KableTransport(
    private val peripheral: Peripheral,
    uart: ResolvedUart,
) : ObdTransport {

    private val notifyCharacteristic = characteristicOf(uart.service, uart.notify)
    private val writeCharacteristic = characteristicOf(uart.service, uart.write)
    private val writeType: WriteType = when (uart.writeType) {
        UartWriteType.WITH_RESPONSE -> WriteType.WithResponse
        UartWriteType.WITHOUT_RESPONSE -> WriteType.WithoutResponse
    }

    /** Octets reçus des notifications, en attente de lecture. */
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        // Les deux collecteurs vivent dans le scope du périphérique : annulés par close().
        peripheral.scope.launch {
            try {
                peripheral.observe(notifyCharacteristic).collect { data ->
                    incoming.trySend(data) // canal illimité : trySend ne peut échouer que s'il est fermé
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                incoming.close(TransportIOException("Réception BLE interrompue : ${e.message}", e))
            }
        }
        peripheral.scope.launch {
            peripheral.state.collect { state ->
                if (state is State.Disconnected) {
                    incoming.close(TransportIOException("Liaison BLE perdue"))
                }
            }
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        val chunk = try {
            incoming.receive()
        } catch (e: ClosedReceiveChannelException) {
            return -1 // canal fermé sans erreur : liaison fermée proprement
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportIOException) {
            throw e // cause de fermeture du canal (perte de liaison, erreur d'observation)
        } catch (e: Exception) {
            throw TransportIOException("Liaison BLE fermée", e)
        }
        val n = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, n)
        return n
    }

    override suspend fun write(data: ByteArray) {
        try {
            peripheral.write(writeCharacteristic, data, writeType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TransportIOException("Écriture BLE impossible : ${e.message}", e)
        }
    }

    override fun close() {
        incoming.close()
        // Déconnexion best-effort hors du thread appelant (close n'est pas suspend).
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { peripheral.disconnect() }
            runCatching { peripheral.close() }
        }
    }
}
