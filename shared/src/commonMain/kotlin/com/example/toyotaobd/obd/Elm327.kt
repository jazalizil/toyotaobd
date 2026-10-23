package com.example.toyotaobd.obd

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** L'adaptateur n'a pas renvoyé d'invite `>` dans le délai imparti. La liaison reste ouverte. */
class ElmTimeoutException(command: String) : Exception("Pas de réponse à « $command »")

/** Commandes envoyables pendant qu'on détient l'accès exclusif à l'adaptateur. */
interface ElmSession {
    suspend fun send(command: String, timeoutMs: Long = Elm327.DEFAULT_TIMEOUT_MS): String
}

/**
 * Dialogue texte avec un ELM327 : chaque commande est terminée par `\r`, chaque réponse
 * par l'invite `>`. Un seul échange à la fois (verrou), car l'adaptateur n'a qu'un tampon.
 *
 * Une coroutine lit le flux en continu ; c'est la fermeture du transport qui la débloque.
 */
class Elm327(
    private val transport: ObdTransport,
    private val log: (direction: Char, text: String) -> Unit = { _, _ -> },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val responses = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()

    /** Une commande a été envoyée sans que son invite `>` ait été reçue (protégé par [mutex]). */
    private var awaitingPrompt = false

    /** Complété quand la liaison est perdue ou fermée (avec la cause éventuelle). */
    val disconnected = CompletableDeferred<Throwable?>()

    private val session = object : ElmSession {
        override suspend fun send(command: String, timeoutMs: Long) = sendLocked(command, timeoutMs)
    }

    init {
        scope.launch {
            val buffer = StringBuilder()
            val chunk = ByteArray(512)
            try {
                while (isActive) {
                    val n = transport.read(chunk)
                    if (n < 0) throw TransportIOException("Liaison fermée par l'adaptateur")
                    for (i in 0 until n) {
                        when (val c = (chunk[i].toInt() and 0xFF).toChar()) {
                            '>' -> {
                                responses.trySend(buffer.toString())
                                buffer.clear()
                            }
                            '\u0000' -> Unit
                            else -> buffer.append(c)
                        }
                    }
                }
            } catch (e: Exception) {
                val cause = e as? TransportIOException ?: TransportIOException("Liaison perdue", e)
                responses.close(cause)
                disconnected.complete(cause)
            }
        }
    }

    /** Envoie une commande et renvoie le texte brut reçu avant l'invite `>`. */
    suspend fun send(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        mutex.withLock { sendLocked(command, timeoutMs) }

    /** Exécute plusieurs commandes sans qu'aucune autre ne puisse s'intercaler (ex. changement d'en-tête). */
    suspend fun <T> exclusive(block: suspend ElmSession.() -> T): T =
        mutex.withLock { session.block() }

    private suspend fun sendLocked(command: String, timeoutMs: Long): String {
        // La commande précédente a été annulée ou a expiré : sa réponse peut encore arriver.
        // On l'attend (brièvement) pour ne pas l'attribuer à la commande suivante.
        if (awaitingPrompt) {
            receive(STALE_REPLY_TIMEOUT_MS)
            awaitingPrompt = false
        }
        while (responses.tryReceive().isSuccess) Unit
        log('>', command)
        // Reste vrai si la coroutine est annulée ou si le délai expire avant l'invite.
        awaitingPrompt = true
        // Le protocole ELM327 est de l'ASCII : l'UTF-8 y est identique octet par octet.
        transport.write("$command\r".encodeToByteArray())
        val raw = receive(timeoutMs) ?: throw ElmTimeoutException(command)
        awaitingPrompt = false
        log('<', raw.trim().replace('\r', '\n'))
        return raw
    }

    private suspend fun receive(timeoutMs: Long): String? = try {
        withTimeoutOrNull(timeoutMs) { responses.receive() }
    } catch (e: ClosedReceiveChannelException) {
        throw TransportIOException("Liaison fermée", e)
    }

    fun close() {
        disconnected.complete(null)
        runCatching { transport.close() }
        responses.close()
        scope.cancel()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        private const val STALE_REPLY_TIMEOUT_MS = 1_500L
    }
}
