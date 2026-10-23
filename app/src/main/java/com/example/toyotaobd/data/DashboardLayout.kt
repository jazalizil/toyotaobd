package com.example.toyotaobd.data

import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.ToyotaHybrid

/** Représentation d'une valeur : [code] sert à l'enregistrement, ne pas le changer. */
enum class CardStyle(val code: Char, val label: String) {
    VALUE('V', "Valeur"), LINE('L', "Courbe"), GAUGE('G', "Jauge"),
}

/**
 * Sections du tableau de bord, une par onglet (application et widget), dans l'ordre d'affichage.
 * La section d'une valeur découle de sa nature, elle n'est pas enregistrée : les cartes se
 * réordonnent à l'intérieur de leur section. [tab] : libellé court de l'onglet.
 */
enum class DashboardSection(val title: String, val tab: String) {
    MAIN("Infos principales", "Principal"), HYBRID("Infos hybrides", "Hybride"), ENGINE(
        "Infos thermiques", "Thermique"
    );

    companion object {
        private val BY_PID: Map<Int, DashboardSection> = buildMap {
            Pids.ENGINE.forEach { put(it.code, ENGINE) }
            Pids.HYBRID.forEach { put(it.code, HYBRID) }
            (Pids.MAIN + ToyotaHybrid.CHASSIS).forEach { put(it.code, MAIN) } // l'emporte sur les deux autres
        }

        /** Section de [pid] ; à défaut, le mode 01 va avec le moteur thermique, une valeur constructeur avec les principales. */
        fun of(pid: Int): DashboardSection = BY_PID[pid] ?: if (pid < 0x100) ENGINE else MAIN

        /** Onglets à proposer : sections dont le véhicule fournit au moins une valeur ([pids]), dans l'ordre. */
        fun present(pids: Collection<Int>): List<DashboardSection> {
            val found = pids.mapTo(mutableSetOf()) { of(it) }
            return entries.filter { it in found }
        }
    }
}

/** Carte du tableau de bord : un PID, sa représentation, sur une colonne ou en largeur. */
data class DashboardCard(val pid: Int, val style: CardStyle, val wide: Boolean = false)

/**
 * Tableau de bord choisi par l'utilisateur : les cartes dans l'ordre d'affichage et la durée
 * affichée par les courbes. Une carte dont le PID n'est pas supporté par le véhicule garde sa
 * place mais n'est pas affichée.
 */
data class DashboardLayout(val cards: List<DashboardCard>, val windowMinutes: Int = DEFAULT_WINDOW_MINUTES) {

    fun card(pid: Int): DashboardCard? = cards.firstOrNull { it.pid == pid }

    /**
     * Cartes affichées ([shown] : PID supportés par le véhicule) regroupées par section, dans l'ordre
     * des sections ; chaque section garde l'ordre choisi par l'utilisateur et les sections vides sont omises.
     */
    fun bySection(shown: Set<Int>): Map<DashboardSection, List<DashboardCard>> {
        val groups = cards.filter { it.pid in shown }.groupBy { DashboardSection.of(it.pid) }
        return DashboardSection.entries.mapNotNull { section -> groups[section]?.let { section to it } }.toMap()
    }

    fun update(pid: Int, transform: (DashboardCard) -> DashboardCard) =
        copy(cards = cards.map { if (it.pid == pid) transform(it) else it })

    fun remove(pid: Int) = copy(cards = cards.filterNot { it.pid == pid })

    fun add(pid: Int) = if (card(pid) != null) this else copy(cards = cards + DashboardCard(pid, CardStyle.VALUE))

    /**
     * Échange la carte avec sa voisine ([offset] = -1 : précédente, +1 : suivante) parmi les cartes
     * affichées ([shown]) ; les cartes masquées pour ce véhicule ne bougent pas.
     */
    fun move(pid: Int, offset: Int, shown: Set<Int>): DashboardLayout {
        val visible = cards.indices.filter { cards[it].pid in shown }
        val from = visible.indexOfFirst { cards[it].pid == pid }
        val to = from + offset
        if (from < 0 || to !in visible.indices) return this
        val reordered = cards.toMutableList()
        reordered[visible[from]] = cards[visible[to]]
        reordered[visible[to]] = cards[visible[from]]
        return copy(cards = reordered)
    }

