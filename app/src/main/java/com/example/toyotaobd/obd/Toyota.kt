package com.example.toyotaobd.obd

/** Calculateur adressable en CAN 11 bits : requêtes vers [requestId], réponses depuis requestId + 8. */
data class Ecu(val requestId: String, val name: String) {
    val responseId: String get() = "%03X".format(requestId.toInt(16) + 8)
}

/**
 * PID constructeur (services 21/22) lu sur un calculateur précis.
 *
 * Les octets A, B, ... de la formule commencent après l'écho de la requête :
 * `61 82` pour `2182`, `62 1E 1C` pour `221E1C`.
 */
data class EnhancedPid(
    val id: String,
    val name: String,
    val header: String,
    val command: String,
    val formula: String,
    val unit: String,
    val note: String? = null,
    val builtIn: Boolean = false,
)

/** Résultat d'une lecture de PID constructeur : octets bruts toujours affichés à côté de la valeur. */
data class EnhancedReading(
    val payload: List<Int> = emptyList(),
    val value: Double? = null,
    val error: String? = null,
)

object Toyota {
    val ENGINE = Ecu("7E0", "Moteur (ECM)")
    val TRANSMISSION = Ecu("7E1", "Transmission (TCM)")
    val HYBRID = Ecu("7E2", "Hybride (HV ECU)")

    /** Hybrides TNGA (≈ 2016+ : Prius 4, C-HR, Corolla, RAV4, Lexus UX...) : service 22 (UDS). */
    val HYBRID_UDS = Ecu("7D2", "Hybride TNGA")
    val HV_BATTERY_UDS = Ecu("747", "Batterie HV TNGA")

    /** Freinage, ABS et contrôle de stabilité (angle du volant, freinage régénératif coopératif). */
    val BRAKES = Ecu("7B0", "Freinage (ABS/VSC)")

    val ECUS = listOf(ENGINE, TRANSMISSION, HYBRID, HYBRID_UDS, HV_BATTERY_UDS)

    /** Calculateurs du système hybride (HSD 3, TNGA et sa batterie). */
    val HYBRID_ECUS = setOf(HYBRID, HYBRID_UDS, HV_BATTERY_UDS)

    const val BROADCAST_HEADER = "7DF"

    /**
     * Préréglages issus de la communauté (Torque, forums). Les adresses et formules
     * varient selon le modèle, l'année et la boîte : toujours vérifier avec les octets bruts.
     */
    val PRESETS = listOf(
        EnhancedPid(
            id = "toyota-atf-221e1c",
            name = "Température ATF (service 22)",
            header = TRANSMISSION.requestId,
            command = "221E1C",
            formula = "((A*256)+B)/80",
            unit = "°C",
            note = "Modèles récents (ex. 4Runner, Tacoma). À vérifier selon le modèle.",
            builtIn = true,
        ),
        EnhancedPid(
            id = "toyota-atf-2182",
            name = "Température ATF (service 21)",
            header = TRANSMISSION.requestId,
            command = "2182",
            formula = "A-40",
            unit = "°C",
            note = "Modèles plus anciens. L'octet utile varie (A ou C) : à vérifier selon le modèle.",
            builtIn = true,
        ),
    )

    /** Vérifie la saisie d'un PID personnalisé ; renvoie le message d'erreur, ou null si tout est valide. */
    fun validate(header: String, command: String, formula: String): String? {
        if (!Regex("^[0-9A-Fa-f]{3}$").matches(header)) return "En-tête : 3 chiffres hexadécimaux (ex. 7E0)"
        if (!Regex("^([0-9A-Fa-f]{2}){2,4}$").matches(command)) return "Commande : service + identifiant en hexadécimal (ex. 2182)"
        return try {
            Formula(formula)
            null
        } catch (e: FormulaException) {
            "Formule : ${e.message}"
        }
    }

    /** Nom lisible du calculateur à partir de l'identifiant de réponse (CAN) ou de l'adresse source (ISO/KWP). */
    fun ecuName(responseId: String): String = when (responseId.uppercase()) {
        "7E8", "10" -> "Moteur"
        "7E9", "18" -> "Transmission"
        "7EA", "7DA" -> "Hybride"
        "74F" -> "Batterie hybride"
        "7B8" -> "Freinage"
        "" -> "Calculateur"
        else -> "Calculateur $responseId"
    }
}
