package com.example.toyotaobd.dev.sim

import com.example.toyotaobd.obd.ObdTransport
import com.example.toyotaobd.obd.TransportIOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Remplace la socket Bluetooth : les commandes écrites sont traitées par [emulator] dans une
 * coroutine dédiée, avec les délais d'un vrai adaptateur (multipliés par [latencyScale]), et les
 * réponses sont lues comme sur une socket. [dropAfterMs] simule une coupure de la liaison.
 */
class SimulatedTransport(
    private val emulator: Elm327Emulator,
    private val latencyScale: Double = 1.0,
    dropAfterMs: Long? = null,
) : ObdTransport {

    private val received = Channel<Int>(Channel.UNLIMITED)
    private val commands = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = StringBuilder()

    @Volatile
    private var closed = false

    init {
        // Une seule coroutine consomme les commandes : elles sont traitées dans l'ordre d'écriture.
        scope.launch {
            for (command in commands) {
                val reply = emulator.handle(command)
                delay((reply.delayMs * latencyScale).toLong())
                if (!closed) "${reply.text}>".forEach { received.trySend(it.code) }
            }
        }
        if (dropAfterMs != null) {
            scope.launch {
                delay(dropAfterMs)
                close()
            }
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        val first = received.receive()
        if (first < 0) {
            received.trySend(END) // les lectures suivantes voient aussi la fin du flux
            return -1
        }
        buffer[0] = first.toByte()
        var n = 1
        while (n < buffer.size) {
            val next = received.tryReceive().getOrNull() ?: break
            if (next < 0) {
                received.trySend(END)
                break
            }
            buffer[n++] = next.toByte()
        }
        return n
    }

    override suspend fun write(data: ByteArray) {
        if (closed) throw TransportIOException("Liaison simulée fermée")
        for (byte in data) {
            when (val c = byte.toInt().toChar()) {
                '\r' -> {
                    val command = pending.toString()
                    pending.clear()
                    commands.trySend(command)
                }
                '\n' -> Unit
                else -> pending.append(c)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        received.trySend(END)
    }

    private companion object {
        const val END = -1
    }
}
