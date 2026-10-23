package com.example.toyotaobd.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import com.example.toyotaobd.util.fmt

/** Échelle verticale d'une courbe : bornes arrondies et graduations régulières de [step]. */
data class ChartScale(val min: Double, val max: Double, val step: Double) {

    val ticks: List<Double>
        get() = generateSequence(min) { it + step }.takeWhile { it <= max + step / 1_000 }.toList()

    /** Position de [value] entre 0 (bas) et 1 (haut), bornée à l'échelle. */
    fun fraction(value: Double): Float = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()

    /** Graduation sans décimales inutiles : 2000, 2,5, 0,25. */
    fun label(value: Double): String {
        val decimals = (0..3).firstOrNull { d ->
            val scaled = step * 10.0.pow(d)
            abs(scaled - scaled.roundToLong()) < 1e-6
        } ?: 3
        return "%.${decimals}f".fmt(value)
    }

    companion object {
        private val NICE_STEPS = listOf(1.0, 2.0, 2.5, 5.0, 10.0)

        /**
         * Échelle couvrant [low]..[high] avec environ [intervals] intervalles. L'amplitude est au moins
         * le dixième de la plage usuelle du PID ([usual]) : un régime stable à 750 tr/min reste une
         * ligne plate au lieu de grossir le bruit. Tant que les valeurs y sont, l'échelle ne sort pas
         * de la plage usuelle (pas de régime négatif).
         */
        fun of(low: Double, high: Double, usual: ClosedFloatingPointRange<Double>, intervals: Int = 2): ChartScale {
            var lo = minOf(low, high)
            var hi = maxOf(low, high)
            val minSpan = ((usual.endInclusive - usual.start) / 10).takeIf { it > 0 } ?: 1.0
            if (hi - lo < minSpan) {
                val middle = (lo + hi) / 2
                lo = middle - minSpan / 2
                hi = middle + minSpan / 2
                if (lo < usual.start && minOf(low, high) >= usual.start) {
                    hi += usual.start - lo
                    lo = usual.start
                } else if (hi > usual.endInclusive && maxOf(low, high) <= usual.endInclusive) {
                    lo -= hi - usual.endInclusive
                    hi = usual.endInclusive
                }
            }
            val raw = (hi - lo) / intervals
            val magnitude = 10.0.pow(floor(log10(raw)))
            val step = NICE_STEPS.first { it * magnitude >= raw - 1e-9 } * magnitude
            return ChartScale(min = floor(lo / step + 1e-9) * step, max = ceil(hi / step - 1e-9) * step, step = step)
        }
    }
}
