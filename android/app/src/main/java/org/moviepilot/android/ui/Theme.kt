package org.moviepilot.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 使用 Material 3 提供排版与基础色；外壳以透明顶栏、磨砂胶囊逼近 iOS 参考图。
 * 网页给出的 accentColor 用于动作和选中图标，不给玻璃材质叠加彩色光晕。
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