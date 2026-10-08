package com.example.toyotaobd.dev.sim

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Valeurs physiques du véhicule simulé à l'instant [t] (secondes depuis la connexion).
 * Cycle de conduite de 90 s : arrêt, accélération, croisière, décélération, arrêt.
 */
internal class SimState(val t: Double, hybrid: Boolean, clearedAt: Double?) {
    private val phase = t % 90

    val accelerating = phase >= 10 && phase < 30
    private val cruising = phase >= 30 && phase < 60
    private val braking = phase >= 60 && phase < 75

    val speed: Double = when {
        phase < 10 -> 0.0
        phase < 30 -> (phase - 10) / 20 * 70
        phase < 60 -> 70 + 5 * sin(phase / 3)
        phase < 75 -> 70 * (1 - (phase - 60) / 15)
        else -> 0.0
    }

    /** Sur une hybride, le moteur thermique s'arrête à basse vitesse. */
    val engineOn = !hybrid || accelerating || speed > 45

    val rpm = when {
        !engineOn -> 0.0
        speed < 1 -> 720 + 30 * noise(1)
        else -> 1100 + speed * 22 + (if (accelerating) 700 else 0) + 40 * noise(2)
    }
    val load = if (!engineOn) 0.0 else (if (accelerating) 68.0 else if (cruising) 34.0 else 21.0) + 3 * noise(3)
    val throttle = if (!engineOn) 0.0 else (if (accelerating) 42.0 else if (cruising) 19.0 else 13.0) + noise(4)
    val coolant = min(89.0, 35 + t * 0.45)
    val intake = 24 + 4 * sin(t / 40)
    val maf = rpm * load / 100 * 0.011
    val manifoldPressure = if (engineOn) 28 + load * 0.7 else 101.0
    val timing = if (engineOn) 8 + rpm / 220 + noise(5) else 0.0
    val shortTermTrim = 2 * noise(6)
    val longTermTrim = 2.3
    val catalyst = 380 + load * 4
    val fuelRate = maf * 3600 / (14.7 * 745)
    val transmissionFluid = min(82.0, 30 + t * 0.3)
    val hybridBattery = 55 + 8 * sin(t / 30)

    // Système hybride : le courant est positif en décharge (traction), négatif en recharge (freinage).
    val hvCurrent = when {
        !hybrid -> 0.0
        accelerating -> 60 + 10 * noise(8)
        braking -> -45 + 5 * noise(9)
        cruising -> -8 + 3 * noise(10)
        else -> 4.0 // à l'arrêt : climatisation, 12 V
    }
    val hvVoltage = 225 - 0.25 * hvCurrent
    val boostedVoltage = when {
        accelerating -> 600.0
        speed > 1 -> 400.0
        else -> hvVoltage + 5
    }
    val motorRpm = speed * 95
    val motorTorque = when {
        accelerating -> 110 + 10 * noise(11)
        braking -> -80.0
        cruising -> 25.0
        else -> 0.0
    }
    val regenTorque = if (braking) -120.0 else 0.0

    /** MG1 tourne à l'envers quand le moteur thermique est arrêté et que la voiture roule. */
    val generatorRpm = (if (engineOn) rpm * 3.6 else 0.0) - speed * 40
    val generatorTorque = if (engineOn) -load * 0.5 else 0.0
    val inverterTemp = min(48.0, 25 + t * 0.08)
    val motorTemp = min(70.0, 30 + t * 0.1)
    val inverterCoolant = min(45.0, 22 + t * 0.06)
    val batteryTemp = min(33.0, 24 + t * 0.02)
    val transmissionOil = min(70.0, 20 + t * 0.15)
    val steeringAngle = 30 * sin(t / 7)
    val distanceSinceClear = if (clearedAt == null) 1234 + t / 60 else (t - clearedAt) / 60
    val voltage = if (engineOn || hybrid) 14.1 + 0.1 * noise(7) else 12.4

    /** Bruit déterministe dans [-1, 1], pour des valeurs qui bougent sans aléatoire. */
    private fun noise(seed: Int) = sin(t * (1.7 + seed) + seed)
}

