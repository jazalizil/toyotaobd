package com.example.toyotaobd.obd

/**
 * Données constructeur des hybrides Toyota/Lexus, interrogées en continu avec le tableau de bord.
 * Deux familles, selon la génération du système hybride :
 *
 * - **TNGA** (≈ 2016+ : Prius 4, C-HR, Corolla, RAV4, Lexus UX/ES/NX) : service 22 sur 7D2
 *   (calculateur hybride) et 747 (batterie, surtout lithium). Sources : signalsets OBDb, comparés
 *   à des trames réelles d'un Lexus UX 250h.
 * - **HSD 3** (≈ 2009-2018 : Prius 3, Auris, Yaris, CT 200h, Prius+) : service 21 sur 7E2, batterie
 *   comprise. Sources : listes Torque de PriusChat, comparées à des trames réelles d'une Auris.
 *
 * S'y ajoute l'angle du volant, lu sur le calculateur de freinage (7B0) de ces mêmes modèles.
 *
 * Les octets A, B, ... suivent l'écho (`62 dd dd` ou `61 ll`). Les valeurs Toyota sont décalées
 * (−32768, /8−4096, −40...) et non signées, sauf le courant de 1F9A (complément à deux, comme le
 * PID 9A de la norme). Les adresses varient selon le modèle : seules les valeurs auxquelles le
 * véhicule répond à la connexion sont affichées. Les codes 0x1xx ne servent qu'à les identifier.
 */
object ToyotaHybrid {
    private fun word(d: List<Int>, i: Int) = (d[i] * 256 + d[i + 1]).toDouble()
    private fun signedWord(d: List<Int>, i: Int) = word(d, i).let { if (it >= 32_768) it - 65_536 else it }

    /** Mots de 16 bits consécutifs (blocs, capteurs) ; un octet impair final est ignoré. */
    private fun words(d: List<Int>) = List(d.size / 2) { word(d, it * 2) }

    /** Plus grand écart, ou NaN (valeur ignorée) si la liste est vide. */
    private fun List<Double>.spread() = if (isEmpty()) Double.NaN else max() - min()
    private fun List<Double>.maxOrNaN() = maxOrNull() ?: Double.NaN
    private fun List<Double>.minOrNaN() = minOrNull() ?: Double.NaN

    // --- TNGA : service 22, calculateur hybride 7D2 ---------------------------------------

    private fun tnga(did: String) = EnhancedRequest(Toyota.HYBRID_UDS, "22$did")

    private val TNGA_SOC = tnga("1F5B")
    private val TNGA_BATTERY = tnga("1F9A")
    private val TNGA_SOC_RANGE = tnga("106C")
    private val TNGA_MOTOR = tnga("10A1")
    private val TNGA_REGEN = tnga("1069")
    private val TNGA_GENERATOR_INVERTER = tnga("10A4")
    private val TNGA_MOTOR_INVERTER = tnga("10A5")
    private val TNGA_BOOST_TEMP = tnga("10A7")
    private val TNGA_BOOST = tnga("10B0")
    private val TNGA_INVERTER_COOLANT = tnga("10B2")
    private val TNGA_BATTERY_TEMPS = tnga("1814")
    private val TNGA_BATTERY_FAN = tnga("1802")
    private val TNGA_TRANSAXLE = tnga("1040")
    private val TNGA_AIR_CONDITIONING = tnga("106E")

    /** Batterie lithium (747) : tensions des éléments, 2 octets chacun. Absent sur l'UX NiMH. */
    private val TNGA_CELLS = EnhancedRequest(Toyota.HV_BATTERY_UDS, "22182E")

    /** Copie du PID 5B, lue seulement si le véhicule n'annonce pas celui-ci. */
    val SOC = Pid(0x101, "Charge batterie hybride", "%", 1, range = 0.0..100.0, request = TNGA_SOC, mirrorOf = 0x5B) {
        it[0] * 100.0 / 255
    }

