package com.example.toyotaobd.car

import com.example.toyotaobd.data.DashboardLayout
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.session.LiveState
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.session.stats
import com.example.toyotaobd.ui.GAP_MS
import com.example.toyotaobd.ui.ago

/** Ligne du tableau de bord sur l'écran de la voiture : nom de la valeur et lecture. */
data class CarRow(val code: Int, val title: String, val text: String)

/**
 * Lignes de [section] sur l'écran de la voiture : les cartes affichées du tableau de bord ([layout],
 * ordre et cartes masquées choisis sur le téléphone) que le véhicule fournit, au plus [max] (la
 * voiture limite la longueur des listes, surtout en roulant).
 *
 * Les titres ne changent pas pendant une connexion : une nouvelle lecture ne modifie que le texte,
 * ce que l'hôte Android Auto traite comme un rafraîchissement, sans entamer son quota d'écrans.
 */
fun carRows(layout: DashboardLayout, live: LiveState, section: DashboardSection, max: Int): List<CarRow> {
    val pids = live.pids.associateBy { it.code }
    return layout.bySection(pids.keys)[section].orEmpty().take(max.coerceAtLeast(0)).map { card ->
        val pid = pids.getValue(card.pid)
        val value = live.values[pid.code]
        val text = when {
            value != null -> "${pid.format(value)} ${pid.unit}"
            live.values.containsKey(pid.code) -> "n/d"
            else -> "…"
        }
        CarRow(pid.code, pid.name, text)
    }
}

/** Ligne du détail d'une valeur en voiture (carrousel) : titre fixe, une ou deux lignes de texte. */
data class CarDetailRow(val title: String, val texts: List<String>)

/**
 * Titres des lignes du carrousel, les mêmes pour toutes les valeurs : passer d'une valeur à l'autre
 * reste ainsi un simple rafraîchissement pour l'hôte. Un titre qui changerait (le nom du PID, par
 * exemple) compterait comme un nouvel écran à chaque flèche, et l'hôte fermerait l'application au
 * bout de 5. Le nom va dans le texte de la première ligne.
 */
val CAR_DETAIL_TITLES = listOf("Valeur", "Minimum", "Maximum", "Moyenne")

/**
 * Détail de [pid] en voiture : valeur actuelle, nom et position dans le carrousel ([position] sur
 * [count]), puis minimum, maximum (datés) et moyenne des [windowMinutes] dernières minutes de
 * [samples] ; « … » tant qu'il n'y a pas de lecture.
 */
fun carDetailRows(pid: Pid, live: LiveState, samples: List<Sample>, windowMinutes: Int, position: Int, count: Int): List<CarDetailRow> {
    val last = samples.lastOrNull()?.timeMs
    val recent = if (last == null) emptyList() else samples.filter { it.timeMs >= last - windowMinutes * 60_000L }
    val stats = recent.stats(GAP_MS)
    fun format(value: Double) = "${pid.format(value)} ${pid.unit}".trim()
    fun dated(sample: Sample?) = if (sample == null || last == null) "…" else "${format(sample.value)} · ${ago(last - sample.timeMs)}"
    val value = live.values[pid.code]
    val current = when {
        value != null -> format(value)
        live.values.containsKey(pid.code) -> "n/d"
        else -> "…"
    }
    val (valueTitle, minTitle, maxTitle, meanTitle) = CAR_DETAIL_TITLES
    return listOf(
        CarDetailRow(valueTitle, listOf(current, "${pid.name} · ${position + 1}/$count")),
        CarDetailRow(minTitle, listOf(dated(stats?.min))),
        CarDetailRow(maxTitle, listOf(dated(stats?.max))),
        CarDetailRow(meanTitle, listOf(stats?.let { format(it.mean) } ?: "…", "sur $windowMinutes min")),
    )
}
