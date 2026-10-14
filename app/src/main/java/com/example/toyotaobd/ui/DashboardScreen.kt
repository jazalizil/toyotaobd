package com.example.toyotaobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.obd.Pids
import com.example.toyotaobd.obd.VehicleInfo
import com.example.toyotaobd.session.Sample

@Composable
fun DashboardScreen(vm: ObdViewModel, info: VehicleInfo) {
    val live by vm.live.collectAsStateWithLifecycle()
    val historyState = vm.history.collectAsStateWithLifecycle()
    val history by historyState
    val layout by vm.dashboard.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }

    val pids = remember(live.pids) { live.pids.associateBy { it.code } }
    val sections = remember(layout, pids) { layout.bySection(pids.keys) }
    // Un onglet par section dont le véhicule fournit une valeur, même masquée (sinon il disparaîtrait
    // quand on masque sa dernière carte). Chaque onglet garde sa position de défilement.
    val tabs = remember(pids) { DashboardSection.present(pids.keys) }
    var selectedName by rememberSaveable { mutableStateOf(DashboardSection.MAIN.name) }
    val selected = tabs.firstOrNull { it.name == selectedName } ?: tabs.firstOrNull() ?: DashboardSection.MAIN
    val gridStates = DashboardSection.entries.associateWith { rememberLazyGridState() }
    val cards = sections[selected].orEmpty()
    // Bord droit commun des courbes. Il change au plus une fois par seconde et seules les courbes
    // le lisent : les autres cartes ne sont pas recomposées à chaque lecture d'un PID.
    val chartEnd = remember(historyState) { derivedStateOf { chartEndMs(historyState.value) } }
    val chartEndMs = remember(chartEnd) { { chartEnd.value } }

    // Détail d'un PID, ouvert en touchant sa carte ; fermé si le PID disparaît (nouvelle connexion).
    var detailCode by rememberSaveable { mutableStateOf<Int?>(null) }
    val detailPid = detailCode?.let { pids[it] }
    LaunchedEffect(detailCode, detailPid) { if (detailCode != null && detailPid == null) detailCode = null }
    if (detailPid != null) {
        PidDetailScreen(
            pid = detailPid,
            value = live.values[detailPid.code],
            hasValue = live.values.containsKey(detailPid.code),
            samples = history[detailPid.code].orEmpty(),
            chartEndMs = chartEndMs,
            defaultMinutes = layout.windowMinutes,
            onBack = { detailCode = null },
        )
        return
    }

    // L'interrogation tourne dans ObdSession tant que l'adaptateur est connecté (widget, notification).
    // Changement d'onglet au toucher seulement : un balayage gênerait la lecture au doigt des courbes.
    Column(Modifier.fillMaxSize()) {
        if (tabs.size > 1) {
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(selected)) {
                tabs.forEach { section ->
                    Tab(
                        selected = section == selected,
                        onClick = { selectedName = section.name },
                        text = { Text(section.tab, maxLines = 1) },
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            state = gridStates.getValue(selected),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        info.vin?.let { Text("VIN : $it", style = MaterialTheme.typography.bodyMedium) }
                        val hybrid = remember(live.pids) { live.pids.count(Pids::isHybridValue) }
                        Text(
                            "${info.adapterVersion} · ${live.pids.size} PID supportés" +
                                if (hybrid > 0) ", dont $hybrid données hybrides" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { editing = true }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Personnaliser")
                    }
                }
            }
            if (cards.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Aucune carte affichée dans cet onglet. Touchez « Personnaliser » pour en ajouter.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(cards, key = { it.pid }, span = { GridItemSpan(if (it.wide) minOf(2, maxLineSpan) else 1) }) { card ->
                val pid = pids.getValue(card.pid)
                DashboardCardView(
                    card = card,
                    pid = pid,
                    value = live.values[pid.code],
                    hasValue = live.values.containsKey(pid.code),
                    samples = history[pid.code].orEmpty(),
                    windowMinutes = layout.windowMinutes,
                    chartEndMs = chartEndMs,
                    onOpen = { detailCode = pid.code },
                )
            }
        }
    }

    if (editing) {
        // Interrogation suspendue comme pour la console : à chaque lecture, le tableau de bord derrière
        // la feuille (cartes MPAndroidChart), le widget et la notification se redessinent sur le fil
        // principal et rendent son défilement saccadé ; l'éditeur n'affiche pas de valeurs. En
        // arrière-plan, elle reprend pour le widget et la notification.
        LifecycleStartEffect(Unit) {
            vm.enterDashboardEditor()
            onStopOrDispose { vm.leaveDashboardEditor() }
        }
        DashboardEditor(
            layout = layout,
            pids = live.pids,
            onEdit = vm::editDashboard,
            onDismiss = { editing = false },
        )
    }
}

/** Lecture la plus récente, arrondie à la seconde supérieure (toutes les valeurs restent à gauche du bord). */
internal fun chartEndMs(history: Map<Int, List<Sample>>): Long {
    val latest = history.values.mapNotNull { it.lastOrNull()?.timeMs }.maxOrNull() ?: return 0L
    return (latest / 1_000 + 1) * 1_000
}

/** Titre d'une section de l'éditeur du tableau de bord, annoncé comme tel par TalkBack. */
@Composable
fun DashboardSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}
