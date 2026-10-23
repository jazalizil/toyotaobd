package com.example.toyotaobd.obd

import com.example.toyotaobd.obd.Pids.MIN_SPEED_KMH
import com.example.toyotaobd.util.fmt


/** Requête constructeur (services 21/22) adressée à un calculateur : sa réponse porte souvent plusieurs valeurs. */
data class EnhancedRequest(val ecu: Ecu, val command: String)

/**
 * Valeur calculée à partir d'autres PID, sans requête, après chaque cycle d'interrogation.
 * [available] : le calcul est possible avec les PID interrogés sur ce véhicule ; [compute] reçoit
 * les dernières lectures (code → valeur) et rend null quand la valeur n'a pas de sens.
 */
class Derivation(val available: (Set<Int>) -> Boolean, val compute: (Map<Int, Double?>) -> Double?)

/**
 * Valeur du tableau de bord.
 *
 * Sans [request], PID standard du mode 01 (SAE J1979) : [decode] reçoit les [bytes] octets A, B, ...
 * de la réponse. Avec [request], valeur constructeur : [decode] reçoit toutes les données qui suivent
 * l'écho (`61 xx`, `62 xx xx`) et [bytes] est le nombre d'octets dont il a besoin. Son [code],
 * à partir de 0x100, ne sert qu'à l'identifier (tableau de bord enregistré, historique).
 *
 * Avec [derivation], valeur calculée à partir d'autres PID (code à partir de 0x200, pas de [decode]).
 *
 * [range] est la plage usuelle sur une voiture : échelle des jauges et amplitude minimale des courbes.
 * [slow] : valeur qui évolue lentement (températures...), lue une fois sur cinq. [mirrorOf] : PID du
 * mode 01 dont cette valeur constructeur est la copie (1F5B = 5B) ; elle n'est lue que si le véhicule
 * ne l'annonce pas.
 */
data class Pid(
    val code: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val decimals: Int = 0,
    val range: ClosedFloatingPointRange<Double>,
    val request: EnhancedRequest? = null,
    val slow: Boolean = false,
    val mirrorOf: Int? = null,
    val derivation: Derivation? = null,
    val decode: (List<Int>) -> Double = { Double.NaN },
) {
    val command: String = request?.command ?: "01%02X".fmt(code)

    /** Motif préparé une fois ; le séparateur décimal reste celui de la langue au moment de l'appel. */
    private val pattern = "%.${decimals}f"

    fun format(value: Double): String = pattern.fmt(value)

    /** Valeur constructeur tirée des données qui suivent l'écho, ou null si la réponse est trop courte ou inexploitable. */
    fun decodeEnhanced(payload: List<Int>): Double? =
        if (payload.size >= bytes) decode(payload).takeIf { it.isFinite() } else null
}

object Pids {
    private fun word(d: List<Int>) = (d[0] * 256 + d[1]).toDouble()
    private fun trim(d: List<Int>) = (d[0] - 128) * 100.0 / 128

