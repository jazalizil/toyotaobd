package com.example.toyotaobd.platform

import android.content.Context

/** Stockage clé-valeur Android : un fichier SharedPreferences par [name]. */
class SharedPreferencesStore(context: Context, name: String) : KeyValueStore {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
