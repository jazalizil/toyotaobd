package com.example.toyotaobd.obd

/** Format des en-têtes affichés par l'ELM327 quand ATH1 est actif. */
enum class HeaderFormat {
    /** En-têtes désactivés (ATH0). */
    NONE,

    /** CAN 11 bits : `7E8 06 41 0D 00 ...` (identifiant + octet PCI ISO-TP). */
    CAN_11,

    /** CAN 29 bits : `18 DA F1 10 06 41 0D 00 ...`. */
    CAN_29,

    /** ISO 9141-2 / KWP2000 / J1850 : 3 octets d'en-tête, données, puis somme de contrôle. */
    LEGACY,
}

/** Protocoles OBD tels que numérotés par l'ELM327 (`AT DPN`). */
enum class ObdProtocol(val code: Char, val label: String, val headerFormat: HeaderFormat) {
    AUTO('0', "Automatique", HeaderFormat.NONE),
    J1850_PWM('1', "SAE J1850 PWM", HeaderFormat.LEGACY),
    J1850_VPW('2', "SAE J1850 VPW", HeaderFormat.LEGACY),
    ISO_9141_2('3', "ISO 9141-2", HeaderFormat.LEGACY),
    KWP_5BAUD('4', "ISO 14230-4 KWP (init 5 bauds)", HeaderFormat.LEGACY),
    KWP_FAST('5', "ISO 14230-4 KWP (init rapide)", HeaderFormat.LEGACY),
    CAN_11_500('6', "ISO 15765-4 CAN 11 bits 500 kbaud", HeaderFormat.CAN_11),
    CAN_29_500('7', "ISO 15765-4 CAN 29 bits 500 kbaud", HeaderFormat.CAN_29),
    CAN_11_250('8', "ISO 15765-4 CAN 11 bits 250 kbaud", HeaderFormat.CAN_11),
    CAN_29_250('9', "ISO 15765-4 CAN 29 bits 250 kbaud", HeaderFormat.CAN_29),
    J1939('A', "SAE J1939 CAN", HeaderFormat.CAN_29),
    USER1_CAN('B', "CAN utilisateur 1", HeaderFormat.CAN_11),
    USER2_CAN('C', "CAN utilisateur 2", HeaderFormat.CAN_11);

    val isCan: Boolean get() = headerFormat == HeaderFormat.CAN_11 || headerFormat == HeaderFormat.CAN_29

    companion object {
        /** Interprète la réponse à `AT DPN`, ex. `A6` (auto, protocole 6) ou `6`. */
        fun fromDpn(response: String): ObdProtocol? {
            val code = response.trim().uppercase().lastOrNull() ?: return null
            return entries.firstOrNull { it.code == code }
        }
    }
}

/** Choix de protocole proposé à l'utilisateur avant la connexion (argument de `AT SP`). */
enum class ProtocolChoice(val atSpArgument: String, val label: String) {
    TOYOTA_AUTO("A6", "Auto, CAN 11 bits 500k en priorité (Toyota/Lexus ≈ 2008+)"),
    AUTO("0", "Recherche automatique complète"),
    CAN_11_500("6", "ISO 15765-4 CAN 11 bits 500k uniquement"),
    KWP_FAST("5", "ISO 14230-4 KWP rapide (Toyota ≈ 2003-2008)"),
    ISO_9141("3", "ISO 9141-2 (Toyota ≈ 1996-2004)"),
}