internal fun byte(value: Double): List<Int> = listOf(value.roundToInt().coerceIn(0, 0xFF))

internal fun word(value: Double): List<Int> {
    val w = value.roundToInt().coerceIn(0, 0xFFFF)
    return listOf(w shr 8, w and 0xFF)
}

/** Mot de 16 bits en complément à deux (courant de 1F9A). */
internal fun signedWord(value: Double): List<Int> = word((value.roundToInt().coerceIn(-0x8000, 0x7FFF) and 0xFFFF).toDouble())

/** Températures de la batterie Toyota : 2 octets, 255,9 °C sur 65535, décalées de 50 °C. */
internal fun batteryTemp(celsius: Double): List<Int> = word((celsius + 50) * 65_535 / 255.9)

/**
 * Calculateur simulé. Les codes défaut sont stockés bruts (2 octets SAE J2012) : 0x0420 = P0420.
 */
internal class SimEcu(
    val canRequestId: Int,
    val legacyAddress: Int,
    /** PID du mode 01 ; 0x01 (statut) et les PID de capacités (0x00, 0x20...) sont gérés automatiquement. */
    private val pids: Map<Int, (SimState) -> List<Int>>,
    stored: List<Int> = emptyList(),
    pending: List<Int> = emptyList(),
    private val permanent: List<Int>? = null,
    private val vin: String? = null,
    /** Services 21/22 : requête hexadécimale (ex. `221E1C`) → octets de données après l'écho. */
    private val enhanced: Map<String, (SimState) -> List<Int>> = emptyMap(),
) {
    val canResponseId: Int get() = canRequestId + 8

    private val stored = stored.toMutableList()
    private val pending = pending.toMutableList()

    private val supported: Set<Int> = buildSet {
        add(0x01)
        addAll(pids.keys)
        for (base in 0x20..0xE0 step 0x20) if (pids.keys.any { it > base }) add(base)
    }

    /** Messages renvoyés pour une requête (plusieurs en ISO 9141 quand ils ne tiennent pas en une trame). */
    fun answer(request: List<Int>, physical: Boolean, legacy: Boolean, state: SimState): List<List<Int>> {
        val sid = request[0]
        return when (sid) {
            0x01 -> request.getOrNull(1)?.let { pid -> mode01(pid, state)?.let { listOf(listOf(0x41, pid) + it) } }.orEmpty()
            0x03 -> dtcMessages(0x43, stored, legacy)
            0x07 -> dtcMessages(0x47, pending, legacy)
            0x0A -> permanent?.let { dtcMessages(0x4A, it, legacy) }.orEmpty()
            0x04 -> {
                stored.clear()
                pending.clear()
                listOf(listOf(0x44))
            }
            0x09 -> if (request.getOrNull(1) == 0x02 && vin != null) vinMessages(vin, legacy) else emptyList()
            0x21, 0x22 -> when {
                !physical -> emptyList()
                else -> {
                    // Identifiant inconnu : 0x31 (hors plage) ; aucun PID constructeur : 0x11 (service non supporté).
                    val key = request.joinToString("") { "%02X".format(it) }
                    val data = enhanced[key]?.invoke(state)
                    val refusal = if (enhanced.isEmpty()) 0x11 else 0x31
                    listOf(if (data != null) listOf(sid + 0x40) + request.drop(1) + data else listOf(0x7F, sid, refusal))
                }
            }
            else -> if (physical) listOf(listOf(0x7F, sid, 0x11)) else emptyList()
        }
    }

    private fun mode01(pid: Int, state: SimState): List<Int>? = when {
        pid % 0x20 == 0 -> if (pid == 0 || pid in supported) supportedBitmap(pid) else null
        pid == 0x01 -> listOf((if (stored.isNotEmpty()) 0x80 else 0) or stored.size, 0x07, 0x65, 0x00)
        else -> pids[pid]?.invoke(state)
    }

    private fun supportedBitmap(base: Int): List<Int> = List(4) { byteIndex ->
        (0 until 8).fold(0) { acc, bit ->
            if (base + byteIndex * 8 + bit + 1 in supported) acc or (0x80 shr bit) else acc
        }
    }

    private fun dtcMessages(sid: Int, codes: List<Int>, legacy: Boolean): List<List<Int>> {
        fun bytes(list: List<Int>) = list.flatMap { listOf(it shr 8, it and 0xFF) }
        if (!legacy) return listOf(listOf(sid, codes.size) + bytes(codes))
        // ISO 9141 : 3 codes par message, complétés par des zéros, sans compteur.
        return codes.chunked(3).ifEmpty { listOf(emptyList()) }
            .map { chunk -> listOf(sid) + bytes(chunk + List(3 - chunk.size) { 0 }) }
    }

    private fun vinMessages(vin: String, legacy: Boolean): List<List<Int>> {
        val chars = vin.map { it.code }
        if (!legacy) return listOf(listOf(0x49, 0x02, 0x01) + chars)
        return (listOf(0, 0, 0) + chars).chunked(4).mapIndexed { i, chunk -> listOf(0x49, 0x02, i + 1) + chunk }
    }
}

