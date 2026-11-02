package com.example.toyotaobd.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withScale
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.session.segments
import com.github.mikephil.charting.charts.Chart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Rendus MPAndroidChart de la surface Android Auto et du widget (le tableau de bord du téléphone
// dessine ses graphiques en Compose, dans le module partagé). Les abscisses sont en secondes depuis
// le début de la courbe : un Float ne garde pas les millisecondes d'une horloge qui compte depuis
// le démarrage. Bornes de jauge, remplissage et couleurs WindowColors viennent du module partagé.

/** Marge au-delà des graduations extrêmes : une ligne posée sur une borne n'est pas coupée de moitié. */
private const val EDGE_MARGIN = 0.03f

private fun Sample.toEntry(startMs: Long) = Entry((timeMs - startMs) / 1_000f, value.toFloat())

/** Ni description, ni légende, ni sélection au toucher ; ignoré par TalkBack (la carte se lit comme une phrase). */
private fun Chart<*>.setupCommon() {
    description.isEnabled = false
    legend.isEnabled = false
    isHighlightPerTapEnabled = false
    setNoDataText("")
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
}

private fun YAxis.applyScale(scale: ChartScale) {
    val margin = (scale.max - scale.min).toFloat() * EDGE_MARGIN
    axisMinimum = scale.min.toFloat() - margin
    axisMaximum = scale.max.toFloat() + margin
}

/**
 * Une série de 2 dp par tronçon (la ligne s'interrompt entre deux lectures trop espacées), puis une
 * série d'un point, [marked], en [dotColor] entouré d'un anneau [ringColor] s'il est donné.
 * [highlightColor] : trait vertical de la lecture au doigt, sur les tronçons seulement.
 */
private fun trendData(
    segments: List<List<Sample>>,
    startMs: Long,
    lineColor: Int,
    highlightColor: Int?,
    marked: Sample,
    dotColor: Int,
    ringColor: Int?,
): LineData {
    val lines = segments.map { segment ->
        LineDataSet(segment.map { it.toEntry(startMs) }, null).apply {
            color = lineColor
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            isHighlightEnabled = highlightColor != null
            if (highlightColor != null) {
                highLightColor = highlightColor
                highlightLineWidth = 1f
                setDrawHorizontalHighlightIndicator(false)
            }
        }
    }
    val dot = LineDataSet(listOf(marked.toEntry(startMs)), null).apply {
        setDrawValues(false)
        isHighlightEnabled = false
        if (ringColor != null) {
            setCircleColor(ringColor)
            circleRadius = 6f
            circleHoleRadius = 4f
            circleHoleColor = dotColor
        } else {
            setCircleColor(dotColor)
            circleRadius = 4f
            setDrawCircleHole(false)
        }
    }
    return LineData(lines + dot)
}

// --- Tendance : carte « Valeur » et tuiles du widget ------------------------------------

/** Courbe sans axe ni interaction, à [insetDp] des bords (place du dernier point). */
fun LineChart.setupSparkline(insetDp: Float) {
    setupCommon()
    setTouchEnabled(false)
    xAxis.isEnabled = false
    axisLeft.isEnabled = false
    axisRight.isEnabled = false
    minOffset = 0f
    setExtraOffsets(insetDp, insetDp, insetDp, insetDp)
}

/**
 * Tendance sur toute la largeur, des premières aux dernières lectures ; la dernière en [dotColor].
 * Moins de deux lectures : rien.
 */
fun LineChart.showSparkline(
    samples: List<Sample>,
    range: ClosedFloatingPointRange<Double>,
    lineColor: Int,
    dotColor: Int,
    ringColor: Int?,
) {
    if (samples.size < 2) {
        data = null
    } else {
        axisLeft.applyScale(ChartScale.of(samples.minOf { it.value }, samples.maxOf { it.value }, range))
        data = trendData(samples.segments(GAP_MS), samples.first().timeMs, lineColor, null, samples.last(), dotColor, ringColor)
    }
    invalidate()
}

/**
 * Tendances du widget, dessinées hors écran : un widget n'affiche pas de vue personnalisée. Tracé en
 * blanc (ligne translucide, dernier point plus marqué) pour être teinté aux couleurs du widget ;
 * [faint] pour une tendance dessinée derrière du texte.
 * [pixelsPerDp] inférieur à la densité de l'écran réduit la définition (mémoire limitée des widgets).
 * Sur le thread principal : le graphique crée un Handler.
 */
@MainThread
class SparklineRenderer(context: Context, widthDp: Float, heightDp: Float, pixelsPerDp: Float, faint: Boolean) {
    private val density = context.resources.displayMetrics.density
    private val widthPx = (widthDp * density).roundToInt()
    private val heightPx = (heightDp * density).roundToInt()
    private val factor = pixelsPerDp / density
    private val lineColor = Color.argb(if (faint) 80 else 150, 255, 255, 255)
    private val dotColor = Color.argb(if (faint) 140 else 255, 255, 255, 255)
    private val chart = LineChart(context).apply {
        setupSparkline(insetDp = 4f)
        // Taille fixée à la main : sans mise en page, le graphique ne connaît pas ses dimensions.
        measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        layout(0, 0, widthPx, heightPx)
    }

    fun render(samples: List<Sample>, range: ClosedFloatingPointRange<Double>): Bitmap? {
        if (samples.size < 2 || widthPx <= 0 || heightPx <= 0) return null
        chart.showSparkline(samples, range, lineColor, dotColor, ringColor = null)
        val bitmap = Bitmap.createBitmap(max(1, (widthPx * factor).roundToInt()), max(1, (heightPx * factor).roundToInt()), Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bitmap).apply { scale(factor, factor) })
        return bitmap
    }
}

