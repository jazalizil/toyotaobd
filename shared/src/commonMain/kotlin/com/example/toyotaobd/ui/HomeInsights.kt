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

/**
 * Score de santé de la batterie hybride (0–100), calculé depuis les lectures courantes.
 *
 * Chaque famille de batterie a ses indicateurs d'usure, renseignés ou non selon le véhicule :
 * - **lithium TNGA** (ex. Lexus UX 250h, calculateur batterie 747) : [cellSpread], écart de
 *   tension entre éléments en V — sain ~0,02 V (trame réelle UX 250h), à surveiller au-delà de
 *   0,05 V ;
 * - **NiMH HSD 3** : [blockSpread] (écart de tension entre blocs, V — au-delà d'environ 1 V un
 *   bloc décroche), [deltaSoc] (écart de charge entre blocs, %) et [resistanceMax] (résistance
 *   interne max d'un bloc, mΩ, qui augmente avec l'usure).
 * [tempMax] (température max des capteurs batterie, °C) vaut pour les deux familles.
 *
 * La plage de charge min/max (TNGA 106C) n'est PAS un indicateur de capacité : le BMS la resserre
 * en permanence autour du SOC courant (27,0–28,5 % à 28,6 % sur un UX 250h sain) — elle n'entre
 * donc pas dans le score.
 *
 * Retourne `null` si aucun indicateur n'est disponible (pas encore de lecture, ou véhicule qui
 * ne remonte ces données) : pas de score affiché dans ce cas.
 */
fun computeHybridBatteryHealth(
    cellSpread: Double?,
    blockSpread: Double?,
    deltaSoc: Double?,
    resistanceMax: Double?,
    tempMax: Double?,
): HealthReport? {
    val issues = mutableListOf<HealthIssue>()
    var score = 100
    var hasIndicator = false

    // Lithium : écart de tension entre éléments (sain ~0,02 V ; peut monter transitoirement en
    // forte charge, d'où un premier seuil à 0,05 V).
    cellSpread?.let {
        hasIndicator = true
        when {
            it > 0.12 -> {
                issues += HealthIssue(HealthSeverity.CRITICAL, "Éléments batterie très déséquilibrés : %.2f V d'écart".fmt(it))
                score -= 50
            }
            it > 0.08 -> {
                issues += HealthIssue(HealthSeverity.WARNING, "Éléments batterie déséquilibrés : %.2f V d'écart".fmt(it))
                score -= 25
            }
            it > 0.05 -> {
                issues += HealthIssue(HealthSeverity.WARNING, "Écart entre éléments : %.2f V".fmt(it))
                score -= 10
            }
        }
    }
    // NiMH : écart de tension entre blocs (sain : ~0,1–0,3 V ; ~1 V : un bloc décroche).
    blockSpread?.let {
        hasIndicator = true
        when {
            it > 0.9 -> {
                issues += HealthIssue(HealthSeverity.CRITICAL, "Blocs batterie très déséquilibrés : %.2f V d'écart".fmt(it))
                score -= 50
            }
            it > 0.6 -> {
                issues += HealthIssue(HealthSeverity.WARNING, "Blocs batterie déséquilibrés : %.2f V d'écart".fmt(it))
                score -= 25
            }
            it > 0.3 -> {
                issues += HealthIssue(HealthSeverity.WARNING, "Écart entre blocs : %.2f V".fmt(it))
                score -= 10
            }
        }
    }
    // NiMH : écart de charge entre blocs (%).
    deltaSoc?.let {
        hasIndicator = true
        when {
            it > 10 -> {
                issues += HealthIssue(HealthSeverity.CRITICAL, "Écart de charge entre blocs élevé : %.0f %%".fmt(it))
                score -= 25
            }
            it > 5 -> {
                issues += HealthIssue(HealthSeverity.WARNING, "Écart de charge entre blocs : %.0f %%".fmt(it))
                score -= 10
            }
        }
    }
    // NiMH : résistance interne max d'un bloc (mΩ) ; ~20–28 mΩ neuf, augmente avec l'usure.
    resistanceMax?.let {
        hasIndicator = true
        when {
            it > 50 -> {
                issues += HealthIssue(HealthSeverity.CRITICAL, "Résistance interne élevée : %.0f mΩ (usure)".fmt(it))
                score -= 40
            }
            it > 40 -> {
                issues += HealthIssue(HealthSeverity.WARNING, "Résistance interne haute : %.0f mΩ".fmt(it))
                score -= 25
            }
            it > 30 -> {
                issues += HealthIssue(HealthSeverity.WARNING, "Résistance interne : %.0f mΩ".fmt(it))
                score -= 10
            }
        }
    }
    // Température max des capteurs batterie, les deux familles. En été la batterie d'un hybride
    // monte couramment à 40–45 °C (ventilateur) : les seuils ne visent que l'anormal.
    tempMax?.let {
        hasIndicator = true
        when {
            it > 60 -> {
                issues += HealthIssue(HealthSeverity.CRITICAL, "Batterie très chaude : %.0f °C".fmt(it))
                score -= 25
            }
            it > 50 -> {
                issues += HealthIssue(HealthSeverity.WARNING, "Batterie chaude : %.0f °C".fmt(it))
                score -= 10
            }
        }
    }
    if (!hasIndicator) return null
    if (issues.isEmpty()) {
        issues += HealthIssue(HealthSeverity.INFO, "Batterie hybride en bon état")
    }
    return HealthReport(score.coerceIn(0, 100), issues)
}
