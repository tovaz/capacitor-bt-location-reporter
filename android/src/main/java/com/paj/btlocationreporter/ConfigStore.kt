package com.paj.btlocationreporter

import android.content.Context

/**
 * Utilidad para persistir la configuración completa del servicio como JSON.
 */
object ConfigStore {
    private const val PREFS_NAME = "bt_location_reporter"
    private const val KEY_CONFIG_JSON = "config_json"

    fun saveConfig(context: Context, configJson: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG_JSON, configJson).apply()
    }

    fun getConfig(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CONFIG_JSON, null)
    }

    fun clearConfig(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CONFIG_JSON).apply()
    }
}
