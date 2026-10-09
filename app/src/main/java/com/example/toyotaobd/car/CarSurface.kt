package com.example.toyotaobd.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.MainThread
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.core.graphics.withTranslation
import com.example.toyotaobd.data.CardStyle
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.ToyotaHybrid
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.ui.ChartScale
import com.example.toyotaobd.ui.GAUGE_HOLE_PERCENT
import com.example.toyotaobd.ui.GAUGE_START_DEGREES
import com.example.toyotaobd.ui.GAUGE_SWEEP_DEGREES
import com.example.toyotaobd.ui.STEERING_CLOCKWISE
import com.example.toyotaobd.ui.SparklineCanvasRenderer
import com.example.toyotaobd.ui.WindowColors
import com.example.toyotaobd.ui.WindowRenderer
import com.example.toyotaobd.ui.boundLabel
import com.example.toyotaobd.ui.drawSteeringWheel
import com.example.toyotaobd.ui.gaugeFill
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Cartes par page du carrousel au plus : une grille de 2 × 2. */
internal const val CARDS_PER_PAGE = 4

/** Marges autour du tableau de bord, en dp de l'écran de la voiture. */
private const val PADDING_DP = 16f

/** Espace entre deux cartes. */
private const val SPACING_DP = 16f

/** Espace entre la courbe et la ligne de texte. */
private const val GAP_DP = 8f

/** Graduations de la courbe. */
private const val LABEL_SIZE_DP = 16f

/** Ligne sous la courbe (nom et lecture) : plus petite qu'une ligne de gabarit. */
private const val TEXT_SIZE_DP = 20f

/** Lecture en grand d'une carte « Valeur » ou d'une jauge (réduite si la jauge est petite). */
private const val BIG_TEXT_SIZE_DP = 36f

/**
 * Valeur montrée par le carrousel : sa ligne [row], sa lecture [value] (null : pas encore lue ou sans
 * réponse) et les lectures [samples] de [pid] ; la courbe montre les [windowMinutes] minutes qui précèdent [endMs].
 */
internal class CarSlide(val row: CarRow, val pid: Pid, val value: Double?, val samples: List<Sample>, val endMs: Long, val windowMinutes: Int)

/**
 * Page du carrousel : ses valeurs [slides], dans l'ordre des cases d'une grille de [cells] cases
 * ([carGrid]), la même pour toutes les pages d'une section (la dernière peut en laisser de vides).
 */
internal class CarPage(val slides: List<CarSlide>, val cells: Int)

/** Case d'une carte sur la surface, en px : dessin de [chartTop] à [chartBottom], ligne de texte posée sur [textBottom], de [left] à [right]. */
internal data class CarFrame(val left: Int, val right: Int, val chartTop: Int, val chartBottom: Int, val textBottom: Int)

/**
 * Cases de [cells] cartes (au plus [CARDS_PER_PAGE]) dans la zone utile ([left], [top], [right],
 * [bottom] en px) d'un écran de densité [density], dans l'ordre de lecture : une carte occupe toute
 * la zone, deux sont empilées sur toute la largeur, trois ou quatre forment une grille de 2 × 2.
 * Chaque case : le dessin de la carte, puis une ligne de texte haute de [lineHeight] px en bas.
 * Toutes les cases ont la même taille.
 */
internal fun carGrid(cells: Int, left: Int, top: Int, right: Int, bottom: Int, density: Float, lineHeight: Float): List<CarFrame> {
    val count = cells.coerceIn(0, CARDS_PER_PAGE)
    val columns = if (count >= 3) 2 else 1
    val rows = if (count >= 2) 2 else 1
    val padding = (PADDING_DP * density).roundToInt()
    val spacing = (SPACING_DP * density).roundToInt()
    val gap = (GAP_DP * density).roundToInt()
    val cellWidth = (right - left - 2 * padding - (columns - 1) * spacing) / columns
    val cellHeight = (bottom - top - 2 * padding - (rows - 1) * spacing) / rows
    return List(count) { index ->
        val x = left + padding + (index % columns) * (cellWidth + spacing)
        val y = top + padding + (index / columns) * (cellHeight + spacing)
        val textBottom = y + cellHeight
        CarFrame(x, x + cellWidth, y, (textBottom - ceil(lineHeight).toInt() - gap).coerceAtLeast(y), textBottom)
    }
}

