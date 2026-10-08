package com.example.toyotaobd.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/** Erreur OBD fonctionnelle (véhicule muet, protocole non supporté...). La liaison reste ouverte. */
class ObdException(message: String) : Exception(message)

/**
 * Informations établies pendant l'initialisation. [supportedPids] : PID du mode 01 annoncés par les
 * calculateurs et valeurs constructeur ([Pid.request]) auxquelles le véhicule a répondu.
 */
data class VehicleInfo(
    val adapterVersion: String,
    val protocol: ObdProtocol,
    val supportedPids: Set<Int>,
    val vin: String?,
)

/** Requêtes OBD de haut niveau au-dessus de l'ELM327. */
class ObdClient(private val elm: Elm327) {

    var protocol: ObdProtocol = ObdProtocol.AUTO
        private set

    /**
     * Initialise l'adaptateur puis le dialogue avec le véhicule.
     * Les en-têtes sont activés (ATH1) une fois le protocole connu, pour distinguer
     * les calculateurs qui répondent à une même requête (moteur + hybride, moteur + boîte...).
     */
    suspend fun initialize(protocolChoice: ProtocolChoice, onStep: (String) -> Unit = {}): VehicleInfo {
        onStep("Réinitialisation de l'adaptateur…")
        val version = elm.send("ATZ", 5_000).split('\r', '\n').map { it.trim() }
            .firstOrNull { it.startsWith("ELM", ignoreCase = true) } ?: "ELM327 (version inconnue)"
        for (command in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP${protocolChoice.atSpArgument}")) {
            elm.send(command)
        }

        onStep("Recherche du protocole véhicule (contact mis ?)…")
        val probe = elm.send("0100", 25_000)
        if (ResponseParser.parse(probe, HeaderFormat.NONE, "0100").none { it.data.take(2) == listOf(0x41, 0x00) }) {
            val error = ResponseParser.errorOf(probe, "0100") ?: "réponse inattendue"
            throw ObdException("Le véhicule ne répond pas ($error). Vérifiez que le contact est mis.")
        }
        protocol = ObdProtocol.fromDpn(ResponseParser.lines(elm.send("ATDPN")).lastOrNull().orEmpty())
            ?: throw ObdException("Protocole non identifié")
        elm.send("ATH1")

        onStep("Lecture des capacités du véhicule…")
        val supported = readSupportedPids()
        val vin = optional { readVin() }
        // Les valeurs constructeur (hybride) n'ont pas de liste de capacités : chaque requête est essayée une fois.
        val enhanced = if (protocol.headerFormat == HeaderFormat.CAN_11) {
            onStep("Recherche des données hybrides…")
            optional { readSupportedEnhanced(Pids.DASHBOARD, supported) }.orEmpty()
        } else {
            emptySet()
        }
        return VehicleInfo(version, protocol, supported + enhanced, vin)
    }

    /**
     * Étape facultative de l'initialisation : null si elle échoue (délai, réponse inattendue...).
     * L'annulation et la perte de liaison interrompent la connexion.
     */
    private inline fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Commande manuelle (console) : texte brut de la réponse. */
    suspend fun raw(command: String, timeoutMs: Long = 5_000): String = elm.send(command, timeoutMs)

    suspend fun query(command: String, timeoutMs: Long = Elm327.DEFAULT_TIMEOUT_MS): List<EcuMessage> =
        ResponseParser.parse(elm.send(command, timeoutMs), protocol.headerFormat, command)

