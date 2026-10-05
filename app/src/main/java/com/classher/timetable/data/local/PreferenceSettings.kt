package com.classher.timetable.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.classher.timetable.domain.AppSettings
import com.classher.timetable.domain.ThemePreference
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore("app_settings")

class PreferenceSettings(context: Context) : AppSettings {
    private val store = context.applicationContext.settingsDataStore
    private val key = stringPreferencesKey("theme")
    override val theme = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values -> ThemePreference.entries.firstOrNull { it.name == values[key] } ?: ThemePreference.SYSTEM }
    override suspend fun setTheme(theme: ThemePreference) { store.edit { it[key] = theme.name } }
}