    /** Ex. `4|5|0C:LW,0D:LW,101:G` : version, durée des courbes, puis PID (hexa), style et « W » si large. */
    fun encode(): String = "$FORMAT|$windowMinutes|" + cards.joinToString(",") { card ->
        "%02X:%c%s".format(card.pid, card.style.code, if (card.wide) "W" else "")
    }

    companion object {
        const val DEFAULT_WINDOW_MINUTES = 5

        /** Durées proposées pour les courbes des cartes (la session conserve davantage : page de détail). */
        val WINDOW_MINUTES = listOf(1, 5, 15)

        private const val FORMAT = 4

        /**
         * Valeurs apparues avec chaque format : ajoutées à la fin d'un tableau de bord enregistré avec
         * un format plus ancien. Celles que l'utilisateur masquera ensuite le resteront.
         */
        private val ADDED_IN_FORMAT: Map<Int, List<Pid>>
            get() {
                val format3 = listOf(ToyotaHybrid.HSD3_BATTERY_FAN, ToyotaHybrid.STEERING_ANGLE)
                // Format 2 : toutes les données hybrides, y compris celles rangées depuis dans les infos principales.
                val format2 = Pids.DASHBOARD.filter(Pids::isHybridValue) - format3.toSet()
                return mapOf(2 to format2, 3 to format3, 4 to listOf(Pids.FUEL_ECONOMY))
            }

        /**
         * Tout le tableau de bord : régime, vitesse et flux de la batterie hybride en courbes,
         * températures, charge moteur et charge de la batterie en jauges, angle du volant en volant.
         */
        val DEFAULT = DashboardLayout(
            Pids.DASHBOARD.map { pid ->
                when (pid) {
                    Pids.FUEL_RATE -> DashboardCard(pid.code, CardStyle.LINE, wide = true)

                    ToyotaHybrid.REGEN_TORQUE -> DashboardCard(pid.code, CardStyle.LINE)

                    Pids.RPM, Pids.FUEL_ECONOMY, Pids.PEDAL, Pids.COOLANT_TEMP, Pids.ENGINE_LOAD, Pids.FUEL_LEVEL, Pids.HYBRID_BATTERY, ToyotaHybrid.SOC, ToyotaHybrid.STEERING_ANGLE, ToyotaHybrid.MOTOR_RPM, ToyotaHybrid.MOTOR_TORQUE -> DashboardCard(
                        pid.code, CardStyle.GAUGE
                    )

                    else -> DashboardCard(pid.code, CardStyle.VALUE)
                }
            },
        )

        /** `null` si le texte est absent ou illisible (on repart alors du tableau de bord par défaut). */
        fun decode(text: String?): DashboardLayout? {
            val parts = text?.split("|") ?: return null
            if (parts.size != 3) return null
            val format = parts[0].toIntOrNull()?.takeIf { it in 1..FORMAT } ?: return null
            val window = parts[1].toIntOrNull()?.takeIf { it in WINDOW_MINUTES } ?: return null
            val cards = parts[2].split(",").filter { it.isNotEmpty() }.map { entry ->
                val pid = entry.substringBefore(":").toIntOrNull(16) ?: return null
                // Le 49 (pédale non remise à zéro) a été remplacé par le 5A (position relative).
                val migrated = if (pid == 0x49) 0x5A else pid
                val flags = entry.substringAfter(":", "")
                val style = CardStyle.entries.firstOrNull { it.code == flags.firstOrNull() } ?: return null
                DashboardCard(migrated, style, wide = flags.drop(1) == "W")
            }
            val layout = DashboardLayout(cards.distinctBy { it.pid }, window)
            val added =
                ADDED_IN_FORMAT.filterKeys { it > format }.values.flatten().filter { layout.card(it.code) == null }
                    .mapNotNull { DEFAULT.card(it.code) }
            return layout.copy(cards = layout.cards + added)
        }
    }
}
