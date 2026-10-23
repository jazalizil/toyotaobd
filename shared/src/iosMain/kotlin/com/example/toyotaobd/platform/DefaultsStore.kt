package com.example.toyotaobd.platform

import platform.Foundation.NSUserDefaults

/** Stockage clé-valeur iOS : les réglages standard de l'application. */
class DefaultsStore : KeyValueStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}
