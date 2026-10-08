package com.example.toyotaobd.data

import android.content.Context
import androidx.core.content.edit

/** Tableau de bord personnalisé, conservé dans les SharedPreferences. */
class DashboardStore(context: Context) {

    private val prefs = context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)

    fun load(): DashboardLayout = DashboardLayout.decode(prefs.getString(KEY, null)) ?: DashboardLayout.DEFAULT

    fun save(layout: DashboardLayout) {
        prefs.edit { putString(KEY, layout.encode()) }
    }

    private companion object {
        const val KEY = "layout"
    }
}
