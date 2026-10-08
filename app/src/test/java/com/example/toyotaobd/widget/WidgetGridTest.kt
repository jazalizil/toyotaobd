package com.example.toyotaobd.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGridTest {

    @Test
    fun `all dashboard values fit on a large widget and fill it`() {
        assertEquals(GridPlan(columns = 6, rows = 4, visibleTiles = 24, hiddenTiles = 0), WidgetGrid.plan(24, 480f, 220f))
    }

    @Test
    fun `small widget shows the first values and a +N tile`() {
        assertEquals(GridPlan(columns = 3, rows = 2, visibleTiles = 5, hiddenTiles = 19), WidgetGrid.plan(24, 250f, 110f))
    }

    @Test
    fun `columns are rebalanced to avoid empty cells`() {
        assertEquals(GridPlan(columns = 4, rows = 2, visibleTiles = 7, hiddenTiles = 0), WidgetGrid.plan(7, 480f, 220f))
    }

    @Test
    fun `grid stays within Glance child limits`() {
        val plan = WidgetGrid.plan(100, 2_000f, 2_000f)
        assertTrue(plan.columns <= 10 && plan.rows <= 10)
        assertEquals(plan.columns * plan.rows, plan.visibleTiles + 1)
    }

    @Test
    fun `degenerate sizes`() {
        assertEquals(GridPlan(1, 1, 0, 24), WidgetGrid.plan(24, 40f, 30f))
        assertEquals(GridPlan(1, 1, 0, 0), WidgetGrid.plan(0, 480f, 220f))
    }

    @Test
    fun `trends keep the screen density while they fit in the budget`() {
        assertEquals(2.75f, WidgetGrid.trendPixelsPerDp(count = 7, widthDp = 70f, heightDp = 20f, density = 2.75f), 0f)
    }

    @Test
    fun `large trends are rendered at a lower density to stay within the budget`() {
        val pixelsPerDp = WidgetGrid.trendPixelsPerDp(count = 8, widthDp = 150f, heightDp = 60f, density = 2.75f)
        assertTrue(pixelsPerDp < 2.75f)
        assertEquals(WidgetGrid.TREND_BUDGET_PX, 8 * 150f * 60f * pixelsPerDp * pixelsPerDp, 1f)
    }
}