/** Véhicule simulé : protocole, calculateurs et état partagé (effacement des codes). */
internal class SimVehicle(
    /** Numéro de protocole ELM327 : '6' = CAN 11 bits 500k, '3' = ISO 9141-2. */
    val protocol: Char,
    private val hybrid: Boolean,
    private val ecus: List<SimEcu>,
    val ignitionOn: Boolean = true,
) {
    private var clearedAt: Double? = null

    fun state(t: Double) = SimState(t, hybrid, clearedAt)

    /** [target] : identifiant CAN du calculateur visé, ou null pour une requête de diffusion (7DF). */
    fun respond(request: List<Int>, target: Int?, legacy: Boolean, t: Double): List<Pair<SimEcu, List<Int>>> {
        if (request.firstOrNull() == 0x04) clearedAt = t
        val state = state(t)
        val recipients = if (target == null) ecus else ecus.filter { it.canRequestId == target }
        return recipients.flatMap { ecu ->
            ecu.answer(request, physical = target != null, legacy = legacy, state = state).map { ecu to it }
        }
    }
}

/** Véhicules proposés par le simulateur. */
internal object SimVehicles {
    const val HYBRID_VIN = "JTDZN3EU0SM000001"

    private val enginePids: Map<Int, (SimState) -> List<Int>> = mapOf(
        0x04 to { s -> byte(s.load * 255 / 100) },
        0x05 to { s -> byte(s.coolant + 40) },
        0x06 to { s -> byte(s.shortTermTrim * 128 / 100 + 128) },
        0x07 to { s -> byte(s.longTermTrim * 128 / 100 + 128) },
        0x0B to { s -> byte(s.manifoldPressure) },
        0x0C to { s -> word(s.rpm * 4) },
        0x0D to { s -> byte(s.speed) },
        0x0E to { s -> byte((s.timing + 64) * 2) },
        0x0F to { s -> byte(s.intake + 40) },
        0x10 to { s -> word(s.maf * 100) },
        0x11 to { s -> byte(s.throttle * 255 / 100) },
        0x1F to { s -> word(s.t) },
        0x2F to { _ -> byte(62.0 * 255 / 100) },
        0x31 to { s -> word(s.distanceSinceClear) },
        0x33 to { _ -> listOf(101) },
        0x3C to { s -> word((s.catalyst + 40) * 10) },
        0x42 to { s -> word(s.voltage * 1000) },
        0x46 to { _ -> byte(18.0 + 40) },
        0x49 to { s -> byte((14 + s.throttle * 0.9) * 255 / 100) },
        0x5E to { s -> word(s.fuelRate * 20) },
    )

