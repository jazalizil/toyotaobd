package com.example.toyotaobd.data

import com.example.toyotaobd.obd.EnhancedPid
import com.example.toyotaobd.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** PID constructeur ajoutés par l'utilisateur, conservés dans le stockage clé-valeur. */
class CustomPidStore(private val storage: KeyValueStore) {

    fun load(): List<EnhancedPid> {
        val json = storage.getString(KEY) ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(json).jsonArray.map { element ->
                val o = element.jsonObject
                EnhancedPid(
                    id = o.string("id"),
                    name = o.string("name"),
                    header = o.string("header"),
                    command = o.string("command"),
                    formula = o.string("formula"),
                    unit = o.string("unit"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(pids: List<EnhancedPid>) {
        val array: JsonArray = buildJsonArray {
            pids.forEach { pid ->
                add(
                    buildJsonObject {
                        put("id", pid.id)
                        put("name", pid.name)
                        put("header", pid.header)
                        put("command", pid.command)
                        put("formula", pid.formula)
                        put("unit", pid.unit)
                    },
                )
            }
        }
        storage.putString(KEY, array.toString())
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.content

    private companion object {
        const val KEY = "pids"
    }
}