/**
 * Surface de l'écran de la voiture, derrière le gabarit de navigation : l'application y dessine une
 * page du carrousel du tableau de bord, chaque carte dans sa représentation ([CarRow.style]), comme
 * sur le téléphone : courbe graduée MPAndroidChart ([WindowRenderer]), jauge, volant, ou lecture en
 * grand au-dessus de sa tendance ([SparklineCanvasRenderer]) ; dessous, son nom en petit (et sa
 * lecture, sous une courbe ou un volant).
 * Redessiner ne passe pas par un gabarit : ni quota de l'hôte, ni limite de taille d'image. Rappels
 * de l'hôte et dessin sur le thread principal.
 *
 * [content] donne la page à montrer (null : surface vide), lue à chaque dessin.
 */
@MainThread
internal class CarSurface(context: Context) : SurfaceCallback {

    var content: (() -> CarPage?)? = null
        set(value) {
            field = value
            redraw()
        }

    private var container: SurfaceContainer? = null

    /** La surface de [container] refuse le dessin (signalé une fois). */
    private var unavailable = false
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    /** Un seul graphique par représentation : les cases ayant la même taille, sa mise en page sert pour toutes. */
    private val renderer = WindowRenderer(
        context.applicationContext, LABEL_SIZE_DP,
        WindowColors(
            line = Color.WHITE,
            ring = Color.BLACK,
            labels = Color.argb(200, 255, 255, 255),
            grid = Color.argb(60, 255, 255, 255),
            highlight = Color.WHITE,
        ),
    )
    private val sparkline = SparklineCanvasRenderer(
        context.applicationContext, insetDp = 6f,
        lineColor = Color.argb(140, 255, 255, 255), dotColor = Color.WHITE, ringColor = Color.BLACK,
    )
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }

    /** Lecture en grand (carte « Valeur », jauge). */
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }

    /** Unité à côté ou sous la lecture en grand, bornes de la jauge. */
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface : ${surfaceContainer.width} × ${surfaceContainer.height} px, ${surfaceContainer.dpi} dpi")
        container = surfaceContainer
        unavailable = false
        redraw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.i(TAG, "Zone visible : $visibleArea")
        this.visibleArea = Rect(visibleArea)
        redraw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.i(TAG, "Zone stable : $stableArea")
        this.stableArea = Rect(stableArea)
        redraw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
        // Détache l'application de la surface : l'hôte peut la confier à un autre processus.
        surfaceContainer.surface?.release()
    }

    /** Redessine la surface avec la page que donne [content] ; sans effet tant que l'hôte n'a pas fourni de surface. */
    fun redraw() {
        val container = container ?: return
        val surface = container.surface?.takeIf { it.isValid } ?: return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: IllegalArgumentException) {
            // Surface encore reliée à un processus précédent de l'application, mort sans s'en détacher
            // (réinstallation, plantage) : rien à dessiner jusqu'à ce que l'hôte en fournisse une autre.
            if (!unavailable) Log.w(TAG, "Surface indisponible : quitter puis rouvrir l'application dans la voiture", e)
            unavailable = true
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            val page = content?.invoke() ?: return
            val density = container.dpi / DisplayMetrics.DENSITY_DEFAULT.toFloat()
            // Zone stable : elle tient compte des boutons même masqués, les courbes ne sautent pas quand ils apparaissent.
            val area = stableArea?.takeUnless { it.isEmpty } ?: visibleArea?.takeUnless { it.isEmpty } ?: Rect(0, 0, container.width, container.height)
            namePaint.textSize = TEXT_SIZE_DP * density
            valuePaint.textSize = TEXT_SIZE_DP * density
            labelPaint.textSize = LABEL_SIZE_DP * density
            val metrics = valuePaint.fontMetrics
            val frames = carGrid(page.cells, area.left, area.top, area.right, area.bottom, density, metrics.descent - metrics.ascent)
            for ((slide, frame) in page.slides.zip(frames)) {
                val width = frame.right - frame.left
                val height = frame.chartBottom - frame.chartTop
                val wheel = slide.row.style == CardStyle.GAUGE && slide.pid.code == ToyotaHybrid.STEERING_ANGLE.code
                canvas.withTranslation(frame.left.toFloat(), frame.chartTop.toFloat()) {
                    when {
                        slide.row.style == CardStyle.LINE -> drawLine(this, slide, width, height, density)
                        wheel -> drawWheel(this, slide, width, height, density)
                        slide.row.style == CardStyle.GAUGE -> drawGauge(this, slide, width, height, density)
                        else -> drawValue(this, slide, width, height, density)
                    }
                }

                // Nom à gauche (abrégé s'il manque de place) ; lecture à droite sous une courbe ou un
                // volant (la jauge et la carte « Valeur » l'affichent en grand).
                val baseline = frame.textBottom - metrics.descent
                val value = slide.row.text.takeIf { slide.row.style == CardStyle.LINE || wheel }
                val valueWidth = value?.let { valuePaint.measureText(it) + GAP_DP * density } ?: 0f
                val name = TextUtils.ellipsize(slide.row.title, namePaint, (width - valueWidth).coerceAtLeast(0f), TextUtils.TruncateAt.END)
                canvas.drawText(name, 0, name.length, frame.left.toFloat(), baseline, namePaint)
                if (value != null) canvas.drawText(value, frame.right - valuePaint.measureText(value), baseline, valuePaint)
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Courbe graduée sur la durée des courbes du tableau de bord, bord droit commun à toutes. */
    private fun drawLine(canvas: Canvas, slide: CarSlide, width: Int, height: Int, density: Float) {
        val windowMs = slide.windowMinutes * 60_000L
        val startMs = slide.endMs - windowMs
        val recent = slide.samples.filter { it.timeMs >= startMs }
        val scale = if (recent.isEmpty()) null else ChartScale.of(recent.minOf { it.value }, recent.maxOf { it.value }, slide.pid.range)
        renderer.draw(canvas, recent, scale, startMs, windowMs, width, height, density)
    }

    /** Lecture en grand, unité à côté, puis tendance sur la durée des courbes, jusqu'à la dernière lecture du PID. */
    private fun drawValue(canvas: Canvas, slide: CarSlide, width: Int, height: Int, density: Float) {
        bigPaint.textSize = BIG_TEXT_SIZE_DP * density
        val big = bigPaint.fontMetrics
        val number = slide.value?.let(slide.pid::format) ?: slide.row.text
        val baseline = -big.ascent
        canvas.drawText(number, 0f, baseline, bigPaint)
        if (slide.value != null) canvas.drawText(" ${slide.pid.unit}", bigPaint.measureText(number), baseline, labelPaint)

        val last = slide.samples.lastOrNull()?.timeMs ?: return
        val recent = slide.samples.filter { it.timeMs >= last - slide.windowMinutes * 60_000L }
        val top = (big.descent - big.ascent + GAP_DP * density).roundToInt()
        canvas.withTranslation(0f, top.toFloat()) {
            sparkline.draw(this, recent, slide.pid.range, width, height - top, density)
        }
    }

    /**
     * Anneau de 240° ouvert vers le bas (comme [com.example.toyotaobd.ui.setupGauge] sur le téléphone),
     * rempli selon [gaugeFill] ; lecture et unité au centre, bornes de la plage usuelle sous ses extrémités.
     */
    private fun drawGauge(canvas: Canvas, slide: CarSlide, width: Int, height: Int, density: Float) {
        val label = labelPaint.fontMetrics
        val gap = 2 * density
        val labelHeight = label.descent - label.ascent + gap
        // L'arc occupe 1,5 rayon en hauteur, les bornes s'écrivent dessous : le tout centré dans la case.
        val radius = min(width / 2f, (height - labelHeight) / 1.5f)
        if (radius <= 0f) return
        val centerX = width / 2f
        val centerY = (height - (1.5f * radius + labelHeight)) / 2 + radius

        // Anneau entre le trou et le bord, tracé en son milieu ; bouts droits, comme les parts du graphique.
        val ringMiddle = radius * (1 + GAUGE_HOLE_PERCENT / 100) / 2
        val oval = RectF(centerX - ringMiddle, centerY - ringMiddle, centerX + ringMiddle, centerY + ringMiddle)
        arcPaint.strokeWidth = radius * (1 - GAUGE_HOLE_PERCENT / 100)
        fun arc(from: Float, to: Float, color: Int) {
            if (to <= from) return
            arcPaint.color = color
            canvas.drawArc(oval, GAUGE_START_DEGREES + from * GAUGE_SWEEP_DEGREES, (to - from) * GAUGE_SWEEP_DEGREES, false, arcPaint)
        }
        val fill = slide.value?.let { gaugeFill(slide.pid.range, it) } ?: 0f..0f
        arc(0f, fill.start, GAUGE_TRACK)
        arc(fill.start, fill.endInclusive, Color.WHITE)
        arc(fill.endInclusive, 1f, GAUGE_TRACK)

        // Lecture au centre, réduite pour tenir dans le trou ; unité dessous.
        bigPaint.textSize = min(BIG_TEXT_SIZE_DP * density, radius * 0.4f)
        val big = bigPaint.fontMetrics
        val number = slide.value?.let(slide.pid::format) ?: slide.row.text
        val unit = slide.pid.unit.takeIf { slide.value != null }
        val blockTop = centerY - (big.descent - big.ascent + if (unit != null) label.descent - label.ascent else 0f) / 2
        canvas.drawText(number, centerX - bigPaint.measureText(number) / 2, blockTop - big.ascent, bigPaint)
        if (unit != null) {
            canvas.drawText(unit, centerX - labelPaint.measureText(unit) / 2, blockTop + big.descent - big.ascent - label.ascent, labelPaint)
        }

        // Chaque borne sous le milieu de l'anneau, à son extrémité.
        val range = slide.pid.range
        for ((degrees, bound) in listOf(GAUGE_START_DEGREES to range.start, GAUGE_START_DEGREES + GAUGE_SWEEP_DEGREES to range.endInclusive)) {
            val angle = Math.toRadians(degrees.toDouble())
            val text = boundLabel(bound)
            val x = centerX + ringMiddle * cos(angle).toFloat() - labelPaint.measureText(text) / 2
            val y = centerY + radius * sin(angle).toFloat() + gap - label.ascent
            canvas.drawText(text, x, y, labelPaint)
        }
    }

    /** Volant tourné de l'angle lu (droit et estompé sans lecture), comme sur le téléphone. */
    private fun drawWheel(canvas: Canvas, slide: CarSlide, width: Int, height: Int, density: Float) {
        drawSteeringWheel(
            canvas, width.toFloat(), height.toFloat(), density,
            rotation = (slide.value ?: 0.0).toFloat() * STEERING_CLOCKWISE,
            wheelColor = Color.argb(if (slide.value != null) 200 else 80, 255, 255, 255),
            markerColor = Color.WHITE,
            referenceColor = Color.argb(120, 255, 255, 255),
        )
    }

    private companion object {
        const val TAG = "CarSurface"

        /** Partie vide de la jauge. */
        val GAUGE_TRACK = Color.argb(60, 255, 255, 255)
    }
}
