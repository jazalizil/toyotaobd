package com.example.toyotaobd.ui

import com.example.toyotaobd.util.fmt

/** Gravité d'un point d'attention remonté par l'assistant. */
enum class HealthSeverity { INFO, WARNING, CRITICAL }

/** Point d'attention calculé depuis les lectures courantes du véhicule. */
data class HealthIssue(val severity: HealthSeverity, val message: String)

/** Score de santé du véhicule (0–100) et points d'attention associés. */
data class HealthReport(val score: Int, val issues: List<HealthIssue>) {
    /** Point d'attention le plus important : critique d'abord, puis avertissement, puis info. */
    val top: HealthIssue?
        get() = issues.firstOrNull { it.severity == HealthSeverity.CRITICAL }
            ?: issues.firstOrNull { it.severity == HealthSeverity.WARNING }
            ?: issues.firstOrNull()
}

/**
 * Score de santé et points d'attention, calculés depuis les lectures courantes.
 * [dtcCount] n'est pris en compte que si les codes ont été lus ([dtcKnown]) pendant cette connexion.
 */
fun computeHealth(
    coolantTempC: Double?,
    voltage: Double?,
    fuelLevelPct: Double?,
    dtcCount: Int,
    dtcKnown: Boolean,
): HealthReport {
    val issues = mutableListOf<HealthIssue>()
    var score = 100
    if (dtcKnown && dtcCount > 0) {
        issues += HealthIssue(
            HealthSeverity.CRITICAL,
            "$dtcCount code${if (dtcCount > 1) "s" else ""} défaut enregistré${if (dtcCount > 1) "s" else ""} (P/C/B/U)",
        )
        score -= minOf(40, dtcCount * 10)
    }
    when {
        coolantTempC != null && coolantTempC > 110 -> {
            issues += HealthIssue(HealthSeverity.CRITICAL, "Liquide de refroidissement très chaud : %.0f °C".fmt(coolantTempC))
            score -= 25
        }
        coolantTempC != null && coolantTempC > 100 -> {
            issues += HealthIssue(HealthSeverity.WARNING, "Liquide de refroidissement chaud : %.0f °C".fmt(coolantTempC))
            score -= 10
        }
    }
    when {
        voltage != null && voltage < 12.2 -> {
            issues += HealthIssue(HealthSeverity.WARNING, "Tension batterie faible : %.1f V (moteur éteint ?)".fmt(voltage))
            score -= 15
        }
        voltage != null && voltage > 15.0 -> {
            issues += HealthIssue(HealthSeverity.WARNING, "Tension de charge élevée : %.1f V".fmt(voltage))
            score -= 10
        }
    }
    if (fuelLevelPct != null && fuelLevelPct < 15) {
        issues += HealthIssue(HealthSeverity.WARNING, "Carburant faible : %.0f %%".fmt(fuelLevelPct))
        score -= 10
    }
    if (issues.isEmpty()) {
        issues += HealthIssue(HealthSeverity.INFO, "Aucun problème détecté, bonne route !")
    }
    return HealthReport(score.coerceIn(0, 100), issues)
}
