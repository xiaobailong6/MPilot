package org.moviepilot.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
        darkColorScheme(
            primary = DarkAccent, secondary = DarkAccent,
            background = Color(0xFF0D1220), surface = Color(0xFF252A38),
        )
    } else {
        lightColorScheme(
            primary = LightAccent, secondary = LightAccent,
            background = Color(0xFFF2F3F8), surface = Color(0xFFF9FAFE),
        )
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = androidx.compose.material3.Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}