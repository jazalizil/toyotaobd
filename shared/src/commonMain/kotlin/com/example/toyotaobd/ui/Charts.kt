package com.example.toyotaobd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.session.segments
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Graphiques Compose du tableau de bord (téléphone Android et iOS). Les abscisses sont en secondes
// depuis le début de la courbe : un Float ne garde pas les millisecondes d'une horloge qui compte
// depuis le démarrage. La surface Android Auto et le widget dessinent leurs propres rendus.

/** Au-delà de cet écart entre deux lectures (console ouverte, PID muet), la courbe est interrompue. */
const val GAP_MS = 15_000L

/** Marge au-delà des graduations extrêmes : une ligne posée sur une borne n'est pas coupée de moitié. */
private const val EDGE_MARGIN = 0.03f

/** Déplacement au-delà duquel un toucher n'est plus une simple touche (ouverture du détail), en dp. */
private const val TAP_SLOP_DP = 8f

const val GAUGE_START_DEGREES = 150f
const val GAUGE_SWEEP_DEGREES = 240f

/** Rayon du trou de la jauge, en % du rayon : l'anneau en occupe le reste. */
const val GAUGE_HOLE_PERCENT = 84f

/** Couleurs de la courbe : ligne et point, anneau (fond de la carte), graduations, grille, trait de lecture. */
data class WindowColors(
    val line: Color,
    val ring: Color,
    val labels: Color,
    val grid: Color,
    val highlight: Color,
)

/**
 * Bornes verticales d'une courbe graduée : [scale] élargi de la marge [EDGE_MARGIN], comme les axes
 * de MPAndroidChart (une valeur posée sur une graduation n'est pas coupée de moitié).
 */
private class PlotBounds(scale: ChartScale) {
    val min = scale.min.toFloat() - ((scale.max - scale.min).toFloat() * EDGE_MARGIN)
    val max = scale.max.toFloat() + ((scale.max - scale.min).toFloat() * EDGE_MARGIN)
    fun y(height: Float, value: Float): Float = height * (1 - (value - min) / (max - min))
}

// --- Tendance : carte « Valeur » ---------------------------------------------------------

/**
 * Courbe sans axe ni interaction, à [insetDp] dp des bords (place du dernier point). Tendance sur
 * toute la largeur, des premières aux dernières lectures ; la dernière en [dotColor], entourée
 * d'un anneau [ringColor] s'il est donné. Moins de deux lectures : rien.
 */
