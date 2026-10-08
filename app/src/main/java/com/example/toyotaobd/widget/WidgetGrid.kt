package com.example.toyotaobd.widget

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Disposition des tuiles : [visibleTiles] valeurs, plus une tuile « +[hiddenTiles] » si tout ne tient pas. */
data class GridPlan(val columns: Int, val rows: Int, val visibleTiles: Int, val hiddenTiles: Int)

/**
 * Calcule la grille du widget à partir de sa taille réelle (SizeMode.Exact).
 *
 * Les widgets en voiture ne défilent pas : tout doit tenir sur une surface. Si toutes les
 * valeurs du tableau de bord tiennent, elles sont toutes affichées et la grille remplit le
 * widget ; sinon, les plus importantes (ordre du tableau de bord) et une tuile « +N ».
 * Glance limite chaque Row/Column à 10 enfants : colonnes et lignes sont plafonnées à 6.
 */
object WidgetGrid {
    const val MIN_CELL_WIDTH_DP = 76f
    const val MIN_CELL_HEIGHT_DP = 46f
    const val MAX_COLUMNS = 6
    const val MAX_ROWS = 6

    /**
     * Pixels de toutes les tendances d'une taille du widget (4 octets chacun ; Glance envoie une mise en
     * page par taille, portrait et paysage) : la mémoire des images d'un widget est limitée.
     */
    const val TREND_BUDGET_PX = 100_000f

    fun plan(tileCount: Int, widthDp: Float, heightDp: Float): GridPlan {
        val maxColumns = (widthDp / MIN_CELL_WIDTH_DP).toInt().coerceIn(1, MAX_COLUMNS)
        val maxRows = (heightDp / MIN_CELL_HEIGHT_DP).toInt().coerceIn(1, MAX_ROWS)
        val capacity = maxColumns * maxRows
        if (tileCount <= 0) return GridPlan(1, 1, 0, 0)
        if (tileCount <= capacity) {
            // Tout tient : le moins de lignes possible, puis les colonnes rééquilibrées pour limiter les trous.
            val rows = ceil(tileCount / maxColumns.toFloat()).toInt()
            val columns = ceil(tileCount / rows.toFloat()).toInt()
            return GridPlan(columns, rows, tileCount, 0)
        }
        val visible = capacity - 1
        return GridPlan(maxColumns, maxRows, visible, tileCount - visible)
    }

    /**
     * Définition des tendances, en pixels par dp : celle de l'écran ([density]), réduite si [count]
     * images de [widthDp] × [heightDp] dépassent [TREND_BUDGET_PX].
     */
    fun trendPixelsPerDp(count: Int, widthDp: Float, heightDp: Float, density: Float): Float {
        val areaDp = count * widthDp * heightDp
        return if (areaDp <= 0f) density else min(density, sqrt(TREND_BUDGET_PX / areaDp))
    }
}
