package com.example.toyotaobd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Sens de rotation : l'angle Toyota est positif quand on tourne à gauche (convention ISO 8855,
 * celle du capteur d'angle sur le CAN), soit dans le sens inverse des aiguilles d'une montre.
 * À vérifier sur la voiture : inverser ce signe si le dessin tourne à l'envers.
 */
const val STEERING_CLOCKWISE = -1f

/**
 * Volant à trois branches tourné de [rotation] degrés (sens horaire), centré dans la carte.
 * Le repère en haut de la jante ([markerColor]) s'écarte du repère fixe « roues droites »
 * ([referenceColor]) quand on braque. Le widget et la surface Android Auto dessinent le leur
 * sur un Canvas de la plateforme.
 */
@Composable
fun SteeringWheel(
    rotation: Float,
    wheelColor: Color,
    markerColor: Color,
    referenceColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        // Repère fixe : 3 + 6 dp au-dessus de la jante, réduits sur un petit dessin.
        val side = min(size.width, size.height)
        val gap = min(3.dp.toPx(), side * 0.03f)
        val tick = min(6.dp.toPx(), side * 0.07f)
        val outer = side / 2 - tick - gap
        if (outer <= 0f) return@Canvas
        val rim = outer * 0.16f
        val radius = outer - rim / 2
        val cx = size.width / 2
        val cy = size.height / 2

        // Repère fixe, au-dessus du volant.
        val top = cy - outer - gap
        drawLine(referenceColor, Offset(cx, top - tick), Offset(cx, top), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)

        rotate(degrees = rotation, pivot = Offset(cx, cy)) {
            drawCircle(wheelColor, radius = radius, center = Offset(cx, cy), style = Stroke(width = rim, cap = StrokeCap.Butt))
            // Repère mobile en haut de la jante.
            drawArc(
                color = markerColor,
                startAngle = -105f,
                sweepAngle = 30f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = rim, cap = StrokeCap.Butt),
            )
            // Branches gauche, droite et basse, puis moyeu.
            val hub = radius * 0.32f
            for ((dx, dy) in listOf(-1f to 0f, 1f to 0f, 0f to 1f)) {
                drawLine(
                    wheelColor,
                    Offset(cx + dx * hub, cy + dy * hub),
                    Offset(cx + dx * radius, cy + dy * radius),
                    strokeWidth = rim * 0.8f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(wheelColor, radius = hub, center = Offset(cx, cy))
        }
    }
}
