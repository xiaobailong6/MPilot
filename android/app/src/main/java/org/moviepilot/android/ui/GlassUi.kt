package org.moviepilot.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** 原生公共材质：不再软件截图 WebView，避免图表闪烁和主线程持续采样。 */
@Composable
internal fun Modifier.glassSurface(shape: Shape = RoundedCornerShape(22.dp)): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = if (dark) {
        listOf(Color(0xFF30323D).copy(alpha = 0.88f), Color(0xFF222530).copy(alpha = 0.76f))
    } else {
        listOf(Color.White.copy(alpha = 0.86f), Color(0xFFF0F2F8).copy(alpha = 0.72f))
    }
    return shadow(8.dp, shape, clip = false)
        .clip(shape)
        .background(Brush.verticalGradient(fill))
        .border(
            0.75.dp,
            Brush.verticalGradient(listOf(
                Color.White.copy(alpha = if (dark) 0.34f else 0.94f),
                Color.White.copy(alpha = if (dark) 0.06f else 0.25f),
            )),
            shape,
        )
}

@Composable
internal fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).glassSurface(RoundedCornerShape(50)),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        elevation = null,
        content = content,
    )
}

/** 独立窗口不能可靠采样底层网页，菜单采用同色系薄材质而不是伪造模糊。 */
@Composable
internal fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.glassSurface(),
        shape = RoundedCornerShape(22.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        content = content,
    )
}
