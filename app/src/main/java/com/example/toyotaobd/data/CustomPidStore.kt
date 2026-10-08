package com.example.toyotaobd.data

import android.content.Context
import androidx.core.content.edit
import com.example.toyotaobd.obd.EnhancedPid
import org.json.JSONArray
import org.json.JSONObject

/** PID constructeur ajoutés par l'utilisateur, conservés dans les SharedPreferences. */
class CustomPidStore(context: Context) {

    private val prefs = context.getSharedPreferences("custom_pids", Context.MODE_PRIVATE)

    fun load(): List<EnhancedPid> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                EnhancedPid(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    header = o.getString("header"),
                    command = o.getString("command"),
                    formula = o.getString("formula"),
                    unit = o.optString("unit"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(pids: List<EnhancedPid>) {
        val array = JSONArray()
        pids.forEach { pid ->
            array.put(
                JSONObject()
                    .put("id", pid.id)
                    .put("name", pid.name)
                    .put("header", pid.header)
                    .put("command", pid.command)
                    .put("formula", pid.formula)
                    .put("unit", pid.unit),
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
    }

    private companion object {
        const val KEY = "pids"
    }
}