    /** 1F9A copie le PID 9A : octet de capacités, état de charge, tension (C, D) puis courant (E, F). */
    val HV_VOLTAGE = Pid(0x102, "Tension batterie HV", "V", 6, 1, range = 150.0..350.0, request = TNGA_BATTERY) {
        word(it, 2) / 64
    }

    /** Positif en décharge (traction), négatif en recharge (freinage, moteur thermique). */
    val HV_CURRENT = Pid(0x103, "Courant batterie HV", "A", 6, 1, range = -150.0..150.0, request = TNGA_BATTERY) {
        signedWord(it, 4) / 10
    }

    val HV_POWER = Pid(0x104, "Puissance batterie HV", "kW", 6, 1, range = -40.0..40.0, request = TNGA_BATTERY) {
        word(it, 2) / 64 * signedWord(it, 4) / 10 / 1_000
    }

    val SOC_MAX =
        Pid(0x105, "Charge batterie max", "%", 3, 1, range = 0.0..100.0, request = TNGA_SOC_RANGE, slow = true) {
            it[1] / 2.0
        }
    val SOC_MIN =
        Pid(0x106, "Charge batterie min", "%", 3, 1, range = 0.0..100.0, request = TNGA_SOC_RANGE, slow = true) {
            it[2] / 2.0
        }

    /** Moteur de traction (MG2 probablement : ±16 000 tr/min, jusqu'à ≈ 170 Nm sur l'UX). */
    val MOTOR_RPM = Pid(0x107, "Régime moteur électrique", "tr/min", 2, range = 0.0..16_000.0, request = TNGA_MOTOR) {
        word(it, 0) - 32_768
    }
    val MOTOR_TORQUE = Pid(0x108, "Couple moteur électrique", "Nm", 8, 1, range = 0.0..500.0, request = TNGA_MOTOR) {
        word(it, 6) / 8 - 4_096
    }

    /** Couple réellement freiné par le moteur électrique (C, D ; A, B : couple demandé). OBDb indique kW, à tort. */
    val REGEN_TORQUE = Pid(0x109, "Freinage régénératif", "Nm", 4, 1, range = -300.0..500.0, request = TNGA_REGEN) {
        word(it, 2) / 8 - 4_096
    }

    val MOTOR_INVERTER_TEMP = Pid(
        0x10A, "Onduleur du moteur (MG2)", "°C", 1, range = 0.0..100.0, request = TNGA_MOTOR_INVERTER, slow = true
    ) {
        it[0] - 40.0
    }
    val GENERATOR_INVERTER_TEMP = Pid(
        0x10B,
        "Onduleur du générateur (MG1)",
        "°C",
        1,
        range = 0.0..100.0,
        request = TNGA_GENERATOR_INVERTER,
        slow = true
    ) {
        it[0] - 40.0
    }
    val BOOST_TEMP =
        Pid(0x10C, "Convertisseur élévateur", "°C", 1, range = 0.0..100.0, request = TNGA_BOOST_TEMP, slow = true) {
            it[0] - 40.0
        }

    /** Tension de la batterie (VL) et du bus des onduleurs après élévation (VH, jusqu'à ≈ 650 V). */
    val VL = Pid(0x10D, "Tension avant élévation (VL)", "V", 4, 1, range = 150.0..350.0, request = TNGA_BOOST) {
        word(it, 0) / 2
    }
    val VH = Pid(0x10E, "Tension après élévation (VH)", "V", 4, 1, range = 150.0..650.0, request = TNGA_BOOST) {
        word(it, 2) / 2
    }

    val INVERTER_COOLANT =
        Pid(0x10F, "Liquide de l'onduleur", "°C", 1, range = 0.0..80.0, request = TNGA_INVERTER_COOLANT, slow = true) {
            it[0] - 40.0
        }

