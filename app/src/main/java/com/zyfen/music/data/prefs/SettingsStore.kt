package com.zyfen.music.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.map

class SettingsStore(
    private val ds: DataStore<Preferences>
) {
    companion object {
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT_MODE = intPreferencesKey("repeat_mode")
    }

    val shuffle = ds.data.map { it[SHUFFLE] ?: false }
    val repeatMode = ds.data.map { it[REPEAT_MODE] ?: 0 }

    suspend fun setShuffle(v: Boolean) { ds.updateData { it.toMutablePreferences().apply { set(SHUFFLE, v) } } }
    suspend fun setRepeatMode(v: Int) { ds.updateData { it.toMutablePreferences().apply { set(REPEAT_MODE, v) } } }
}
