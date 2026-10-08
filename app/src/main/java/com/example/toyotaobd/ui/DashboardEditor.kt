package com.example.toyotaobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.toyotaobd.data.CardStyle
import com.example.toyotaobd.data.DashboardCard
import com.example.toyotaobd.data.DashboardLayout
import com.example.toyotaobd.data.DashboardSection
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.ToyotaHybrid

/**
 * Choix des cartes du tableau de bord, section par section : ordre, représentation, largeur, cartes
 * masquées. Seuls les PID supportés par le véhicule sont proposés ; chaque modification est enregistrée.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardEditor(
    layout: DashboardLayout,
    pids: List<Pid>,
    onEdit: ((DashboardLayout) -> DashboardLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val byCode = pids.associateBy { it.code }
    val sections = layout.bySection(byCode.keys)
    val hidden = pids.filter { layout.card(it.code) == null }.groupBy { DashboardSection.of(it.code) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tableau de bord", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onEdit { DashboardLayout.DEFAULT.copy(windowMinutes = it.windowMinutes) } }) {
                        Text("Réinitialiser")
                    }
                }
                WindowSelector(layout.windowMinutes, Modifier.padding(horizontal = 16.dp)) { minutes ->
                    onEdit { it.copy(windowMinutes = minutes) }
                }
            }
            // Chaque section : ses cartes affichées (qui ne se déplacent qu'entre elles), puis ses cartes masquées.
            for (section in DashboardSection.entries) {
                val shown = sections[section].orEmpty()
                val masked = hidden[section].orEmpty()
                if (shown.isEmpty() && masked.isEmpty()) continue
                item(key = "section-${section.name}") {
                    DashboardSectionTitle(section.title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                }
                val sectionPids = shown.map { it.pid }.toSet()
                itemsIndexed(shown, key = { _, card -> card.pid }) { index, card ->
                    CardEditorRow(
                        card = card,
                        pid = byCode.getValue(card.pid),
                        canMoveUp = index > 0,
                        canMoveDown = index < shown.lastIndex,
                        onEdit = onEdit,
                        shown = sectionPids,
                    )
                    HorizontalDivider()
                }
                if (masked.isNotEmpty()) {
                    item(key = "masquées-${section.name}") {
                        Text(
                            "Masquées",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(masked, key = { "masquée-${it.code}" }) { pid ->
                        ListItem(
                            headlineContent = { Text(pid.name) },
                            supportingContent = { Text(pid.unit) },
                            trailingContent = {
                                IconButton(onClick = { onEdit { it.add(pid.code) } }) {
                                    Icon(Icons.Default.Add, contentDescription = "Afficher ${pid.name}")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardEditorRow(
    card: DashboardCard,
    pid: Pid,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: ((DashboardLayout) -> DashboardLayout) -> Unit,
    shown: Set<Int>,
) {
    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pid.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onEdit { it.move(card.pid, -1, shown) } }, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Monter ${pid.name}")
            }
            IconButton(onClick = { onEdit { it.move(card.pid, 1, shown) } }, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descendre ${pid.name}")
            }
            IconButton(onClick = { onEdit { it.remove(card.pid) } }) {
                Icon(Icons.Default.Close, contentDescription = "Masquer ${pid.name}")
            }
        }
        Row(
            modifier = Modifier.padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                CardStyle.entries.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = card.style == style,
                        onClick = { onEdit { it.update(card.pid) { c -> c.copy(style = style) } } },
                        shape = SegmentedButtonDefaults.itemShape(index, CardStyle.entries.size),
                        icon = {},
                    ) {
                        // La jauge de l'angle du volant est un volant qui tourne.
                        val steering = style == CardStyle.GAUGE && card.pid == ToyotaHybrid.STEERING_ANGLE.code
                        Text(if (steering) "Volant" else style.label, maxLines = 1)
                    }
                }
            }
            FilterChip(
                selected = card.wide,
                onClick = { onEdit { it.update(card.pid) { c -> c.copy(wide = !c.wide) } } },
                // Sans coche : la puce garde sa largeur et les boutons restent alignés d'une carte à l'autre.
                label = { Text("Large") },
            )
        }
    }
}

/** Durée des courbes, commune à toutes les cartes. */
@Composable
private fun WindowSelector(selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Courbes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            val options = DashboardLayout.WINDOW_MINUTES
            options.forEachIndexed { index, minutes ->
                SegmentedButton(
                    selected = minutes == selected,
                    onClick = { onSelect(minutes) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    icon = {},
                ) { Text("$minutes min") }
            }
        }
    }
}