    /** Capteurs de température de la batterie (3 sur l'UX, jusqu'à 16), 2 octets chacun. */
    val BATTERY_TEMP_MAX = Pid(
        0x110, "Batterie HV, température max", "°C", 2, range = 0.0..80.0, request = TNGA_BATTERY_TEMPS, slow = true
    ) {
        words(it).map { w -> w * 255.9 / 65_535 - 50 }.maxOrNaN()
    }
    val BATTERY_TEMP_MIN = Pid(
        0x111, "Batterie HV, température min", "°C", 2, range = 0.0..80.0, request = TNGA_BATTERY_TEMPS, slow = true
    ) {
        words(it).map { w -> w * 255.9 / 65_535 - 50 }.minOrNaN()
    }

    val BATTERY_FAN =
        Pid(0x112, "Ventilateur batterie HV", "%", 3, range = 0.0..100.0, request = TNGA_BATTERY_FAN, slow = true) {
            it[2] / 2.0
        }
    val TRANSAXLE_OIL = Pid(
        0x113, "Huile de la transmission hybride", "°C", 3, range = 0.0..120.0, request = TNGA_TRANSAXLE, slow = true
    ) {
        it[2] - 40.0
    }
    val AIR_CONDITIONING = Pid(
        0x114,
        "Compresseur de climatisation",
        "kW",
        1,
        2,
        range = 0.0..5.0,
        request = TNGA_AIR_CONDITIONING,
        slow = true
    ) {
        it[0] / 20.0
    }

    /** Éléments présents : 0 et FFFF signalent un emplacement vide. */
    private fun cells(d: List<Int>) = words(d).filter { it > 0 && it < 0xFFFF }.map { it * 5 / 65_535 }

    val CELL_MIN =
        Pid(0x115, "Élément batterie, tension min", "V", 4, 3, range = 2.5..4.5, request = TNGA_CELLS, slow = true) {
            cells(it).minOrNaN()
        }
    val CELL_MAX =
        Pid(0x116, "Élément batterie, tension max", "V", 4, 3, range = 2.5..4.5, request = TNGA_CELLS, slow = true) {
            cells(it).maxOrNaN()
        }
    val CELL_SPREAD =
        Pid(0x117, "Écart entre éléments", "V", 4, 3, range = 0.0..0.2, request = TNGA_CELLS, slow = true) {
            cells(it).spread()
        }

    // --- HSD 3 : service 21, calculateur hybride 7E2 (batterie comprise) -------------------

    private fun hsd3(pid: String) = EnhancedRequest(Toyota.HYBRID, "21$pid")

    private val HSD3_MG1 = hsd3("61")
    private val HSD3_MG2 = hsd3("62")
    private val HSD3_MG1_TORQUE = hsd3("67")
    private val HSD3_MG2_TORQUE = hsd3("68")
    private val HSD3_MG1_INVERTER = hsd3("70")
    private val HSD3_MG2_INVERTER = hsd3("71")
    private val HSD3_BOOST = hsd3("74")
    private val HSD3_COOLING = hsd3("75")
    private val HSD3_BLOCKS = hsd3("81")
    private val HSD3_BATTERY_TEMPS = hsd3("87")
    private val HSD3_BLOCK_RANGE = hsd3("92")
    private val HSD3_RESISTANCE = hsd3("95")
    private val HSD3_BATTERY = hsd3("98")
    private val HSD3_BATTERY_FAN_REQUEST = hsd3("8E")

    val MG1_RPM = Pid(0x121, "Régime MG1 (générateur)", "tr/min", 5, range = -10_000.0..10_000.0, request = HSD3_MG1) {
        word(it, 3) - 32_768
    }
    val MG1_TEMP =
        Pid(0x122, "Température MG1", "°C", 1, range = 0.0..120.0, request = HSD3_MG1, slow = true) { it[0] - 40.0 }
    val MG2_RPM =
        Pid(0x123, "Régime MG2 (moteur électrique)", "tr/min", 5, range = -2_000.0..14_000.0, request = HSD3_MG2) {
            word(it, 3) - 32_768
        }
    val MG2_TEMP =
        Pid(0x124, "Température MG2", "°C", 1, range = 0.0..120.0, request = HSD3_MG2, slow = true) { it[0] - 40.0 }
    val MG1_TORQUE =
        Pid(0x125, "Couple MG1 (générateur)", "Nm", 2, 1, range = -100.0..100.0, request = HSD3_MG1_TORQUE) {
            word(it, 0) / 8 - 4_096
        }
    val MG2_TORQUE =
        Pid(0x126, "Couple MG2 (moteur électrique)", "Nm", 2, 1, range = -250.0..250.0, request = HSD3_MG2_TORQUE) {
            word(it, 0) / 8 - 4_096
        }
    val MG1_INVERTER_TEMP = Pid(
        0x127, "Onduleur MG1", "°C", 1, range = 0.0..100.0, request = HSD3_MG1_INVERTER, slow = true
    ) { it[0] - 40.0 }
    val MG2_INVERTER_TEMP = Pid(
        0x128, "Onduleur MG2", "°C", 1, range = 0.0..100.0, request = HSD3_MG2_INVERTER, slow = true
    ) { it[0] - 40.0 }