// --- Courbe : fenêtre fixe, graduations, lecture au doigt --------------------------------

/**
 * Courbe d'une durée fixe, graduée à gauche. Lecture au doigt : tant qu'il reste posé, [onSelect]
 * reçoit l'abscisse de la lecture la plus proche, puis null quand il se lève ou que la grille défile.
 * Une simple touche appelle [onTap] (ouverture du détail depuis une carte).
 * [labelSizeDp] : taille des graduations, pour laisser une demi-hauteur de texte en haut et en bas.
 */
fun LineChart.setupWindow(labelSizeDp: Float, onTap: () -> Unit = {}, onSelect: (Float?) -> Unit) {
    setupCommon()
    setNoDataText("En attente de valeurs…")
    xAxis.isEnabled = false
    axisRight.isEnabled = false
    axisLeft.apply {
        textSize = labelSizeDp
        xOffset = 6f
        setDrawAxisLine(false)
        gridLineWidth = 1f
        isGranularityEnabled = true
    }
    rendererLeftYAxis.paintAxisLabels.fontFeatureSettings = "tnum"
    minOffset = 0f
    // La largeur réservée aux graduations compte xOffset des deux côtés du texte : on retire celui de gauche.
    setExtraOffsets(-6f, labelSizeDp / 2, 6f, labelSizeDp / 2)

    // Sans zoom ni décalage, glisser le doigt déplace la sélection au lieu de faire défiler la courbe.
    isDragEnabled = true
    setScaleEnabled(false)
    isDoubleTapToZoomEnabled = false
    isDragDecelerationEnabled = false
    isHighlightPerDragEnabled = true
    setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
        override fun onValueSelected(e: Entry, h: Highlight) = onSelect(e.x)
        override fun onNothingSelected() = onSelect(null)
    })
    onChartGestureListener = object : OnChartGestureListener {
        // Sélection dès que le doigt se pose, retirée quand il se lève (ou que la grille reprend le geste).
        override fun onChartGestureStart(me: MotionEvent, lastPerformedGesture: ChartTouchListener.ChartGesture?) =
            highlightValue(getHighlightByTouchPoint(me.x, me.y), true)

        override fun onChartGestureEnd(me: MotionEvent, lastPerformedGesture: ChartTouchListener.ChartGesture?) =
            highlightValue(null, true)

        override fun onChartLongPressed(me: MotionEvent) = Unit
        override fun onChartDoubleTapped(me: MotionEvent) = Unit
        override fun onChartSingleTapped(me: MotionEvent) = onTap()
        override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) = Unit
        override fun onChartScale(me: MotionEvent, scaleX: Float, scaleY: Float) = Unit
        override fun onChartTranslate(me: MotionEvent, dX: Float, dY: Float) = Unit
    }
}

/**
 * Lectures [recent] de [startMs] à [startMs] + [windowMs] (bord droit : la dernière lecture, tous PID
 * confondus). Le point est sur [selected] pendant une lecture au doigt, sinon sur la dernière valeur.
 */
fun LineChart.showWindow(
    recent: List<Sample>,
    scale: ChartScale?,
    startMs: Long,
    windowMs: Long,
    selected: Sample?,
    colors: WindowColors,
) {
    setNoDataTextColor(colors.labels.toArgb())
    if (scale == null || recent.isEmpty()) {
        data = null
        invalidate()
        return
    }
    xAxis.axisMinimum = 0f
    xAxis.axisMaximum = windowMs / 1_000f
    axisLeft.apply {
        applyScale(scale)
        granularity = scale.step.toFloat()
        setLabelCount(scale.ticks.size, false)
        valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float) = scale.label(value.toDouble())
        }
        textColor = colors.labels.toArgb()
        gridColor = colors.grid.toArgb()
    }
    val segments = recent.segments(GAP_MS)
    data = trendData(segments, startMs, colors.line.toArgb(), colors.highlight?.toArgb(), selected ?: recent.last(), colors.line.toArgb(), colors.ring.toArgb())
    // Les données changent sous le doigt : le trait reste sur la lecture choisie.
    val segment = selected?.let { s -> segments.indexOfFirst { s in it } } ?: -1
    if (selected != null && segment >= 0) highlightValue(selected.toEntry(startMs).x, segment, false) else highlightValue(null, false)
}

