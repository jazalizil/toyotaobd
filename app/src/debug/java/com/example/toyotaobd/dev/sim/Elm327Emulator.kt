package com.example.toyotaobd.dev.sim

import java.util.Locale

/** Texte renvoyé par l'adaptateur (sans l'invite `>`) et durée de traitement simulée. */
internal data class SimReply(val text: String, val delayMs: Long)

/**
 * Interpréteur de commandes ELM327 v1.5 : réglages AT (écho, espaces, en-têtes, adressage,
 * filtre de réception, protocole), recherche du protocole, puis mise en forme des réponses
 * du [vehicle] comme le ferait un vrai adaptateur (trames CAN ISO-TP ou messages ISO 9141).
 */
internal class Elm327Emulator(
    private val vehicle: SimVehicle,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = clock()

    private var echo = true
    private var spaces = true
    private var headers = false
    private var linefeeds = false
    private var header = BROADCAST
    private var receiveFilter: Int? = null
    private var protocolSetting = "0"
    private var activeProtocol: Char? = null
    private var lastCommand = ""

    private val isAuto: Boolean get() = protocolSetting == "0" || protocolSetting.startsWith("A")
    private val elapsedSeconds: Double get() = (clock() - startedAt) / 1000.0

    fun handle(input: String): SimReply {
        val echoed = echo
        // Un retour chariot seul répète la dernière commande, comme sur un vrai ELM327.
        val command = input.uppercase().replace(" ", "").ifEmpty { lastCommand }
        lastCommand = command
        val reply = if (command.startsWith("AT")) at(command.removePrefix("AT")) else obd(command)
        val text = (if (echoed) "$input\r" else "") + reply.text + "\r\r"
        return reply.copy(text = if (linefeeds) text.replace("\r", "\r\n") else text)
    }

    private fun at(cmd: String): SimReply {
        fun ok(text: String = "OK") = SimReply(text, 20)
        fun flag(value: Char) = value == '1'
        return when {
            cmd == "Z" -> { reset(); SimReply("\r\rELM327 v1.5", 800) }
            cmd == "WS" -> { reset(); SimReply("\r\rELM327 v1.5", 300) }
            cmd == "D" -> { reset(); ok() }
            cmd == "I" -> ok("ELM327 v1.5")
            cmd == "@1" -> ok("OBDII to RS232 Interpreter")
            Regex("^E[01]$").matches(cmd) -> { echo = flag(cmd[1]); ok() }
            Regex("^L[01]$").matches(cmd) -> { linefeeds = flag(cmd[1]); ok() }
            Regex("^S[01]$").matches(cmd) -> { spaces = flag(cmd[1]); ok() }
            Regex("^H[01]$").matches(cmd) -> { headers = flag(cmd[1]); ok() }
            Regex("^[ST]PA?[0-9A-C]$").matches(cmd) -> {
                protocolSetting = cmd.drop(2)
                activeProtocol = null
                ok()
            }
            cmd == "DPN" -> ok(describeProtocolNumber())
            cmd == "DP" -> ok(PROTOCOL_NAMES[activeProtocol ?: protocolSetting.last()] ?: "AUTO")
            cmd == "RV" -> ok("%.1fV".format(Locale.US, if (vehicle.ignitionOn) vehicle.state(elapsedSeconds).voltage else 12.3))
            cmd == "PC" -> { activeProtocol = null; ok() }
            cmd == "CRA" || cmd == "AR" -> { receiveFilter = null; ok() }
            Regex("^CRA[0-9A-F]{3}$").matches(cmd) -> { receiveFilter = cmd.drop(3).toInt(16); ok() }
            Regex("^SH[0-9A-F]{3}$").matches(cmd) -> { header = cmd.drop(2).toInt(16); ok() }
            Regex("^SH[0-9A-F]{6}$").matches(cmd) -> ok() // en-tête ISO/KWP : ignoré par le simulateur
            // Contrôle de flux : le simulateur envoie toujours toutes les trames.
            Regex("^FCS(H[0-9A-F]{3}|D([0-9A-F]{2}){1,5}|M[0-2])$").matches(cmd) -> ok()
            // Réglages acceptés sans effet sur la simulation (temporisation, formatage CAN...).
            Regex("^(AT[0-2]|ST[0-9A-F]{2}|CAF[01]|CFC[01]|D[01]|M[01]|R[01]|AL|NL)$").matches(cmd) -> ok()
            else -> SimReply("?", 20)
        }
    }

    private fun obd(command: String): SimReply {
        if (!Regex("^([0-9A-F]{2})+$").matches(command)) return SimReply("?", 20)
        val request = command.chunked(2).map { it.toInt(16) }

        var prefix = ""
        var delay = 40L
        if (activeProtocol == null) {
            val requested = protocolSetting.last()
            val reachable = vehicle.ignitionOn && (isAuto || requested == vehicle.protocol)
            if (!reachable) {
                val failure = if (!isAuto && requested in '3'..'5') "BUS INIT: ...ERROR" else "UNABLE TO CONNECT"
                return SimReply((if (isAuto) "SEARCHING...\r" else "") + failure, if (isAuto) 4_000 else 1_500)
            }
            activeProtocol = vehicle.protocol
            val legacyInit = vehicle.protocol in '3'..'5'
            prefix = (if (isAuto) "SEARCHING...\r" else "") + (if (legacyInit) "BUS INIT: ...OK\r" else "")
            delay += if (legacyInit) 2_500 else 1_200
        }

        val legacy = activeProtocol !in '6'..'9'
        val target = if (legacy || header == BROADCAST) null else header
        // Sans AT CRA, l'ELM327 n'affiche que les réponses OBD (7E8 à 7EF).
        val responses = vehicle.respond(request, target, legacy, elapsedSeconds)
            .filter { (ecu, _) -> legacy || ecu.canResponseId == receiveFilter || (receiveFilter == null && ecu.canResponseId in 0x7E8..0x7EF) }
        if (responses.isEmpty()) return SimReply(prefix + "NO DATA", delay + 100)

        val lines = responses.flatMap { (ecu, payload) -> format(ecu, payload, legacy) }
        return SimReply(prefix + lines.joinToString("\r"), delay + 6L * lines.size)
    }

    private fun format(ecu: SimEcu, payload: List<Int>, legacy: Boolean): List<String> = when {
        legacy && headers -> {
            val frame = listOf(0x48, 0x6B, ecu.legacyAddress) + payload
            listOf(hex(frame + (frame.sum() and 0xFF)))
        }
        legacy -> listOf(hex(payload))
        headers -> canFrames(payload).map { "%03X".format(ecu.canResponseId) + separator + hex(it) }
        payload.size <= 7 -> listOf(hex(payload))
        // En-têtes désactivés : longueur totale puis trames numérotées « 0: », « 1: »...
        else -> listOf("%03X".format(payload.size), "0:$separator" + hex(payload.take(6))) +
            payload.drop(6).chunked(7).mapIndexed { i, chunk -> "%X:".format((i + 1) and 0xF) + separator + hex(chunk) }
    }

    /** Découpage ISO 15765-2 : trame unique, ou première trame + trames consécutives, complétées à 8 octets. */
    private fun canFrames(payload: List<Int>): List<List<Int>> {
        fun padded(frame: List<Int>) = frame + List(8 - frame.size) { 0x00 }
        if (payload.size <= 7) return listOf(padded(listOf(payload.size) + payload))
        val first = listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6)
        return listOf(first) + payload.drop(6).chunked(7).mapIndexed { i, chunk -> padded(listOf(0x20 or ((i + 1) and 0xF)) + chunk) }
    }

    private val separator: String get() = if (spaces) " " else ""

    private fun hex(bytes: List<Int>) = bytes.joinToString(separator) { "%02X".format(it) }

    private fun describeProtocolNumber(): String {
        val number = activeProtocol ?: protocolSetting.last()
        return if (isAuto && number != '0') "A$number" else "$number"
    }

    private fun reset() {
        echo = true
        spaces = true
        headers = false
        linefeeds = false
        header = BROADCAST
        receiveFilter = null
        activeProtocol = null
    }

    private companion object {
        const val BROADCAST = 0x7DF

        val PROTOCOL_NAMES = mapOf(
            '3' to "ISO 9141-2",
            '5' to "ISO 14230-4 (KWP FAST)",
            '6' to "ISO 15765-4 (CAN 11/500)",
        )
    }
}
