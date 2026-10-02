package com.classher.timetable.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF202428), onPrimary = Color(0xFFFFFCF5),
    primaryContainer = Color(0xFFDCEAF2), onPrimaryContainer = Color(0xFF202428),
    background = Color(0xFFFAF6EB), onBackground = Color(0xFF202428),
    surface = Color(0xFFFFFCF5), onSurface = Color(0xFF202428),
    surfaceVariant = Color(0xFFECE7DC), onSurfaceVariant = Color(0xFF545651),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFE6E3DA), onPrimary = Color(0xFF212321),
    primaryContainer = Color(0xFF34434B), onPrimaryContainer = Color(0xFFDDEAF1),
    background = Color(0xFF202321), onBackground = Color(0xFFE6E3DA),
    surface = Color(0xFF292D29), onSurface = Color(0xFFE6E3DA),
    surfaceVariant = Color(0xFF373C36), onSurfaceVariant = Color(0xFFC1C7BD),
)

@Composable
fun ProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
