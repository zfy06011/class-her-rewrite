package com.classher.timetable.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.classher.timetable.domain.ThemePreference

private val Light = lightColorScheme(
    primary = Color(0xFF202428), onPrimary = Color(0xFFFFFCF5),
    primaryContainer = Color(0xFFDCEAF2), onPrimaryContainer = Color(0xFF202428),
    secondaryContainer = Color(0xFFF6D6DF), onSecondaryContainer = Color(0xFF293443),
    background = Color(0xFFFAF6EB), onBackground = Color(0xFF202428),
    surface = Color(0xFFFFFCF5), onSurface = Color(0xFF202428),
    surfaceVariant = Color(0xFFECE7DC), onSurfaceVariant = Color(0xFF545651),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFE6E3DA), onPrimary = Color(0xFF212321),
    primaryContainer = Color(0xFF34434B), onPrimaryContainer = Color(0xFFDDEAF1),
    secondaryContainer = Color(0xFF573A46), onSecondaryContainer = Color(0xFFF0EEE7),
    background = Color(0xFF202321), onBackground = Color(0xFFE6E3DA),
    surface = Color(0xFF292D29), onSurface = Color(0xFFE6E3DA),
    surfaceVariant = Color(0xFF373C36), onSurfaceVariant = Color(0xFFC1C7BD),
)

private val LightCourseColors = listOf(0xFFF6D6DF, 0xFFD4E1FB, 0xFFFAE6B5, 0xFFD0E9E0, 0xFFE3DAF5).map { Color(it) }
private val DarkCourseColors = listOf(0xFF573A46, 0xFF344664, 0xFF554A2E, 0xFF2E4F44, 0xFF463C5C).map { Color(it) }
val LocalCourseColors = staticCompositionLocalOf { LightCourseColors }

@Composable
fun ProbeTheme(preference: ThemePreference = ThemePreference.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (preference) { ThemePreference.SYSTEM -> isSystemInDarkTheme(); ThemePreference.LIGHT -> false; ThemePreference.DARK -> true }
    CompositionLocalProvider(LocalCourseColors provides if (dark) DarkCourseColors else LightCourseColors) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
