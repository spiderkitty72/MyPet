package com.mypet.app

import android.content.Context
import com.mypet.core.SaveData

/** Saves the game as one JSON string in SharedPreferences. */
class PetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mypet", Context.MODE_PRIVATE)

    fun load(): SaveData = synchronized(LOCK) { SaveData.fromJson(prefs.getString(KEY_SAVE, null)) }

    fun save(data: SaveData) = synchronized(LOCK) {
        prefs.edit().putString(KEY_SAVE, data.toJson()).apply()
    }

    /** Key of the last call we sent a notification for, so each call notifies once. */
    var lastNotifiedCall: String?
        get() = prefs.getString(KEY_NOTIFIED, null)
        set(value) = prefs.edit().putString(KEY_NOTIFIED, value).apply()

    companion object {
        private const val KEY_SAVE = "save"
        private const val KEY_NOTIFIED = "notified_call"
        private val LOCK = Any()
    }
}
