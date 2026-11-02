package com.example.toyotaobd.session

/** Valeur d'un PID à un instant, en millisecondes d'une horloge monotone (seuls les écarts comptent). */
data class Sample(val timeMs: Long, val value: Double)

/**
 * Ajoute [sample] à la fin de l'historique (ordre chronologique) et oublie les valeurs de plus de
 * [windowMs] par rapport à lui, ainsi que les plus anciennes au-delà de [maxSamples] valeurs.
 */
fun List<Sample>.appendWithin(sample: Sample, windowMs: Long, maxSamples: Int = Int.MAX_VALUE): List<Sample> {
    val cutoff = sample.timeMs - windowMs
    val firstInWindow = indexOfFirst { it.timeMs >= cutoff }.let { if (it < 0) size else it }
    val firstKept = maxOf(firstInWindow, size + 1 - maxSamples.coerceAtLeast(1))
    return subList(firstKept, size) + sample
}

/**
 * Sous-liste des échantillons d'au moins [cutoffMs] (liste triée par temps) : recherche binaire,
 * O(log n) même avec des milliers de valeurs (filtrer à chaque lecture ralentissait le défilement).
 */
fun List<Sample>.fromTime(cutoffMs: Long): List<Sample> {
    if (isEmpty()) return this
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].timeMs < cutoffMs) lo = mid + 1 else hi = mid
    }
    return if (lo == 0) this else subList(lo, size)
}

/** Découpe l'historique en tronçons : un écart de plus de [maxGapMs] entre deux valeurs en commence un nouveau. */
fun List<Sample>.segments(maxGapMs: Long): List<List<Sample>> {
    val segments = mutableListOf<MutableList<Sample>>()
    for (sample in this) {
        val current = segments.lastOrNull()
        if (current == null || sample.timeMs - current.last().timeMs > maxGapMs) segments += mutableListOf(sample) else current += sample
    }
    return segments
}