    val ENGINE_LOAD = Pid(0x04, "Charge moteur", "%", 1, range = 0.0..100.0) { it[0] * 100.0 / 255 }
    val COOLANT_TEMP = Pid(0x05, "Liquide de refroidissement", "°C", 1, range = 0.0..120.0) { it[0] - 40.0 }
    val STFT_B1 = Pid(0x06, "Correction court terme B1", "%", 1, 1, range = -25.0..25.0, decode = ::trim)
    val LTFT_B1 = Pid(0x07, "Correction long terme B1", "%", 1, 1, range = -25.0..25.0, decode = ::trim)
    val STFT_B2 = Pid(0x08, "Correction court terme B2", "%", 1, 1, range = -25.0..25.0, decode = ::trim)
    val LTFT_B2 = Pid(0x09, "Correction long terme B2", "%", 1, 1, range = -25.0..25.0, decode = ::trim)
    val INTAKE_MAP = Pid(0x0B, "Pression collecteur", "kPa", 1, range = 0.0..120.0) { it[0].toDouble() }
    val RPM = Pid(0x0C, "Régime moteur", "tr/min", 2, range = 0.0..7_000.0) { word(it) / 4 }
    val SPEED = Pid(0x0D, "Vitesse", "km/h", 1, range = 0.0..200.0) { it[0].toDouble() }
    val TIMING_ADVANCE = Pid(0x0E, "Avance à l'allumage", "°", 1, 1, range = -10.0..50.0) { it[0] / 2.0 - 64 }
    val INTAKE_TEMP = Pid(0x0F, "Air d'admission", "°C", 1, range = -20.0..80.0) { it[0] - 40.0 }
    val MAF = Pid(0x10, "Débit d'air (MAF)", "g/s", 2, 2, range = 0.0..150.0) { word(it) / 100 }
    val THROTTLE = Pid(0x11, "Papillon", "%", 1, range = 0.0..100.0) { it[0] * 100.0 / 255 }
    val RUN_TIME = Pid(0x1F, "Temps depuis démarrage", "s", 2, range = 0.0..3_600.0) { word(it) }
    val DISTANCE_MIL = Pid(0x21, "Distance avec voyant allumé", "km", 2, range = 0.0..1_000.0) { word(it) }
    val FUEL_LEVEL = Pid(0x2F, "Niveau de carburant", "%", 1, range = 0.0..100.0) { it[0] * 100.0 / 255 }
    val DISTANCE_SINCE_CLEAR = Pid(0x31, "Distance depuis effacement", "km", 2, range = 0.0..5_000.0) { word(it) }
    val BAROMETRIC = Pid(0x33, "Pression atmosphérique", "kPa", 1, range = 70.0..105.0) { it[0].toDouble() }
    val CATALYST_TEMP_B1S1 = Pid(0x3C, "Catalyseur B1S1", "°C", 2, range = 0.0..900.0) { word(it) / 10 - 40 }
    val MODULE_VOLTAGE = Pid(0x42, "Tension calculateur", "V", 2, 2, range = 10.0..16.0) { word(it) / 1000 }
    val AMBIENT_TEMP = Pid(0x46, "Température extérieure", "°C", 1, range = -20.0..45.0) { it[0] - 40.0 }
    /**
     * Position relative de la pédale : 0 % au repos. Le 49 (« position D ») donne la tension brute du
     * capteur, qui démarre à ~16 % sur Toyota ; le 5A est la même mesure remise à zéro.
     */
    val PEDAL = Pid(0x5A, "Pédale d'accélérateur", "%", 1, range = 0.0..100.0) { it[0] * 100.0 / 255 }
    val OIL_TEMP = Pid(0x5C, "Huile moteur", "°C", 1, range = 0.0..140.0) { it[0] - 40.0 }
    val FUEL_RATE = Pid(0x5E, "Débit de carburant", "L/h", 2, 1, range = 0.0..20.0) { word(it) / 20 }

    /**
     * Consommation instantanée : débit de carburant (L/h) rapporté à la vitesse (km/h). Le débit vient
     * du PID 5E, ou à défaut du débit d'air (MAF), comme sur le Lexus UX qui ne fournit pas le 5E.
     * Pas de valeur sous [MIN_SPEED_KMH] : à l'arrêt, des litres aux 100 km n'ont pas de sens.
     */
    val FUEL_ECONOMY = Pid(
        0x200, "Consommation instantanée", "L/100 km", 0, 1, range = 0.0..20.0,
        derivation = Derivation(
            available = { SPEED.code in it && (FUEL_RATE.code in it || MAF.code in it) },
            compute = ::fuelEconomy,
        ),
    )

    private const val MIN_SPEED_KMH = 5.0

    private fun fuelEconomy(values: Map<Int, Double?>): Double? {
        val speed = values[SPEED.code]?.takeIf { it >= MIN_SPEED_KMH } ?: return null
        val litresPerHour = values[FUEL_RATE.code] ?: values[MAF.code]?.let(::fuelRateFromMaf) ?: return null
        return litresPerHour / speed * 100
    }