    /** HSD 3 (Prius 3, Auris) : service 21 sur 7E2, batterie de 14 blocs comprise. */
    private val hsd3Pids: Map<String, (SimState) -> List<Int>> = mapOf(
        "2161" to { s -> List(3) { byte(s.motorTemp + 40)[0] } + word(s.generatorRpm + 32_768) },
        "2162" to { s -> List(3) { byte(s.motorTemp + 45)[0] } + word(s.motorRpm + 32_768) },
        "2167" to { s -> word((s.generatorTorque + 4_096) * 8) + word((s.generatorTorque + 4_096) * 8) + listOf(0) },
        "2168" to { s -> word((s.motorTorque + 4_096) * 8) + word((s.motorTorque + 4_096) * 8) + listOf(0) },
        "2170" to { s -> byte(s.inverterTemp + 40) + byte(s.inverterTemp + 40) + byte(s.inverterTemp + 45) + listOf(0) },
        "2171" to { s -> byte(s.inverterTemp + 42) + byte(s.inverterTemp + 42) + byte(s.inverterTemp + 47) + listOf(0) },
        "2174" to { s ->
            List(4) { byte(s.inverterTemp + 43)[0] } + listOf(0) + word(s.hvVoltage * 2) + word(s.boostedVoltage * 2)
        },
        "2175" to { s -> listOf(0x20) + word(3_375.0) + byte(s.inverterCoolant + 40) + listOf(0xC5, 0xC0) },
        // 14 blocs (le 5e plus faible), batterie 12 V, puis tension totale /10 : 32 octets, 5 trames.
        "2181" to { s ->
            List(14) { i -> word((s.hvVoltage / 14 - if (i == 4) 0.12 else 0.0) * 65_535 / 79.99) }.flatten() +
                word((s.voltage + 40) * 65_535 / 79.9) + word(s.hvVoltage * 10)
        },
        "2187" to { s -> batteryTemp(s.batteryTemp - 3) + batteryTemp(s.batteryTemp) + batteryTemp(s.batteryTemp + 1) + batteryTemp(s.batteryTemp - 1) },
        "2192" to { s ->
            word((s.hvVoltage / 14 - 0.12) * 65_535 / 79.99) + listOf(4) + word((s.hvVoltage / 14 + 0.05) * 65_535 / 79.99) +
                listOf(13, 14) + List(8) { 0 }
        },
        "2195" to { _ -> List(14) { i -> if (i == 4) 0x1A else 0x16 } },
        "218E" to { s -> byte(if (s.batteryTemp > 30) 70.0 else 0.0) + listOf(0x00) },
        "2198" to { s ->
            word((s.hvCurrent + 327.68) * 100) + byte((-25.0 + 64) * 2) + byte((27.0 + 64) * 2) + byte(1.5 * 2) +
                List(3) { byte(s.hybridBattery * 2)[0] }
        },
    )

    /** TNGA (Lexus UX 250h) : service 22 sur 7D2, tel que relevé par OBDb sur un UX 2023. */
    private val tngaPids: Map<String, (SimState) -> List<Int>> = mapOf(
        "221F5B" to { s -> byte(s.hybridBattery * 255 / 100) },
        "221F9A" to { s -> listOf(0x0E, 0x00) + word(s.hvVoltage * 64) + signedWord(s.hvCurrent * 10) },
        "22106C" to { s -> byte(s.hybridBattery * 2) + byte(63.0 * 2) + byte(47.0 * 2) },
        // 9 octets (2 trames) : régime (A, B), octets inconnus, couple (G, H).
        "2210A1" to { s -> word(s.motorRpm + 32_768) + listOf(0x7F, 0xEC, 0x03, 0x00) + word((s.motorTorque + 4_096) * 8) + listOf(0x04) },
        "221069" to { s -> word((s.regenTorque + 4_096) * 8) + word((s.regenTorque + 4_096) * 8) },
        "2210A4" to { s -> List(3) { byte(s.inverterTemp + 38)[0] } },
        "2210A5" to { s -> byte(s.inverterTemp + 40) + byte(s.inverterTemp + 36) + byte(s.inverterTemp + 48) },
        "2210A7" to { s -> byte(s.inverterTemp + 41) + byte(s.inverterTemp + 41) + listOf(0x35, 0x46) },
        "2210B0" to { s -> word(s.hvVoltage * 2) + word(s.boostedVoltage * 2) },
        "2210B2" to { s -> byte(s.inverterCoolant + 40) },
        "221814" to { s -> batteryTemp(s.batteryTemp) + batteryTemp(s.batteryTemp + 1.2) + batteryTemp(s.batteryTemp + 0.3) },
        "221802" to { s -> listOf(0x42, 0x03) + byte(if (s.batteryTemp > 30) 70.0 else 0.0) + listOf(0x03) },
        "221040" to { s -> listOf(0x8B, 0x60) + byte(s.transmissionOil + 40) },
        "22106E" to { _ -> byte(0.65 * 20) },
    )

