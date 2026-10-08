package com.example.toyotaobd.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.toyotaobd.data.CardStyle
import com.example.toyotaobd.data.DashboardCard
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.ToyotaHybrid
import com.example.toyotaobd.session.Sample
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Hauteur commune : les lignes de la grille restent alignées quels que soient les styles. */
private val CARD_HEIGHT = 168.dp

/**
 * Carte du tableau de bord. [samples] est l'historique du PID et [windowMinutes] la durée affichée.
 *
 * La carte ne dépend que de ses propres valeurs : elle n'est recomposée que lorsque son PID change.
 * Seules les courbes lisent [chartEndMs], le bord droit commun (qui avance avec chaque lecture).
 */
@Composable
fun DashboardCardView(
    card: DashboardCard,
    pid: Pid,
    value: Double?,
    hasValue: Boolean,
    samples: List<Sample>,
    windowMinutes: Int,
    chartEndMs: () -> Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowMs = windowMinutes * 60_000L
    // Fenêtre ancrée sur la dernière lecture de ce PID.
    val recent = remember(samples, windowMs) {
        val last = samples.lastOrNull()?.timeMs ?: return@remember emptyList()
        samples.filter { it.timeMs >= last - windowMs }
    }
    val text = when {
        value != null -> pid.format(value)
        hasValue -> "n/d"
        else -> "…"
    }
    // Formatés une fois par nouvelle valeur : phrase TalkBack et pied des courbes.
    val extremes = remember(recent) {
        if (recent.isEmpty()) null else pid.format(recent.minOf { it.value }) to pid.format(recent.maxOf { it.value })
    }
    // Le dessin est invisible pour TalkBack : la carte se lit comme une phrase.
    val description = buildString {
        append("${pid.name} : $text")
        if (value != null) append(" ${pid.unit}")
        extremes?.let { (min, max) -> append(". Sur $windowMinutes min : de $min à $max") }
    }
    // Toucher la carte ouvre le détail du PID (TalkBack : action « Afficher le détail »).
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth().height(CARD_HEIGHT).clearAndSetSemantics {
            contentDescription = description
            onClick(label = "Afficher le détail") { onOpen(); true }
        },
    ) {
        when (card.style) {
            CardStyle.VALUE -> ValueContent(pid, text, value != null, recent)
            CardStyle.LINE -> LineContent(pid, text, value != null, samples, extremes, chartEndMs, windowMinutes, onOpen)
            CardStyle.GAUGE -> if (pid.code == ToyotaHybrid.STEERING_ANGLE.code) {
                SteeringWheelContent(pid, value, text)
            } else {
                GaugeContent(pid, value, text)
            }
        }
    }
}