    /** 2174 : températures du convertisseur (A à D), puis VL (F, G) et VH (H, I). */
    val HSD3_BOOST_TEMP = Pid(
        0x129, "Convertisseur élévateur", "°C", 1, range = 0.0..100.0, request = HSD3_BOOST, slow = true
    ) { it[0] - 40.0 }
    val HSD3_VL = Pid(0x12A, "Tension avant élévation (VL)", "V", 9, 1, range = 150.0..350.0, request = HSD3_BOOST) {
        word(
            it, 5
        ) / 2
    }
    val HSD3_VH = Pid(0x12B, "Tension après élévation (VH)", "V", 9, 1, range = 150.0..650.0, request = HSD3_BOOST) {
        word(
            it, 7
        ) / 2
    }

    val HSD3_INVERTER_COOLANT =
        Pid(0x12C, "Liquide de l'onduleur", "°C", 4, range = 0.0..80.0, request = HSD3_COOLING, slow = true) {
            it[3] - 40.0
        }

    /**
     * 2181 : tension de chaque bloc (2 octets), puis batterie 12 V (2 octets) et tension totale VB
     * (2 octets, /10). Le nombre de blocs varie (14 sur Prius et Auris) : il se déduit de la longueur.
     */
    val HSD3_HV_VOLTAGE = Pid(0x12D, "Tension batterie HV", "V", 6, 1, range = 150.0..350.0, request = HSD3_BLOCKS) {
        val blocks = (it.size - 4) / 2
        word(it, blocks * 2 + 2) / 10
    }

    /** 2187 : air d'admission de la batterie (A, B), puis capteurs TB1 à TB3, 2 octets chacun. */
    private fun hsd3BatteryTemps(d: List<Int>) = words(d).drop(1).map { it * 255.9 / 65_535 - 50 }

    val HSD3_BATTERY_TEMP_MAX = Pid(
        0x12E, "Batterie HV, température max", "°C", 4, range = -10.0..60.0, request = HSD3_BATTERY_TEMPS, slow = true
    ) {
        hsd3BatteryTemps(it).maxOrNaN()
    }
    val HSD3_BATTERY_TEMP_MIN = Pid(
        0x12F, "Batterie HV, température min", "°C", 4, range = -10.0..60.0, request = HSD3_BATTERY_TEMPS, slow = true
    ) {
        hsd3BatteryTemps(it).minOrNaN()
    }

    /** 2192 : bloc le plus faible (A, B ; numéro C+1) et le plus fort (D, E ; numéro F+1). */
    private fun blockVolts(d: List<Int>, i: Int) = word(d, i) * 79.99 / 65_535

    val BLOCK_MIN = Pid(
        0x130, "Bloc batterie, tension min", "V", 5, 2, range = 12.0..20.0, request = HSD3_BLOCK_RANGE, slow = true
    ) {
        blockVolts(it, 0)
    }
    val BLOCK_MAX = Pid(
        0x131, "Bloc batterie, tension max", "V", 5, 2, range = 12.0..20.0, request = HSD3_BLOCK_RANGE, slow = true
    ) {
        blockVolts(it, 3)
    }

