package com.example.toyotaobd.session

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Statistiques d'une série de lectures d'un PID (page de détail). [min] et [max] sont les lectures
 * les plus récentes atteignant ces valeurs.
 */
data class SampleStats(
    val count: Int,
    val first: Sample,
    val last: Sample,
    val min: Sample,
    val max: Sample,
    /** Moyenne pondérée par le temps : chaque lecture compte jusqu'à la suivante, sauf au-delà d'un trou. */
    val mean: Double,
    val median: Double,
    val standardDeviation: Double,
    /** Intervalle moyen entre deux lectures consécutives, trous exclus ; null avec une seule lecture. */
    val meanIntervalMs: Long?,
) {
    val durationMs: Long get() = last.timeMs - first.timeMs
    val amplitude: Double get() = max.value - min.value
    val variation: Double get() = last.value - first.value
}

/**
 * Statistiques des lectures (ordre chronologique), ou null s'il n'y en a aucune. Un écart de plus de
 * [maxGapMs] entre deux lectures est un trou (liaison coupée, valeur lente) : il ne pèse pas dans la
 * moyenne ni dans l'intervalle moyen.
 */
fun List<Sample>.stats(maxGapMs: Long): SampleStats? {
    if (isEmpty()) return null
    var weightedSum = 0.0
    var weightMs = 0L
    var intervals = 0
    for (i in 0 until lastIndex) {
        val dt = this[i + 1].timeMs - this[i].timeMs
        if (dt in 1..maxGapMs) {
            weightedSum += this[i].value * dt
            weightMs += dt
            intervals++
        }
    }
    val values = map { it.value }
    val average = values.average()
    val sorted = values.sorted()
    val median = if (size % 2 == 1) sorted[size / 2] else (sorted[size / 2 - 1] + sorted[size / 2]) / 2
    val minValue = sorted.first()
    val maxValue = sorted.last()
    return SampleStats(
        count = size,
        first = first(),
        last = last(),
        min = last { it.value == minValue },
        max = last { it.value == maxValue },
        mean = if (weightMs > 0) weightedSum / weightMs else average,
        median = median,
        standardDeviation = sqrt(values.sumOf { (it - average).pow(2) } / size),
        meanIntervalMs = if (intervals > 0) weightMs / intervals else null,
    )
}