    /** Débit d'essence estimé (L/h) à partir du débit d'air (g/s) : 14,7 g d'air par g d'essence, 745 g/L. */
    fun fuelRateFromMaf(gramsPerSecond: Double): Double = gramsPerSecond * 3_600 / (14.7 * 745)

    /**
     * « Hybrid/EV battery pack remaining charge » : état de charge de la batterie hybride (et non son
     * usure, malgré l'ancien nom « remaining life »). Annoncé par le calculateur hybride (7EA).
     */
    val HYBRID_BATTERY = Pid(0x5B, "Charge batterie hybride", "%", 1, range = 0.0..100.0) { it[0] * 100.0 / 255 }

    /** Données hybrides : mode 01 et valeurs constructeur Toyota/Lexus. */
    val HYBRID: List<Pid> = ToyotaHybrid.TNGA + ToyotaHybrid.HSD3

    /**
     * Valeur du système hybride, quelle que soit sa section : la charge (PID 5B) et tout ce que lisent
     * les calculateurs hybrides (puissance, moteur électrique... rangés dans les infos principales compris).
     */
    fun isHybridValue(pid: Pid): Boolean = pid == HYBRID_BATTERY || pid.request?.ecu in Toyota.HYBRID_ECUS

    /**
     * Infos principales : conduite et énergie. Charge et flux de la batterie hybride : puissance en
     * TNGA, courant en HSD 3 (qui ne donne pas la puissance).
     */
    val MAIN: List<Pid> = listOf(
        RPM,
        ToyotaHybrid.MOTOR_RPM,
        SPEED,
        FUEL_ECONOMY,
        FUEL_RATE,
        FUEL_LEVEL,
        PEDAL,
        ToyotaHybrid.STEERING_ANGLE,
        HYBRID_BATTERY,
        ToyotaHybrid.MOTOR_TORQUE,
        ToyotaHybrid.REGEN_TORQUE,
        ToyotaHybrid.SOC,
        ToyotaHybrid.HV_POWER,
        ToyotaHybrid.HSD3_HV_CURRENT
    )

    /** Infos thermiques : moteur à essence et son calculateur (mode 01). */
    val ENGINE: List<Pid> = listOf(
        COOLANT_TEMP, ENGINE_LOAD, THROTTLE, INTAKE_TEMP, MAF, INTAKE_MAP, TIMING_ADVANCE,
        STFT_B1, LTFT_B1, STFT_B2, LTFT_B2, OIL_TEMP, CATALYST_TEMP_B1S1, AMBIENT_TEMP, BAROMETRIC,
        MODULE_VOLTAGE, RUN_TIME, DISTANCE_SINCE_CLEAR, DISTANCE_MIL,
    )

    /**
     * PIDs affichés sur le tableau de bord, dans l'ordre d'affichage : infos principales, hybrides,
     * puis thermiques (ordre du widget). Seuls ceux auxquels le véhicule répond sont affichés.
     */
    val DASHBOARD = MAIN + (HYBRID - MAIN.toSet()) + ENGINE

    /**
     * Décode la réponse à `0100`, `0120`, ... : 4 octets dont chaque bit indique si le PID
     * correspondant est supporté (bit 7 de A = PID base+1).
     */
    fun decodeSupported(base: Int, bitmap: List<Int>): Set<Int> = buildSet {
        bitmap.take(4).forEachIndexed { byteIndex, byte ->
            for (bit in 0 until 8) {
                if (byte and (0x80 shr bit) != 0) add(base + byteIndex * 8 + bit + 1)
            }
        }
    }
}

/** Statut des moniteurs (PID 01 01) : voyant moteur et nombre de codes confirmés. */
data class MonitorStatus(val milOn: Boolean, val dtcCount: Int) {
    companion object {
        fun decode(a: Int) = MonitorStatus(milOn = a and 0x80 != 0, dtcCount = a and 0x7F)
    }
}
