package com.example.toyotaobd.obd

import com.example.toyotaobd.util.fmt
/** Un message OBD complet (après réassemblage ISO-TP), émis par un calculateur. */
data class EcuMessage(val ecu: String, val data: List<Int>)

/**
 * Analyse le texte renvoyé par l'ELM327 entre deux invites `>`.
 *
 * Fonctions pures : aucune dépendance Android, testées unitairement.
 */
object ResponseParser {

    private val HEX_LINE = Regex("^[0-9A-F: ]+$")
    private val MULTIFRAME_LENGTH = Regex("^[0-9A-F]{3}$")
    private val MULTIFRAME_INDEX = Regex("^[0-9A-F]:")

    /** Messages d'erreur ELM327 connus (`BUS INIT: ...ERROR` est couvert par `ERROR`). */
    private val ERRORS = listOf(
        "UNABLE TO CONNECT", "NO DATA", "CAN ERROR", "BUS ERROR", "BUS BUSY", "FB ERROR",
        "DATA ERROR", "BUFFER FULL", "LV RESET", "ACT ALERT", "STOPPED", "ERROR",
    )

    /** Lignes utiles de la réponse : sans écho, ni `SEARCHING...`, ni lignes vides. */
    fun lines(raw: String, echo: String? = null): List<String> {
        val echoCompact = echo?.compactHex()
        return raw.replace("\u0000", "")
            .replace("SEARCHING...", "")
            .split('\r', '\n')
            .map { it.trim().trimStart('>').trim().uppercase() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("BUS INIT") && !it.contains("ERROR") }
            .filterNot { echoCompact != null && it.compactHex() == echoCompact }
    }

    /** Erreur signalée par l'adaptateur (`NO DATA`, `?`, ...), ou null si la réponse contient des données. */
    fun errorOf(raw: String, echo: String? = null): String? {
        val lines = lines(raw, echo)
        if (lines.any { HEX_LINE.matches(it) }) return null
        if (lines.any { it == "?" }) return "Commande non reconnue (?)"
        return lines.firstOrNull { line -> ERRORS.any { line.contains(it) } }
            ?: if (lines.isEmpty()) "Réponse vide" else null
    }

    /** Découpe la réponse en messages par calculateur, selon le format d'en-tête actif. */
    fun parse(raw: String, format: HeaderFormat, echo: String? = null): List<EcuMessage> {
        val dataLines = lines(raw, echo).filter { HEX_LINE.matches(it) }
        return when (format) {
            HeaderFormat.NONE -> parseHeadersOff(dataLines)
            HeaderFormat.CAN_11 -> reassembleIsoTp(dataLines, headerChars = 3)
            HeaderFormat.CAN_29 -> reassembleIsoTp(dataLines, headerChars = 8)
            HeaderFormat.LEGACY -> parseLegacy(dataLines)
        }
    }

    /**
     * En-têtes désactivés. En CAN, l'ELM327 formate les réponses multi-trames ainsi :
     * ```
     * 014
     * 0: 49 02 01 31 4E 34
     * 1: 41 4C 33 41 50 37 44
     * ```
     * La première ligne est la longueur totale en hexadécimal.
     */
    private fun parseHeadersOff(lines: List<String>): List<EcuMessage> {
        val messages = mutableListOf<EcuMessage>()
        var multi: MutableList<Int>? = null
        var expected = 0

        fun flush() {
            multi?.let { messages += EcuMessage("", it.take(expected)) }
            multi = null
        }

        for (line in lines) {
            when {
                MULTIFRAME_LENGTH.matches(line) -> {
                    flush()
                    expected = line.toInt(16)
                    multi = mutableListOf()
                }
                MULTIFRAME_INDEX.containsMatchIn(line) && multi != null ->
                    multi!! += line.substringAfter(':').hexBytes()
                else -> {
                    flush()
                    val bytes = line.hexBytes()
                    if (bytes.isNotEmpty()) messages += EcuMessage("", bytes)
                }
            }
        }
        flush()
        return messages
    }

    /**
     * En-têtes activés en CAN : chaque ligne est une trame brute `ID PCI données...`.
     * Les trames sont regroupées par identifiant puis réassemblées selon ISO 15765-2,
     * ce qui permet de distinguer plusieurs calculateurs répondant au même broadcast.
     */
    private fun reassembleIsoTp(lines: List<String>, headerChars: Int): List<EcuMessage> {
        class Pending(val length: Int, val data: MutableList<Int>)

        val messages = mutableListOf<EcuMessage>()
        val pending = linkedMapOf<String, Pending>()

        for (line in lines) {
            val compact = line.compactHex()
            if (compact.length < headerChars + 2) continue
            val ecu = compact.substring(0, headerChars)
            val frame = compact.substring(headerChars).hexBytes()
            if (frame.isEmpty()) continue
            val pci = frame[0]
            when (pci shr 4) {
                0x0 -> {
                    val length = pci and 0x0F
                    messages += EcuMessage(ecu, frame.drop(1).take(length))
                }
                0x1 -> {
                    if (frame.size < 2) continue
                    val length = ((pci and 0x0F) shl 8) or frame[1]
                    pending[ecu] = Pending(length, frame.drop(2).toMutableList())
                }
                0x2 -> {
                    val p = pending[ecu] ?: continue
                    p.data += frame.drop(1)
                    if (p.data.size >= p.length) {
                        messages += EcuMessage(ecu, p.data.take(p.length))
                        pending.remove(ecu)
                    }
                }
                // 0x3 : trame de contrôle de flux, sans données utiles.
            }
        }
        // Messages incomplets (trames perdues) : on rend ce qui a été reçu.
        pending.forEach { (ecu, p) -> messages += EcuMessage(ecu, p.data) }
        return messages
    }

    /** ISO 9141-2 / KWP2000 avec en-têtes : `48 6B 10 41 0D 00 E9` → calculateur `10`, données `41 0D 00`. */
    private fun parseLegacy(lines: List<String>): List<EcuMessage> = lines.mapNotNull { line ->
        val bytes = line.hexBytes()
        if (bytes.size < 5) return@mapNotNull null
        EcuMessage("%02X".fmt(bytes[2]), bytes.subList(3, bytes.size - 1))
    }

    private fun String.compactHex(): String = replace(" ", "").uppercase()

    internal fun String.hexBytes(): List<Int> {
        val compact = compactHex()
        if (compact.length % 2 != 0) return emptyList()
        return compact.chunked(2).map { it.toIntOrNull(16) ?: return emptyList() }
    }
}
