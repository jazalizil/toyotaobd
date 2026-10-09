package com.example.toyotaobd.car

import com.example.toyotaobd.data.CardStyle
import com.example.toyotaobd.data.DashboardLayout
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.session.LiveState

/** Valeur du carrousel de la voiture : nom, lecture (avec son unité) et représentation [style]. */
data class CarRow(val code: Int, val title: String, val text: String, val style: CardStyle)

/**
 * Valeurs du carrousel de la voiture : les cartes affichées du tableau de bord ([layout], ordre et
 * cartes masquées choisis sur le téléphone) que le véhicule fournit, celles de [section] ou, sans
 * section, toutes (principales, hybrides puis thermiques). Chacune a la représentation du tableau de
 * bord par défaut ([DashboardLayout.DEFAULT]), à défaut celle choisie sur le téléphone.
 */
fun carRows(layout: DashboardLayout, live: LiveState, section: DashboardSection? = null): List<CarRow> {
    val pids = live.pids.associateBy { it.code }
    val sections = layout.bySection(pids.keys)
    val cards = if (section == null) sections.values.flatten() else sections[section].orEmpty()
    return cards.map { card ->
        val pid = pids.getValue(card.pid)
        val value = live.values[pid.code]
        val text = when {
            value != null -> "${pid.format(value)} ${pid.unit}"
            live.values.containsKey(pid.code) -> "n/d"
            else -> "…"
        }
        CarRow(pid.code, pid.name, text, DashboardLayout.DEFAULT.card(pid.code)?.style ?: card.style)
    }
}
