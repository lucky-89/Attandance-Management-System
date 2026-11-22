package com.finowise.hr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF0B3D91)
val Ok = Color(0xFF1E8E3E)
val Warn = Color(0xFFB26A00)
val Danger = Color(0xFFC62828)
val Info = Color(0xFF1565C0)
val Muted = Color(0xFF6B7280)

fun toneColor(tone: String): Color = when (tone) {
    "ok" -> Ok; "warn" -> Warn; "danger" -> Danger; "info" -> Info; else -> Muted
}

private val Light = lightColorScheme(
    primary = Brand, onPrimary = Color.White, primaryContainer = Color(0xFFDCE6FA), onPrimaryContainer = Color(0xFF0A2A63),
    secondary = Color(0xFF00897B), background = Color(0xFFF5F7FB), surface = Color.White, surfaceVariant = Color(0xFFEEF1F6),
    error = Danger,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9DB8F0), onPrimary = Color(0xFF0A2A63), primaryContainer = Color(0xFF173E80),
    secondary = Color(0xFF4DB6AC), error = Color(0xFFEF9A9A),
)

@Composable
fun FinowiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
