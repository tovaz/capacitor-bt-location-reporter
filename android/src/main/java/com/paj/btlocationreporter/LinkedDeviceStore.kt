package com.paj.btlocationreporter

import android.content.Context
import android.content.SharedPreferences

/**
 * Utilidad para persistir la lista de dispositivos linked en SharedPreferences.
 */
object LinkedDeviceStore {
    private const val PREFS_NAME = "bt_location_reporter"
    private const val KEY_LINKED = "linked_device_ids"

    fun saveLinkedDevices(context: Context, ids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_LINKED, ids).apply()
    }

    fun getLinkedDevices(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LINKED, emptySet()) ?: emptySet()
    }

    fun clearLinkedDevices(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LINKED).apply()
    }
}
