package org.moviepilot.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Android 端不做 Liquid Glass 复刻，走 Material 3；
 * 网页给出的 accentColor 会在顶栏/底栏上覆盖主题色，保持与 iOS 端的视觉联动。
 */
private val LightAccent = Color(0xFF2F8FD8)
private val DarkAccent = Color(0xFF7DD3FC)

@Composable
fun MoviePilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        darkColorScheme(primary = DarkAccent, secondary = DarkAccent)
    } else {
        lightColorScheme(primary = LightAccent, secondary = LightAccent)
    }
    MaterialTheme(colorScheme = colors, content = content)
}