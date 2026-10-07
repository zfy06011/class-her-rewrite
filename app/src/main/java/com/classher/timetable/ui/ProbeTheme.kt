package com.classher.timetable.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.classher.timetable.R
import com.classher.timetable.domain.ThemePreference

private val Light = lightColorScheme(
    primary = Color(0xFF202428), onPrimary = Color(0xFFFFFCF5),
    primaryContainer = Color(0xFFDCEAF2), onPrimaryContainer = Color(0xFF202428),
    secondaryContainer = Color(0xFFF6D6DF), onSecondaryContainer = Color(0xFF293443),
    background = Color(0xFFFFFBEF), onBackground = Color(0xFF101525),
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

private val LightCourseColors = listOf(0xFFFBE0EC, 0xFFD7F1FF, 0xFFFFF0BD, 0xFFD9F9E7, 0xFFE8DDFB).map { Color(it) }
private val DarkCourseColors = listOf(0xFF573A46, 0xFF344664, 0xFF554A2E, 0xFF2E4F44, 0xFF463C5C).map { Color(it) }
val LocalCourseColors = staticCompositionLocalOf { LightCourseColors }

data class PaperColors(val ink: Color, val grid: Color, val accent: Color, val morning: Color, val afternoon: Color,
    val evening: Color, val clouds: Color, val skyline: Color, val sun: Color, val shadow: Color, val highlight: Color)
private val LightPaper = PaperColors(Color(0xFF101525), Color(0xFFECE7D5), Color(0xFFFF8AB8),
    Color(0xFFFFEDE0), Color(0xFFD9F1FF), Color(0xFFD7CCF7), Color(0xFFFFFDF2),
    Color(0xFFB5ACDE), Color(0xFFFFCE83), Color(0xFF77777A), Color(0xFF73C6F6))
private val DarkPaper = PaperColors(Color(0xFFEFEBD9), Color(0xFF353B38), Color(0xFFCB799D),
    Color(0xFF443832), Color(0xFF2B414C), Color(0xFF3C3458), Color(0xFF9CA5A6),
    Color(0xFF27243C), Color(0xFFB89561), Color(0xFF101211), Color(0xFF649BB5))
val LocalPaperColors = staticCompositionLocalOf { LightPaper }
private val PixelFont = FontFamily(Font(R.font.fusion_pixel))
private val PixelTypography = Typography().let { base -> base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp),
    headlineLarge = base.headlineLarge.copy(fontFamily = PixelFont, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = PixelFont, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = PixelFont, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = PixelFont, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = PixelFont, fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontFamily = PixelFont),
    bodyLarge = base.bodyLarge.copy(fontFamily = PixelFont), bodyMedium = base.bodyMedium.copy(fontFamily = PixelFont),
    bodySmall = base.bodySmall.copy(fontFamily = PixelFont),
    labelLarge = base.labelLarge.copy(fontFamily = PixelFont), labelMedium = base.labelMedium.copy(fontFamily = PixelFont),
    labelSmall = base.labelSmall.copy(fontFamily = PixelFont),
) }

@Composable
fun ProbeTheme(preference: ThemePreference = ThemePreference.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (preference) { ThemePreference.SYSTEM -> isSystemInDarkTheme(); ThemePreference.LIGHT -> false; ThemePreference.DARK -> true }
    CompositionLocalProvider(LocalCourseColors provides if (dark) DarkCourseColors else LightCourseColors,
        LocalPaperColors provides if (dark) DarkPaper else LightPaper) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = PixelTypography, content = content)
    }
}
