package com.example.toyotaobd.data

import com.example.toyotaobd.platform.KeyValueStore

/** Tableau de bord personnalisé, conservé dans le stockage clé-valeur de la plateforme. */
class DashboardStore(private val storage: KeyValueStore) {

    fun load(): DashboardLayout =
        DashboardLayout.decode(storage.getString(KEY)) ?: DashboardLayout.DEFAULT

    fun save(layout: DashboardLayout) {
        storage.putString(KEY, layout.encode())
    }

    private companion object {
        const val KEY = "layout"
    }
}