@Composable
private fun CardLabel(pid: Pid, modifier: Modifier = Modifier) {
    Text(
        pid.name,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun ValueWithUnit(text: String, unit: String?, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(text, style = style, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.alignByBaseline())
        if (unit != null) {
            Text(
                " $unit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

// --- Valeur : chiffre et tendance (MPAndroidChart) -------------------------------------

@Composable
private fun ValueContent(pid: Pid, text: String, hasNumber: Boolean, recent: List<Sample>) {
    val trend = MaterialTheme.colorScheme.outline.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val ring = CardDefaults.cardColors().containerColor.toArgb()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        CardLabel(pid)
        ValueWithUnit(text, pid.unit.takeIf { hasNumber }, MaterialTheme.typography.headlineMedium)
        // Tendance discrète sur toute la largeur (des premières aux dernières lectures de la fenêtre),
        // dernière valeur en couleur d'accent.
        AndroidView(
            factory = { LineChart(it).apply { setupSparkline(insetDp = 6f) } },
            update = { it.showSparkline(recent, pid.range, lineColor = trend, dotColor = accent, ringColor = ring) },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
        )
    }
}

// --- Courbe : graduations, minimum et maximum, lecture au doigt (MPAndroidChart) ---------

/** [chartEndMs] : bord droit commun des courbes ; [extremes] : minimum et maximum déjà formatés. */
@Composable
private fun LineContent(
    pid: Pid,
    text: String,
    hasNumber: Boolean,
    samples: List<Sample>,
    extremes: Pair<String, String>?,
    chartEndMs: () -> Long,
    windowMinutes: Int,
    onOpen: () -> Unit,
) {
    // La courbe intercepte les touches (lecture au doigt) : une simple touche ouvre aussi le détail.
    val open by rememberUpdatedState(onOpen)
    val nowMs = chartEndMs()
    val windowMs = windowMinutes * 60_000L
    val startMs = nowMs - windowMs
    val recent = remember(samples, startMs) { samples.filter { it.timeMs >= startMs } }
    val colors = MaterialTheme.colorScheme
    val chartColors = WindowColors(
        line = colors.primary.toArgb(),
        ring = CardDefaults.cardColors().containerColor.toArgb(),
        labels = colors.onSurfaceVariant.toArgb(),
        grid = colors.outlineVariant.toArgb(),
        highlight = colors.outline.toArgb(),
    )
    val labelSizeDp = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.fontSize.toDp().value }

    val scale = remember(recent, pid) {
        if (recent.isEmpty()) null else ChartScale.of(recent.minOf { it.value }, recent.maxOf { it.value }, pid.range)
    }
    // Abscisse lue au doigt (secondes depuis le début de la fenêtre), tant qu'il reste posé.
    var touchX by remember { mutableStateOf<Float?>(null) }
    val selected = touchX?.takeIf { recent.isNotEmpty() }?.let { x ->
        val time = startMs + (x * 1_000).toLong()
        recent.minBy { abs(it.timeMs - time) }
    }

    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
        Row {
            CardLabel(pid, Modifier.weight(1f).alignByBaseline())
            ValueWithUnit(
                text = selected?.let { pid.format(it.value) } ?: text,
                unit = pid.unit.takeIf { hasNumber || selected != null },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.alignByBaseline(),
            )
        }
        AndroidView(
            factory = { LineChart(it).apply { setupWindow(labelSizeDp, onTap = { open() }) { x -> touchX = x } } },
            update = { it.showWindow(recent, scale, startMs, windowMs, selected, chartColors) },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
        )
        Row {
            AxisText("−$windowMinutes min")
            Spacer(Modifier.weight(1f))
            when {
                selected != null -> AxisText(ago(nowMs - selected.timeMs))
                extremes != null -> AxisText("min ${extremes.first} · max ${extremes.second} ${pid.unit}")
            }
        }
    }
}

@Composable
internal fun AxisText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

internal fun ago(ms: Long): String {
    val seconds = ms / 1_000
    return if (seconds < 60) "il y a $seconds s" else "il y a ${seconds / 60} min ${seconds % 60} s"
}

// --- Jauge : position dans la plage usuelle du PID (MPAndroidChart) ---------------------

@Composable
private fun GaugeContent(pid: Pid, value: Double?, text: String) {
    val colors = MaterialTheme.colorScheme
    val track = colors.primaryContainer.toArgb()
    val fill = colors.primary.toArgb()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        CardLabel(pid)
        Layout(
            content = {
                AndroidView(
                    factory = { PieChart(it).apply { setupGauge() } },
                    update = { it.showGauge(pid.range, value, trackColor = track, fillColor = fill) },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (value != null) {
                        Text(pid.unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
                for (bound in listOf(pid.range.start, pid.range.endInclusive)) {
                    Text(boundLabel(bound), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val center = measurables[1].measure(loose)
            val bounds = measurables.subList(2, 4).map { it.measure(loose) }
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val gap = 2.dp.toPx()
            val labelHeight = bounds.maxOf { it.height } + gap
            // L'arc de 240° occupe 1,5 rayon en hauteur ; les bornes s'écrivent sous ses extrémités.
            // Le graphique est un carré autour du cercle : son quart inférieur, vide, peut déborder.
            val radius = min(width / 2f, (height - labelHeight) / 1.5f).coerceAtLeast(0f)
            val chart = measurables[0].measure(Constraints.fixed(width, (radius * 2).roundToInt()))
            val top = (height - (1.5f * radius + labelHeight)) / 2
            val centerX = width / 2f
            val centerY = top + radius
            layout(width, height) {
                chart.place(0, top.roundToInt())
                center.place((centerX - center.width / 2f).roundToInt(), (centerY - center.height / 2f).roundToInt())
                // Chaque borne sous le milieu de l'anneau, à son extrémité.
                val ringMiddle = radius * (1 + GAUGE_HOLE_PERCENT / 100) / 2
                listOf(GAUGE_START_DEGREES, GAUGE_START_DEGREES + GAUGE_SWEEP_DEGREES).zip(bounds) { degrees, label ->
                    val angle = Math.toRadians(degrees.toDouble())
                    val x = centerX + ringMiddle * cos(angle).toFloat()
                    val y = centerY + radius * sin(angle).toFloat() + gap
                    label.place((x - label.width / 2f).roundToInt(), y.roundToInt())
                }
            }
        }
    }
}

private fun boundLabel(value: Double): String = if (value % 1.0 == 0.0) "%.0f".format(value) else "%.1f".format(value)

// --- Volant : l'angle du volant fait tourner un volant dessiné ---------------------------

@Composable
private fun SteeringWheelContent(pid: Pid, value: Double?, text: String) {
    // Les lectures arrivent toutes les une à trois secondes : le volant les rejoint en douceur.
    // L'angle n'est pas ramené à un tour : de butée à butée, le volant fait plusieurs tours, comme le vrai.
    val angle by animateFloatAsState(
        targetValue = (value ?: 0.0).toFloat() * STEERING_CLOCKWISE,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "volant",
    )
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        CardLabel(pid)
        SteeringWheel(
            rotation = angle,
            wheelColor = colors.onSurfaceVariant.copy(alpha = if (value != null) 1f else 0.38f),
            markerColor = colors.primary,
            referenceColor = colors.outline,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp),
        )
        ValueWithUnit(
            text,
            pid.unit.takeIf { value != null },
            MaterialTheme.typography.titleLarge,
            Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** Volant tourné de [rotation] degrés (sens horaire), dessiné par [drawSteeringWheel] comme dans le widget. */
@Composable
private fun SteeringWheel(
    rotation: Float,
    wheelColor: Color,
    markerColor: Color,
    referenceColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        drawIntoCanvas {
            drawSteeringWheel(
                it.nativeCanvas, size.width, size.height, density, rotation,
                wheelColor.toArgb(), markerColor.toArgb(), referenceColor.toArgb(),
            )
        }
    }
}