// WindowColors (couleurs Compose du module partagé) est converti en ARGB par showWindow.

/** Mise en page de [WindowRenderer] au plus, en px : nombre de pixels et côté. */
private const val MAX_LAYOUT_PIXELS = 4_000_000f
private const val MAX_LAYOUT_SIDE = 8_000f

/**
 * Courbe graduée dessinée hors écran, pour la surface d'Android Auto : mise en page en dp de
 * [context], puis mise à l'échelle de l'écran qui l'affiche. [context] : celui de l'application, car
 * MPAndroidChart garde la densité du dernier contexte reçu pour tous ses graphiques (celui de la
 * voiture fausserait les marges des courbes du téléphone).
 * Sur le thread principal : le graphique crée un Handler.
 */
@MainThread
class WindowRenderer(context: Context, labelSizeDp: Float, private val colors: WindowColors) {
    private val density = context.resources.displayMetrics.density
    private val chart = LineChart(context).apply {
        setupWindow(labelSizeDp) {}
        setTouchEnabled(false)
        getPaint(Chart.PAINT_INFO).textSize = labelSizeDp * density // « En attente de valeurs… »
    }

    /**
     * Lectures [recent] de [startMs] à [startMs] + [windowMs] (voir [showWindow]), dessinées dans
     * [canvas] sur [width] × [height] px depuis son origine. [canvasDensity] : densité de l'écran qui
     * l'affiche (graduations à la même taille en dp que sur le téléphone).
     */
    fun draw(canvas: Canvas, recent: List<Sample>, scale: ChartScale?, startMs: Long, windowMs: Long, width: Int, height: Int, canvasDensity: Float) {
        val factor = chart.fitTo(width, height, density, canvasDensity)
        chart.showWindow(recent, scale, startMs, windowMs, selected = null, colors)
        canvas.withScale(factor, factor) { chart.draw(this) }
    }
}

/**
 * Tendance d'une carte « Valeur » dessinée hors écran, pour la surface d'Android Auto, comme
 * [WindowRenderer] (même remarque sur [context]) : ligne [lineColor], dernière lecture en [dotColor]
 * entourée d'un anneau [ringColor] (fond de la carte).
 * Sur le thread principal : le graphique crée un Handler.
 */
@MainThread
class SparklineCanvasRenderer(context: Context, insetDp: Float, private val lineColor: Int, private val dotColor: Int, private val ringColor: Int) {
    private val density = context.resources.displayMetrics.density
    private val chart = LineChart(context).apply { setupSparkline(insetDp) }

    /** Lectures [samples] (voir [showSparkline]) dessinées dans [canvas] sur [width] × [height] px ; moins de deux lectures : rien. */
    fun draw(canvas: Canvas, samples: List<Sample>, range: ClosedFloatingPointRange<Double>, width: Int, height: Int, canvasDensity: Float) {
        if (samples.size < 2 || width <= 0 || height <= 0) return
        val factor = chart.fitTo(width, height, density, canvasDensity)
        chart.showSparkline(samples, range, lineColor, dotColor, ringColor)
        canvas.withScale(factor, factor) { chart.draw(this) }
    }
}

/**
 * Met en page le graphique, en dp d'un écran de densité [density], pour [width] × [height] px d'un
 * écran de densité [canvasDensity] ; renvoie l'échelle à appliquer au dessin. À appeler avant de
 * donner les données : le graphique en a besoin pour placer la courbe.
 */
private fun LineChart.fitTo(width: Int, height: Int, density: Float, canvasDensity: Float): Float {
    // Mise en page bornée : MPAndroidChart dessine la courbe dans une image de sa taille et ignore
    // une dimension de 10 000 px ou plus. Au-delà (écran large et peu dense), les graduations grossissent.
    val factor = maxOf(canvasDensity / density, sqrt(width * height / MAX_LAYOUT_PIXELS), width / MAX_LAYOUT_SIDE, height / MAX_LAYOUT_SIDE)
    val chartWidth = max(1, (width / factor).roundToInt())
    val chartHeight = max(1, (height / factor).roundToInt())
    if (this.width != chartWidth || this.height != chartHeight) {
        // Taille fixée à la main : sans mise en page, le graphique ne connaît pas ses dimensions.
        measure(View.MeasureSpec.makeMeasureSpec(chartWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(chartHeight, View.MeasureSpec.EXACTLY))
        layout(0, 0, chartWidth, chartHeight)
    }
    return factor
}

