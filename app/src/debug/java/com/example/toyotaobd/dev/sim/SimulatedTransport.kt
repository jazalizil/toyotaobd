package com.example.toyotaobd.dev.sim

import com.example.toyotaobd.obd.ObdTransport
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Remplace la socket Bluetooth : les commandes écrites sont traitées par [emulator] sur un fil
 * dédié, avec les délais d'un vrai adaptateur (multipliés par [latencyScale]), et les réponses
 * sont lues comme sur une socket. [dropAfterMs] simule une coupure de la liaison.
 */
internal class SimulatedTransport(
    private val emulator: Elm327Emulator,
    private val latencyScale: Double = 1.0,
    dropAfterMs: Long? = null,
) : ObdTransport {

    private val received = LinkedBlockingQueue<Int>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "elm327-simulator").apply { isDaemon = true } }
    private val pending = StringBuilder()

    @Volatile
    private var closed = false

    init {
        if (dropAfterMs != null) {
            thread(isDaemon = true, name = "elm327-simulator-drop") {
                Thread.sleep(dropAfterMs)
                close()
            }
        }
    }

    override val input: InputStream = object : InputStream() {
        override fun read(): Int = received.take()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val first = received.take()
            if (first < 0) {
                received.put(END) // les lectures suivantes voient aussi la fin du flux
                return -1
            }
            b[off] = first.toByte()
            var n = 1
            while (n < len && (received.peek() ?: END) >= 0) b[off + n++] = received.take().toByte()
            return n
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            if (closed) throw IOException("Liaison simulée fermée")
            when (val c = b.toChar()) {
                '\r' -> {
                    val command = pending.toString()
                    pending.clear()
                    worker.execute { process(command) }
                }
                '\n' -> Unit
                else -> pending.append(c)
            }
        }
    }

    private fun process(command: String) {
        val reply = emulator.handle(command)
        try {
            Thread.sleep((reply.delayMs * latencyScale).toLong())
        } catch (e: InterruptedException) {
            return
        }
        if (!closed) "${reply.text}>".forEach { received.put(it.code) }
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.shutdownNow()
        received.put(END)
    }

    private companion object {
        const val END = -1
    }
}
