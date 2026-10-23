package com.example.toyotaobd.obd

import com.example.toyotaobd.util.fmt
/** Services OBD de lecture des codes défaut. */
enum class DtcMode(val command: String, val label: String) {
    STORED("03", "Confirmés"),
    PENDING("07", "En attente"),
    PERMANENT("0A", "Permanents"),
    ;

    /** Identifiant de réponse positive : service + 0x40. */
    val responseSid: Int get() = command.toInt(16) + 0x40
}

/** Code défaut, ex. `P0420`, et le calculateur (identifiant de réponse) qui l'a remonté. */
data class Dtc(val code: String, val ecu: String) {
    val description: String? get() = DtcDescriptions[code]

    companion object {
        private const val LETTERS = "PCBU"

        /** Deux octets SAE J2012 → code lisible. Ex. `01 33` → `P0133`, `C1 23` → `U0123`. */
        fun decode(a: Int, b: Int): String =
            "%c%d%X%02X".fmt(LETTERS[a shr 6], (a shr 4) and 0x3, a and 0xF, b)

        /**
         * Extrait les codes des réponses aux services 03/07/0A.
         * En CAN, le premier octet après le service est le nombre de codes ; en ISO 9141/KWP,
         * il n'y a pas de compteur et les messages sont complétés par des `00 00`.
         */
        fun parse(messages: List<EcuMessage>, mode: DtcMode, isCan: Boolean): List<Dtc> =
            messages
                .filter { it.data.firstOrNull() == mode.responseSid }
                .flatMap { message ->
                    val payload = message.data.drop(if (isCan) 2 else 1)
                    payload.chunked(2)
                        .filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
                        .map { Dtc(decode(it[0], it[1]), message.ecu) }
                }
                .distinct()
    }
}

/** Descriptions des codes génériques les plus fréquents (non exhaustif). */
private val DtcDescriptions = mapOf(
    "P0011" to "Calage arbre à cames admission trop avancé (banc 1)",
    "P0012" to "Calage arbre à cames admission trop retardé (banc 1)",
    "P0101" to "Débitmètre d'air (MAF) : plage/performance",
    "P0102" to "Débitmètre d'air (MAF) : signal bas",
    "P0103" to "Débitmètre d'air (MAF) : signal haut",
    "P0113" to "Capteur température air d'admission : signal haut",
    "P0116" to "Capteur température liquide de refroidissement : plage/performance",
    "P0128" to "Thermostat : température de régulation non atteinte",
    "P0133" to "Sonde O2 banc 1 capteur 1 : réponse lente",
    "P0135" to "Sonde O2 banc 1 capteur 1 : circuit de chauffage",
    "P0136" to "Sonde O2 banc 1 capteur 2 : circuit",
    "P0141" to "Sonde O2 banc 1 capteur 2 : circuit de chauffage",
    "P0171" to "Mélange trop pauvre (banc 1)",
    "P0172" to "Mélange trop riche (banc 1)",
    "P0174" to "Mélange trop pauvre (banc 2)",
    "P0175" to "Mélange trop riche (banc 2)",
    "P0300" to "Ratés d'allumage aléatoires/multiples",
    "P0301" to "Ratés d'allumage cylindre 1",
    "P0302" to "Ratés d'allumage cylindre 2",
    "P0303" to "Ratés d'allumage cylindre 3",
    "P0304" to "Ratés d'allumage cylindre 4",
    "P0305" to "Ratés d'allumage cylindre 5",
    "P0306" to "Ratés d'allumage cylindre 6",
    "P0325" to "Capteur de cliquetis 1 : circuit (banc 1)",
    "P0335" to "Capteur position vilebrequin A : circuit",
    "P0340" to "Capteur position arbre à cames A : circuit (banc 1)",
    "P0401" to "Recirculation des gaz (EGR) : débit insuffisant",
    "P0420" to "Efficacité du catalyseur sous le seuil (banc 1)",
    "P0430" to "Efficacité du catalyseur sous le seuil (banc 2)",
    "P0440" to "Système EVAP : dysfonctionnement",
    "P0441" to "Système EVAP : débit de purge incorrect",
    "P0442" to "Système EVAP : petite fuite détectée",
    "P0446" to "Système EVAP : circuit de commande de mise à l'air",
    "P0455" to "Système EVAP : fuite importante détectée",
    "P0456" to "Système EVAP : très petite fuite détectée",
    "P0500" to "Capteur de vitesse véhicule",
    "P0505" to "Système de régulation du ralenti",
    "P0606" to "Processeur du calculateur moteur",
    "P0705" to "Capteur de position de sélecteur de boîte",
    "P0715" to "Capteur de vitesse turbine : circuit",
    "P0741" to "Embrayage du convertisseur de couple : performance / bloqué ouvert",
    "P0A80" to "Remplacer le pack batterie hybride",
)
