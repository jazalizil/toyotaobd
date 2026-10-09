package com.example.toyotaobd.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit

/** Tableau de bord personnalisé, conservé dans les SharedPreferences. */
class DashboardStore(context: Context) {

    private val prefs = context.getSharedPreferences("dashboard", Context.MODE_PRIVATE)

    fun load(): DashboardLayout {
        Log.d("DashboardStore", "Loading dashboard layout wih key $KEY")
        return DashboardLayout.decode(prefs.getString(KEY, null)) ?: DashboardLayout.DEFAULT
    }

    fun save(layout: DashboardLayout) {
        prefs.edit { putString(KEY, layout.encode()) }
    }

    private companion object {
        const val KEY = "layout"
    }
}
