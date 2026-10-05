package com.classher.timetable.domain

import kotlinx.coroutines.flow.Flow

enum class ThemePreference { SYSTEM, LIGHT, DARK }
interface AppSettings {
    val theme: Flow<ThemePreference>
    suspend fun setTheme(theme: ThemePreference)
}