@Composable
fun Sparkline(
    samples: List<Sample>,
    range: ClosedFloatingPointRange<Double>,
    lineColor: Color,
    dotColor: Color,
    ringColor: Color?,
    modifier: Modifier = Modifier,
    insetDp: Float = 6f,
) {
    if (samples.size < 2) {
        Box(modifier)
        return
    }
    val scale = remember(samples, range) {
        ChartScale.of(samples.minOf { it.value }, samples.maxOf { it.value }, range)
    }
    Canvas(modifier.padding(insetDp.dp)) {
        val bounds = PlotBounds(scale)
        val startMs = samples.first().timeMs
        val spanMs = (samples.last().timeMs - startMs).coerceAtLeast(1)
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        for (segment in samples.segments(GAP_MS)) {
            val path = Path()
            segment.forEachIndexed { index, sample ->
                val x = size.width * (sample.timeMs - startMs) / spanMs
                val y = bounds.y(size.height, sample.value.toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = stroke)
        }
        // Dernière lecture : point d'accent, entouré d'un anneau de la couleur de la carte.
        val last = samples.last()
        val center = Offset(
            size.width * (last.timeMs - startMs) / spanMs,
            bounds.y(size.height, last.value.toFloat()),
        )
        if (ringColor != null) drawCircle(ringColor, radius = 6.dp.toPx(), center = center)
        drawCircle(dotColor, radius = 4.dp.toPx(), center = center)
    }
}

// --- Courbe : fenêtre fixe, graduations, lecture au doigt ---------------------------------

/**
 * Courbe d'une durée fixe, graduée à gauche. Lecture au doigt : tant qu'il reste posé, [onSelect]
 * reçoit l'abscisse (en secondes depuis [startMs]) de la lecture la plus proche, puis null quand
 * il se lève. Une simple touche appelle [onTap] (ouverture du détail depuis une carte).
 * [labelSizeDp] : taille des graduations. [noDataText] : affiché sans valeurs.
 */
@Composable
fun TrendWindow(
    recent: List<Sample>,
    scale: ChartScale?,
    startMs: Long,
    windowMs: Long,
    selected: Sample?,
    colors: WindowColors,
    labelSizeDp: Float,
    noDataText: String?,
    onTap: () -> Unit = {},
    onSelect: (Float?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (scale == null || recent.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (!noDataText.isNullOrEmpty()) {
                Text(noDataText, style = MaterialTheme.typography.labelSmall, color = colors.labels, textAlign = TextAlign.Center)
            }
        }
        return
    }
    val bounds = remember(scale) { PlotBounds(scale) }
    val ticks = scale.ticks
    val windowSeconds = (windowMs / 1_000f).coerceAtLeast(1f)

    Layout(
        content = {
            Canvas(
                Modifier
                    .layoutId("chart")
                    .pointerInput(startMs, windowMs) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun read(x: Float): Float = (x / size.width * windowSeconds).coerceIn(0f, windowSeconds)
                            onSelect(read(down.position.x))
                            var travelled = 0f
                            var previous = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (event.changes.any { it.isConsumed }) break
                                if (!change.pressed) {
                                    // Une touche sans déplacement ouvre le détail ; le doigt levé retire la lecture.
                                    if (travelled < TAP_SLOP_DP.dp.toPx()) onTap()
                                    onSelect(null)
                                    break
                                }
                                travelled += abs(change.position.x - previous.x) + abs(change.position.y - previous.y)
                                previous = change.position
                                onSelect(read(change.position.x))
                            }
                        }
                    },
            ) {
                drawWindow(recent, bounds, ticks, startMs, windowSeconds, selected, colors)
            }
            // Graduations, de la plus haute (max) à la plus basse (min).
            ticks.reversed().forEach { value ->
                Text(
                    scale.label(value),
                    fontSize = labelSizeDp.sp,
                    lineHeight = (labelSizeDp * 1.2f).sp,
                    color = colors.labels,
                    maxLines = 1,
                    modifier = Modifier.layoutId("label"),
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val labels = measurables.filter { it.layoutId == "label" }.map { it.measure(loose) }
        val labelWidth = labels.maxOfOrNull { it.width } ?: 0
        val labelGap = 6.dp.roundToPx()
        val chartWidth = (constraints.maxWidth - labelWidth - labelGap).coerceAtLeast(0)
        val chart = measurables.first { it.layoutId == "chart" }
            .measure(Constraints.fixed(chartWidth, constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            chart.place(labelWidth + labelGap, 0)
            // Chaque graduation à la hauteur exacte de sa valeur sur la grille.
            labels.forEachIndexed { index, label ->
                val value = ticks.reversed()[index]
                val y = bounds.y(constraints.maxHeight.toFloat(), value.toFloat())
                label.place(0, (y - label.height / 2f).roundToInt().coerceIn(0, constraints.maxHeight - label.height))
            }
        }
    }
}

/** Grille, tronçons de courbe, point marqué et trait vertical de la lecture au doigt. */
private fun DrawScope.drawWindow(
    recent: List<Sample>,
    bounds: PlotBounds,
    ticks: List<Double>,
    startMs: Long,
    windowSeconds: Float,
    selected: Sample?,
    colors: WindowColors,
) {
    fun x(timeMs: Long): Float = size.width * ((timeMs - startMs) / 1_000f) / windowSeconds

    // Grille : une ligne par graduation (réparties régulièrement par [ChartScale]).
    val gridWidth = 1.dp.toPx()
    for (tick in ticks) {
        val y = bounds.y(size.height, tick.toFloat())
        drawLine(colors.grid, Offset(0f, y), Offset(size.width, y), strokeWidth = gridWidth)
    }

    val segments = recent.segments(GAP_MS)
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    for (segment in segments) {
        val path = Path()
        segment.forEachIndexed { index, sample ->
            val px = x(sample.timeMs)
            val py = bounds.y(size.height, sample.value.toFloat())
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, colors.line, style = stroke)
    }

    // Trait vertical de la lecture au doigt, sur toute la hauteur.
    if (selected != null) {
        val sx = x(selected.timeMs)
        drawLine(colors.highlight, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 1.dp.toPx())
    }

    // Point marqué : la lecture au doigt, sinon la dernière valeur.
    val marked = selected ?: recent.last()
    val center = Offset(x(marked.timeMs), bounds.y(size.height, marked.value.toFloat()))
    drawCircle(colors.ring, radius = 6.dp.toPx(), center = center)
    drawCircle(colors.line, radius = 4.dp.toPx(), center = center)
}

// --- Jauge : position dans la plage usuelle du PID -----------------------------------------

/**
 * Partie remplie de la jauge pour [value] dans [range], en fractions de l'anneau (0 : début, 1 : fin) ;
 * plage de part et d'autre de zéro (corrections de mélange) : remplissage depuis zéro.
 */
fun gaugeFill(range: ClosedFloatingPointRange<Double>, value: Double): ClosedFloatingPointRange<Float> {
    fun fraction(v: Double) = ((v - range.start) / (range.endInclusive - range.start)).coerceIn(0.0, 1.0).toFloat()
    val origin = if (range.start < 0 && range.endInclusive > 0) fraction(0.0) else 0f
    val current = fraction(value)
    return min(origin, current)..max(origin, current)
}

/** Anneau de 240° ouvert vers le bas, comme un compteur ; [value] dans [range], rempli selon [gaugeFill]. */
@Composable
fun Gauge(
    range: ClosedFloatingPointRange<Double>,
    value: Double?,
    trackColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val radius = min(size.width, size.height) / 2
        val stroke = radius * (1 - GAUGE_HOLE_PERCENT / 100)
        val ringRadius = radius - stroke / 2
        val topLeft = Offset(size.width / 2 - ringRadius, size.height / 2 - ringRadius)
        val arcSize = Size(ringRadius * 2, ringRadius * 2)
        fun arc(from: Float, to: Float, color: Color) {
            if (to <= from) return
            drawArc(
                color = color,
                startAngle = GAUGE_START_DEGREES + from * GAUGE_SWEEP_DEGREES,
                sweepAngle = (to - from) * GAUGE_SWEEP_DEGREES,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
        }
        val fill = value?.let { gaugeFill(range, it) } ?: 0f..0f
        arc(0f, fill.start, trackColor)
        arc(fill.start, fill.endInclusive, fillColor)
        arc(fill.endInclusive, 1f, trackColor)
    }
}
