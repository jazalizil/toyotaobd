package com.example.toyotaobd.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import com.example.toyotaobd.obd.Pid
import com.example.toyotaobd.obd.ToyotaHybrid
import com.example.toyotaobd.session.Sample
import com.example.toyotaobd.session.segments
import com.example.toyotaobd.ui.ChartScale
import com.example.toyotaobd.ui.GAP_MS
import com.example.toyotaobd.ui.STEERING_CLOCKWISE
import com.example.toyotaobd.ui.drawSteeringWheel
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Image du carrousel, en dp de l'écran de la voiture (l'hôte la cadre dans 480 × 480 dp). */
private const val IMAGE_WIDTH_DP = 360f
private const val IMAGE_HEIGHT_DP = 240f

/**
 * Pixels au plus : l'image traverse la liaison avec l'hôte (limitée à 1 Mo par échange) à chaque
 * rafraîchissement ; 150 000 pixels font 600 Ko.
 */
private const val MAX_IMAGE_PIXELS = 150_000f

/** Taille de l'image en pixels pour un écran de densité [density], dans la limite de [MAX_IMAGE_PIXELS]. */
internal fun carImageSize(density: Float): Pair<Int, Int> {
    val width = IMAGE_WIDTH_DP * density
    val height = IMAGE_HEIGHT_DP * density
    val scale = min(1f, sqrt(MAX_IMAGE_PIXELS / (width * height)))
    return (width * scale).roundToInt() to (height * scale).roundToInt()
}

/**
 * Valeur du carrousel en image, en blanc sur fond transparent (l'écran de la voiture est sombre) :
 * nom, valeur en grand ([text]), puis la courbe des lectures [recent] ou, pour l'angle du volant,
 * le volant tourné de [angle].
 */
internal fun pidImage(pid: Pid, text: String, recent: List<Sample>, angle: Double?, widthPx: Int, heightPx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val u = widthPx / IMAGE_WIDTH_DP // pixels par « dp » de l'image
    val margin = 12 * u

    val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 20 * u }
    val title = TextUtils.ellipsize(pid.name, name, widthPx - 2 * margin, TextUtils.TruncateAt.END).toString()
    canvas.drawText(title, margin, margin + 20 * u, name)

    val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 60 * u; typeface = Typeface.DEFAULT_BOLD }
    val baseline = 104 * u
    canvas.drawText(text, margin, baseline, big)
    if (text.firstOrNull()?.isDigit() == true || text.startsWith("-")) {
        val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 24 * u }
        canvas.drawText(" ${pid.unit}", margin + big.measureText(text), baseline, unit)
    }

    val top = 124 * u
    val bottom = heightPx - margin
    if (pid.code == ToyotaHybrid.STEERING_ANGLE.code) {
        if (angle != null) {
            val size = bottom - top
            canvas.save()
            canvas.translate((widthPx - size) / 2, top)
            drawSteeringWheel(
                canvas, size, size, u, angle.toFloat() * STEERING_CLOCKWISE,
                wheelColor = Color.argb(220, 255, 255, 255),
                markerColor = Color.WHITE,
                referenceColor = Color.argb(150, 255, 255, 255),
            )
            canvas.restore()
        }
    } else {
        drawTrend(canvas, pid, recent, margin, top, widthPx - margin, bottom, u)
    }
    return bitmap
}

/** Courbe des lectures dans le rectangle donné, interrompue aux trous, dernière valeur pointée. */
private fun drawTrend(canvas: Canvas, pid: Pid, recent: List<Sample>, left: Float, top: Float, right: Float, bottom: Float, u: Float) {
    if (recent.size < 2) return
    val scale = ChartScale.of(recent.minOf { it.value }, recent.maxOf { it.value }, pid.range)
    val start = recent.first().timeMs
    val span = (recent.last().timeMs - start).coerceAtLeast(1)
    fun x(sample: Sample) = left + (right - left) * (sample.timeMs - start) / span
    fun y(sample: Sample) = bottom - (bottom - top) * scale.fraction(sample.value)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3 * u
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    for (segment in recent.segments(GAP_MS)) {
        val path = Path()
        segment.forEachIndexed { i, sample -> if (i == 0) path.moveTo(x(sample), y(sample)) else path.lineTo(x(sample), y(sample)) }
        canvas.drawPath(path, line)
    }
    val last = recent.last()
    canvas.drawCircle(x(last), y(last), 5 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
}
