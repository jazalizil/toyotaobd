package com.example.toyotaobd.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.min

// Le sens de rotation (STEERING_CLOCKWISE) et le composable du téléphone sont dans le module
// partagé ; ce fichier ne garde que le dessin Android (Canvas) du widget.

/**
 * Volant à trois branches tourné de [rotation] degrés (sens horaire), centré dans [width] × [height]
 * pixels. Le repère en haut de la jante ([markerColor]) s'écarte du repère fixe « roues droites »
 * ([referenceColor]) quand on braque. Dessin commun à la carte de l'application et au widget.
 */
fun drawSteeringWheel(
    canvas: Canvas,
    width: Float,
    height: Float,
    density: Float,
    rotation: Float,
    wheelColor: Int,
    markerColor: Int,
    referenceColor: Int,
) {
    // Repère fixe : 3 + 6 dp au-dessus de la jante, réduits sur un petit dessin (tuile du widget).
    val side = min(width, height)
    val gap = min(3 * density, side * 0.03f)
    val tick = min(6 * density, side * 0.07f)
    val outer = side / 2 - tick - gap
    if (outer <= 0) return
    val rim = outer * 0.16f
    val radius = outer - rim / 2
    val cx = width / 2
    val cy = height / 2
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // Repère fixe, au-dessus du volant.
    val top = cy - outer - gap
    paint.color = referenceColor
    paint.strokeWidth = 2 * density
    paint.strokeCap = Paint.Cap.ROUND
    canvas.drawLine(cx, top - tick, cx, top, paint)

    canvas.save()
    canvas.rotate(rotation, cx, cy)
    paint.strokeWidth = rim
    paint.strokeCap = Paint.Cap.BUTT
    paint.color = wheelColor
    canvas.drawCircle(cx, cy, radius, paint)
    paint.color = markerColor
    canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -105f, 30f, false, paint)
    // Branches gauche, droite et basse, puis moyeu.
    val hub = radius * 0.32f
    paint.color = wheelColor
    paint.strokeWidth = rim * 0.8f
    paint.strokeCap = Paint.Cap.ROUND
    for ((dx, dy) in listOf(-1f to 0f, 1f to 0f, 0f to 1f)) {
        canvas.drawLine(cx + dx * hub, cy + dy * hub, cx + dx * radius, cy + dy * radius, paint)
    }
    paint.style = Paint.Style.FILL
    canvas.drawCircle(cx, cy, hub, paint)
    canvas.restore()
}

/**
 * Volant du widget, tourné de [angle] degrés d'angle du volant : un widget n'affiche pas de dessin
 * Compose, l'image est donc calculée ici. Blanc et transparent, il est teinté par le widget (thème
 * clair ou sombre, couleurs dynamiques) ; pâle, car le texte de la tuile passe devant.
 */
fun steeringWheelBitmap(sizePx: Int, density: Float, angle: Double): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawSteeringWheel(
        Canvas(bitmap),
        sizePx.toFloat(),
        sizePx.toFloat(),
        density,
        rotation = angle.toFloat() * STEERING_CLOCKWISE,
        wheelColor = Color.argb(80, 255, 255, 255),
        markerColor = Color.argb(150, 255, 255, 255),
        referenceColor = Color.argb(70, 255, 255, 255),
    )
    return bitmap
}