    /** Calculateur de freinage 7B0 (réponses 7B8, hors du filtre OBD) : angle du volant en 2147. */
    private fun brakes() = SimEcu(
        canRequestId = 0x7B0,
        legacyAddress = 0x28,
        pids = emptyMap(),
        enhanced = mapOf("2147" to { s -> listOf(0x00, 0x00, 0x71) + word((s.steeringAngle + 3_276.8) * 10) + listOf(0x00, 0x01) }),
    )

    /** Toyota hybride récente (CAN) : moteur 7E0, boîte 7E1, calculateur hybride 7E2. */
    fun hybridCan(ignitionOn: Boolean = true) = SimVehicle(
        protocol = '6',
        hybrid = true,
        ignitionOn = ignitionOn,
        ecus = listOf(
            SimEcu(
                canRequestId = 0x7E0,
                legacyAddress = 0x10,
                pids = enginePids,
                stored = listOf(0x0420),
                pending = listOf(0x0171),
                permanent = listOf(0x0420),
                vin = HYBRID_VIN,
                enhanced = mapOf(
                    // Réponse multi-trames (22 octets) pour exercer le réassemblage ISO-TP.
                    "2101" to { s ->
                        byte(s.coolant + 40) + byte(s.intake + 40) + word(s.rpm * 4) + byte(s.speed) +
                            List(15) { i -> (i * 37 + 11) and 0xFF }
                    },
                ),
            ),
            SimEcu(
                canRequestId = 0x7E1,
                legacyAddress = 0x18,
                pids = emptyMap(),
                enhanced = mapOf(
                    "221E1C" to { s -> word(s.transmissionFluid * 80) },
                    "2182" to { s -> byte(s.transmissionFluid + 40) + listOf(0) + byte(s.transmissionFluid + 45) + listOf(0) },
                ),
            ),
            SimEcu(
                canRequestId = 0x7E2,
                legacyAddress = 0x1A,
                pids = mapOf(0x5B to { s -> byte(s.hybridBattery * 255 / 100) }),
                stored = listOf(0x0A80),
                enhanced = hsd3Pids,
            ),
            brakes(),
        ),
    )

    const val UX_VIN = "JTHP9JBH0K2000001"

    /**
     * Lexus UX 250h (TNGA) : moteur 7E0 ; la partie OBD du système hybride (7E2) ne donne que la
     * charge (PID 5B) ; les données détaillées sont sur 7D2 (service 22). Pas de calculateur 747.
     */
    fun uxHybrid() = SimVehicle(
        protocol = '6',
        hybrid = true,
        ecus = listOf(
            SimEcu(canRequestId = 0x7E0, legacyAddress = 0x10, pids = enginePids, vin = UX_VIN),
            SimEcu(canRequestId = 0x7E2, legacyAddress = 0x1A, pids = mapOf(0x5B to { s -> byte(s.hybridBattery * 255 / 100) })),
            SimEcu(canRequestId = 0x7D2, legacyAddress = 0x1A, pids = emptyMap(), enhanced = tngaPids),
            brakes(),
        ),
    )

    /** Toyota ancienne (≈ 2002) en ISO 9141-2 : un seul calculateur, pas de VIN ni de codes permanents. */
    fun legacyIso9141() = SimVehicle(
        protocol = '3',
        hybrid = false,
        ecus = listOf(
            SimEcu(
                canRequestId = 0x7E0,
                legacyAddress = 0x10,
                pids = enginePids.filterKeys { it <= 0x11 && it != 0x10 },
                stored = listOf(0x0420, 0x0300, 0x0171, 0x0135),
            ),
        ),
    )
}
