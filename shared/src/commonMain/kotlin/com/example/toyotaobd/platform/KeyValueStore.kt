package com.example.toyotaobd.platform

/**
 * Stockage clé-valeur persistant : SharedPreferences sur Android, NSUserDefaults sur iOS.
 * Les valeurs sont de simples chaînes (layouts du tableau de bord, PID personnalisés).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}