    /** Indicateur d'usure : au-delà d'environ 1 V au repos, un bloc décroche. */
    val BLOCK_SPREAD =
        Pid(0x132, "Écart entre blocs", "V", 5, 2, range = 0.0..1.5, request = HSD3_BLOCK_RANGE, slow = true) {
            blockVolts(it, 3) - blockVolts(it, 0)
        }

    /** 2195 : résistance interne de chaque bloc, 1 octet en mΩ. */
    val RESISTANCE_MAX = Pid(
        0x133, "Résistance interne max (bloc)", "mΩ", 1, range = 0.0..60.0, request = HSD3_RESISTANCE, slow = true
    ) {
        it.max().toDouble()
    }

    /** 2198 : courant (A, B), limites de charge (C) et de décharge (D), écart de charge (E), charges (F à H). */
    val HSD3_HV_CURRENT = Pid(0x134, "Courant batterie HV", "A", 2, 1, range = -150.0..150.0, request = HSD3_BATTERY) {
        word(it, 0) / 100 - 327.68
    }
    val CHARGE_LIMIT =
        Pid(0x135, "Limite de recharge (Win)", "kW", 3, 1, range = -40.0..0.0, request = HSD3_BATTERY, slow = true) {
            it[2] / 2.0 - 64
        }
    val DISCHARGE_LIMIT =
        Pid(0x136, "Limite de décharge (Wout)", "kW", 4, 1, range = 0.0..40.0, request = HSD3_BATTERY, slow = true) {
            it[3] / 2.0 - 64
        }
    val DELTA_SOC =
        Pid(0x137, "Écart de charge entre blocs", "%", 5, 1, range = 0.0..20.0, request = HSD3_BATTERY, slow = true) {
            it[4] / 2.0
        }

    /** 218E : rapport cyclique du ventilateur (A), puis état du relais (B). */
    val HSD3_BATTERY_FAN = Pid(
        0x138, "Ventilateur batterie HV", "%", 1, range = 0.0..100.0, request = HSD3_BATTERY_FAN_REQUEST, slow = true
    ) {
        it[0] / 2.0
    }

    // --- Châssis : calculateur de freinage 7B0, service 21 (TNGA et HSD 3) ----------------

    /** 2147 : accélérations et lacet, puis angle du volant (D, E), décalé de 3276,8°. */
    val STEERING_ANGLE = Pid(
        0x140, "Angle du volant", "°", 5, 1, range = -720.0..720.0, request = EnhancedRequest(Toyota.BRAKES, "2147")
    ) {
        word(it, 3) / 10 - 3_276.8
    }

    val TNGA = listOf(
        SOC, HV_CURRENT, HV_VOLTAGE, SOC_MAX, SOC_MIN,
        VL, VH, MOTOR_INVERTER_TEMP, GENERATOR_INVERTER_TEMP, BOOST_TEMP, INVERTER_COOLANT,
        BATTERY_TEMP_MAX, BATTERY_TEMP_MIN, BATTERY_FAN, TRANSAXLE_OIL, AIR_CONDITIONING,
        CELL_MIN, CELL_MAX, CELL_SPREAD,
    )

    val HSD3 = listOf(
        HSD3_HV_CURRENT, HSD3_HV_VOLTAGE, MG1_RPM, MG1_TORQUE, MG2_RPM, MG2_TORQUE, HSD3_VL, HSD3_VH,
        MG1_TEMP, MG2_TEMP, MG1_INVERTER_TEMP, MG2_INVERTER_TEMP, HSD3_BOOST_TEMP, HSD3_INVERTER_COOLANT,
        HSD3_BATTERY_TEMP_MAX, HSD3_BATTERY_TEMP_MIN, BLOCK_MIN, BLOCK_MAX, BLOCK_SPREAD, RESISTANCE_MAX,
        CHARGE_LIMIT, DISCHARGE_LIMIT, DELTA_SOC, HSD3_BATTERY_FAN,
    )

    /** Hors système hybride. */
    val CHASSIS = listOf(STEERING_ANGLE)

    val ALL = TNGA + HSD3 + CHASSIS
}
