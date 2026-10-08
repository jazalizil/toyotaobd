package com.example.toyotaobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.session.SampleStats
import com.example.toyotaobd.session.stats
import com.github.mikephil.charting.charts.LineChart
import kotlin.math.abs

/** Durées proposées, en minutes ; [SINCE_START] : tout l'historique de la connexion. */
private val PERIODS = listOf(1, 5, 15, 60, SINCE_START)
private const val SINCE_START = 0

/**
 * Détail d'un PID, ouvert en touchant sa carte : valeur actuelle, courbe sur une durée choisie (de
 * 1 min à tout l'historique depuis le démarrage) avec lecture au doigt, et statistiques de la
 * période. [chartEndMs] : bord droit commun des courbes ; [defaultMinutes] : durée des cartes.
 */
@Composable
fun PidDetailScreen(
    pid: Pid,
    value: Double?,
    hasValue: Boolean,
    samples: List<Sample>,
    chartEndMs: () -> Long,
    defaultMinutes: Int,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var period by rememberSaveable(pid.code) { mutableIntStateOf(defaultMinutes) }
    val nowMs = chartEndMs()
    // « Depuis le démarrage » : l'historique commence à la connexion (au moins une minute affichée).
    val sinceStartMs = samples.firstOrNull()?.let { nowMs - it.timeMs } ?: 0L
    val windowMs = if (period == SINCE_START) sinceStartMs.coerceAtLeast(60_000L) else period * 60_000L
    val startMs = nowMs - windowMs
    // Calculs coûteux (filtre, médiane) seulement quand ce PID change ou que la fenêtre avance.
    val recent = remember(samples, startMs) { samples.filter { it.timeMs >= startMs } }
    val stats = remember(recent) { recent.stats(GAP_MS) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour au tableau de bord")
            }
            Text(pid.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Text(
            when {
                value != null -> "${pid.format(value)} ${pid.unit}"
                hasValue -> "n/d"
                else -> "…"
            },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        PeriodSelector(period, Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { period = it }
        Text(
            if (period == SINCE_START) {
                "Depuis le démarrage · ${duration(sinceStartMs)} de données (depuis la connexion de l'adaptateur)"
            } else {
                "Sur ${periodLabel(period)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        val span = if (period == SINCE_START) duration(windowMs) else shortPeriodLabel(period)
        HistoryChart(pid, recent, stats, startMs, windowMs, nowMs, span, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Text(
            "Statistiques",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
        )
        StatsCard(pid, stats, nowMs, Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun PeriodSelector(selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        PERIODS.forEachIndexed { index, minutes ->
            SegmentedButton(
                selected = minutes == selected,
                onClick = { onSelect(minutes) },
                shape = SegmentedButtonDefaults.itemShape(index, PERIODS.size),
                icon = {},
            ) { Text(if (minutes == SINCE_START) "Tout" else shortPeriodLabel(minutes), maxLines = 1) }
        }
    }
}

/** Courbe de la période ([span] : sa durée lisible), graduée, avec lecture au doigt (valeur et ancienneté). */
@Composable
private fun HistoryChart(
    pid: Pid,
    recent: List<Sample>,
    stats: SampleStats?,
    startMs: Long,
    windowMs: Long,
    nowMs: Long,
    span: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val chartColors = WindowColors(
        line = colors.primary.toArgb(),
        ring = CardDefaults.cardColors().containerColor.toArgb(),
        labels = colors.onSurfaceVariant.toArgb(),
        grid = colors.outlineVariant.toArgb(),
        highlight = colors.outline.toArgb(),
    )
    val labelSizeDp = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.fontSize.toDp().value }
    val scale = remember(stats, pid) { stats?.let { ChartScale.of(it.min.value, it.max.value, pid.range) } }
    var touchX by remember { mutableStateOf<Float?>(null) }
    val selected = touchX?.takeIf { recent.isNotEmpty() }?.let { x ->
        val time = startMs + (x * 1_000).toLong()
        recent.minBy { abs(it.timeMs - time) }
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Text(
                selected?.let { "${pid.format(it.value)} ${pid.unit} · ${ago(nowMs - it.timeMs)}" } ?: "Touchez la courbe pour lire une valeur",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected != null) colors.onSurface else colors.onSurfaceVariant,
            )
            AndroidView(
                factory = { LineChart(it).apply { setupWindow(labelSizeDp) { x -> touchX = x } } },
                update = { it.showWindow(recent, scale, startMs, windowMs, selected, chartColors) },
                modifier = Modifier.fillMaxWidth().height(240.dp).padding(vertical = 6.dp),
            )
            Row {
                AxisText("−$span")
                Spacer(Modifier.weight(1f))
                AxisText("maintenant")
            }
        }
    }
}

/** Statistiques de la période ; [nowMs] date les extrêmes (« il y a… »). */
@Composable
private fun StatsCard(pid: Pid, stats: SampleStats?, nowMs: Long, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        if (stats == null) {
            Text(
                "Aucune lecture sur cette période.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            return@Card
        }
        fun format(value: Double) = "${pid.format(value)} ${pid.unit}".trim()
        val rows = listOf(
            "Minimum" to "${format(stats.min.value)} · ${ago(nowMs - stats.min.timeMs)}",
            "Maximum" to "${format(stats.max.value)} · ${ago(nowMs - stats.max.timeMs)}",
            "Moyenne (pondérée par le temps)" to format(stats.mean),
            "Médiane" to format(stats.median),
            "Écart type" to format(stats.standardDeviation),
            "Amplitude (max − min)" to format(stats.amplitude),
            "Variation sur la période" to (if (stats.variation > 0) "+" else "") + format(stats.variation),
            "Lectures" to "%,d".format(stats.count),
            "Intervalle moyen entre lectures" to (stats.meanIntervalMs?.let(::duration) ?: "—"),
            "Durée couverte" to duration(stats.durationMs),
        )
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            rows.forEachIndexed { index, (label, text) ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                }
            }
        }
    }
}

private fun shortPeriodLabel(minutes: Int) = if (minutes >= 60) "${minutes / 60} h" else "$minutes min"

private fun periodLabel(minutes: Int) = when {
    minutes >= 60 -> "la dernière heure"
    minutes == 1 -> "la dernière minute"
    else -> "les $minutes dernières minutes"
}

/** Durée lisible : « 2,4 s », « 45 s », « 12 min 05 s », « 1 h 07 min ». */
internal fun duration(ms: Long): String {
    val seconds = ms / 1_000
    return when {
        ms < 10_000 -> "%.1f s".format(ms / 1_000.0)
        seconds < 60 -> "$seconds s"
        seconds < 3_600 -> "%d min %02d s".format(seconds / 60, seconds % 60)
        else -> "%d h %02d min".format(seconds / 3_600, seconds % 3_600 / 60)
    }
}