    /** PIDs supportés (union des calculateurs), en chaînant 0100 → 0120 → 0140... */
    suspend fun readSupportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            query("01%02X".format(base))
                .filter { it.data.size >= 6 && it.data[0] == 0x41 && it.data[1] == base }
                .forEach { supported += Pids.decodeSupported(base, it.data.subList(2, 6)) }
            if ((base + 0x20) !in supported) break
            base += 0x20
        }
        return supported
    }

    /** Valeur d'un PID mode 01, en privilégiant le calculateur moteur (plus petit identifiant). */
    suspend fun readPid(pid: Pid): Double? {
        val payload = query(pid.command)
            .sortedBy { it.ecu }
            .firstOrNull { it.data.size >= 2 + pid.bytes && it.data[0] == 0x41 && it.data[1] == pid.code }
            ?.data?.subList(2, 2 + pid.bytes)
            ?: return null
        return pid.decode(payload)
    }

    suspend fun readMonitorStatus(): MonitorStatus? {
        val statuses = query("0101")
            .filter { it.data.size >= 3 && it.data[0] == 0x41 && it.data[1] == 0x01 }
            .map { MonitorStatus.decode(it.data[2]) }
        if (statuses.isEmpty()) return null
        return MonitorStatus(statuses.any { it.milOn }, statuses.sumOf { it.dtcCount })
    }

    suspend fun readDtcs(mode: DtcMode): List<Dtc> =
        Dtc.parse(query(mode.command, 5_000), mode, protocol.isCan)

    /** Efface les codes et l'état des moniteurs (service 04). */
    suspend fun clearDtcs(): Boolean = query("04", 5_000).any { it.data.firstOrNull() == 0x44 }

    /** VIN (service 09 PID 02) : 17 caractères ASCII, en une réponse CAN ou en 5 messages ISO/KWP. */
    suspend fun readVin(): String? {
        val messages = query("0902", 5_000).filter { it.data.take(2) == listOf(0x49, 0x02) }
        val ecu = messages.firstOrNull()?.ecu ?: return null
        val vin = messages.filter { it.ecu == ecu }
            .flatMap { it.data.drop(3) }
            .filter { it in 0x21..0x7E }
            .map { it.toChar() }
            .joinToString("")
        return vin.takeLast(17).takeIf { it.length == 17 }
    }

    /** Tension mesurée par l'adaptateur sur la broche 16 (batterie). */
    suspend fun readBatteryVoltage(): Double? =
        Regex("""(\d+(?:\.\d+)?)\s*V""").find(elm.send("ATRV"))?.groupValues?.get(1)?.toDoubleOrNull()

    /** Lit un PID constructeur sur un calculateur précis (CAN 11 bits uniquement). */
    suspend fun readEnhanced(pid: EnhancedPid): EnhancedReading {
        requireCan11()
        val formula = Formula(pid.formula)
        val ecu = Ecu(pid.header.uppercase(), pid.name)
        val request = with(ResponseParser) { pid.command.hexBytes() }
        if (request.isEmpty()) throw ObdException("Commande invalide : ${pid.command}")

        return addressed(ecu) {
            val raw = send(pid.command, 5_000)
            val message = ResponseParser.parse(raw, HeaderFormat.CAN_11, pid.command)
                .firstOrNull { it.ecu == ecu.responseId }
                ?: return@addressed EnhancedReading(error = ResponseParser.errorOf(raw, pid.command) ?: "Pas de réponse de ${ecu.responseId}")
            decodeEnhanced(message.data, request, formula)
        }
    }

    /**
     * Réponses positives aux [requests] : données après l'écho, par requête (les requêtes sans réponse
     * positive sont absentes). Un seul changement d'en-tête par calculateur ; un calculateur muet
     * (NO DATA, délai dépassé) ou qui refuse le service entier (`7F xx 11`) n'est plus interrogé,
     * alors qu'un identifiant refusé (`7F xx 31`...) n'empêche pas d'essayer les suivants.
     */
    suspend fun readEnhancedPayloads(requests: Collection<EnhancedRequest>): Map<EnhancedRequest, List<Int>> {
        requireCan11()
        val payloads = mutableMapOf<EnhancedRequest, List<Int>>()
        for ((ecu, group) in requests.distinct().groupBy { it.ecu }) {
            addressed(ecu) {
                for (request in group) {
                    // Sans réponse, chaque requête coûterait le délai de l'adaptateur : véhicule non hybride.
                    val raw = try {
                        send(request.command)
                    } catch (e: ElmTimeoutException) {
                        break
                    }
                    val data = ResponseParser.parse(raw, HeaderFormat.CAN_11, request.command)
                        .firstOrNull { it.ecu == ecu.responseId }?.data ?: break
                    if (data.size >= 3 && data[0] == 0x7F && data[2] == NRC_SERVICE_NOT_SUPPORTED) break
                    val echo = with(ResponseParser) { request.command.hexBytes() }
                    payloadAfterEcho(data, echo)?.let { payloads[request] = it }
                }
            }
        }
        return payloads
    }

    /**
     * Valeurs constructeur de [pids] lisibles sur ce véhicule : réponse positive assez longue pour leurs
     * octets. Les copies d'un PID du mode 01 déjà annoncé ([standard]) ne sont pas essayées.
     */
    suspend fun readSupportedEnhanced(pids: List<Pid>, standard: Set<Int> = emptySet()): Set<Int> {
        val candidates = pids.filter { it.request != null && it.mirrorOf !in standard }
        val payloads = readEnhancedPayloads(candidates.mapNotNull { it.request })
        return candidates.filter { pid -> pid.request?.let(payloads::get)?.let(pid::decodeEnhanced) != null }
            .map { it.code }
            .toSet()
    }

    private fun requireCan11() {
        if (protocol.headerFormat != HeaderFormat.CAN_11) {
            throw ObdException("Les PID constructeur nécessitent le protocole CAN 11 bits (protocole actuel : ${protocol.label})")
        }
    }

    /**
     * Exécute [block] avec les requêtes adressées à [ecu]. L'en-tête et le filtre de réception sont
     * restaurés ensuite, sous le même verrou, pour que les requêtes de diffusion (7DF) du tableau de
     * bord continuent de fonctionner.
     */
    private suspend fun <T> addressed(ecu: Ecu, block: suspend ElmSession.() -> T): T {
        // Les réponses 7E8-7EF passent déjà le filtre par défaut de l'ELM327 et le calculateur est
        // choisi par logiciel. Pour les autres adresses (hybride TNGA 7D2, ABS, airbag...), le filtre
        // est posé explicitement, ainsi que le contrôle de flux des réponses multi-trames : il est
        // envoyé à l'adresse du calculateur interrogé (30 00 00 : tout envoyer, sans délai).
        val outsideObd = ecu.requestId.toInt(16) !in 0x7E0..0x7E7
        return elm.exclusive {
            try {
                send("ATSH${ecu.requestId}")
                if (outsideObd) {
                    send("ATCRA${ecu.responseId}")
                    for (command in listOf("ATFCSH${ecu.requestId}", "ATFCSD300000", "ATFCSM1")) send(command)
                }
                block()
            } finally {
                withContext(NonCancellable) {
                    runCatching {
                        send("ATSH${Toyota.BROADCAST_HEADER}")
                        if (outsideObd) {
                            resetReceiveFilter()
                            send("ATFCSM0")
                        }
                    }
                }
            }
        }
    }

    /** Remet l'adaptateur dans l'état attendu par l'application (après des commandes manuelles). */
    suspend fun restoreDefaults() {
        elm.exclusive {
            for (command in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATFCSM0")) send(command)
            resetReceiveFilter()
            if (protocol.headerFormat == HeaderFormat.CAN_11) send("ATSH${Toyota.BROADCAST_HEADER}")
        }
    }

    /** `AT CRA` sans argument n'existe pas sur certains clones : repli sur `AT AR` (réception automatique). */
    private suspend fun ElmSession.resetReceiveFilter() {
        if (send("ATCRA").contains('?')) send("ATAR")
    }

    companion object {
        /** Retire l'écho positif (service + 0x40, puis identifiant) et applique la formule. */
        fun decodeEnhanced(data: List<Int>, request: List<Int>, formula: Formula): EnhancedReading {
            if (data.firstOrNull() == 0x7F) {
                val nrc = data.getOrNull(2)
                return EnhancedReading(data, error = "Réponse négative, code 0x%02X%s".format(nrc ?: 0, nrcLabel(nrc)))
            }
            val payload = payloadAfterEcho(data, request) ?: return EnhancedReading(data, error = "Réponse inattendue")
            return try {
                EnhancedReading(payload, value = formula.evaluate(payload))
            } catch (e: FormulaException) {
                EnhancedReading(payload, error = e.message)
            }
        }

        /** Données qui suivent l'écho positif de [request] (service + 0x40, puis identifiant), ou null. */
        fun payloadAfterEcho(data: List<Int>, request: List<Int>): List<Int>? {
            val echo = listOf(request[0] + 0x40) + request.drop(1)
            return if (data.take(echo.size) == echo) data.drop(echo.size) else null
        }

        private const val NRC_SERVICE_NOT_SUPPORTED = 0x11

        private fun nrcLabel(nrc: Int?) = when (nrc) {
            NRC_SERVICE_NOT_SUPPORTED -> " (service non supporté)"
            0x12 -> " (sous-fonction non supportée)"
            0x13 -> " (longueur incorrecte)"
            0x22 -> " (conditions non remplies)"
            0x31 -> " (identifiant hors plage)"
            0x33 -> " (accès refusé)"
            else -> ""
        }
    }
}
