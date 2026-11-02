package org.moviepilot.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil

/**
 * 只采样玻璃下方的网页，不捕获窗口，避免把胶囊自身再次拍进背景。
 * Android 12+ 用 RenderEffect 模糊采样层，文字和图标在独立的 Compose 层保持清晰。
 * 旧系统不显示未模糊的网页副本，由上层半透明材质降级，避免重影。
 */
@Composable
internal fun WebGlassBackdrop(webView: WebView, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        androidx.compose.runtime.key(webView) {
            AndroidView(
                factory = { context -> WebGlassView(context, webView) },
                modifier = modifier,
            )
        }
    }
}

private class WebGlassView(context: Context, private val source: WebView) : View(context) {
    private var sample: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sourceLocation = IntArray(2)
    private val glassLocation = IntArray(2)
    private val destination = Rect()
    private val scale = 0.25f
    // 扩采边缘再模糊，避免胶囊边缘出现透明黑边。
    private val overscan = (24 * resources.displayMetrics.density).toInt()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = 16 * resources.displayMetrics.density
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || source.width <= 0 || source.height <= 0) return
        // 缓冲区只覆盖底栏及模糊边缘，尺寸不变时复用，避免滚动时反复分配位图。
        val sampleWidth = ceil((width + overscan * 2) * scale).toInt()
        val sampleHeight = ceil((height + overscan * 2) * scale).toInt()
        var bitmap = sample
        if (bitmap == null || bitmap.width != sampleWidth || bitmap.height != sampleHeight) {
            bitmap = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
            sample = bitmap
        }
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        source.getLocationInWindow(sourceLocation)
        getLocationInWindow(glassLocation)
        val capture = Canvas(bitmap)
        capture.scale(scale, scale)
        capture.translate(
            (sourceLocation[0] - glassLocation[0] + overscan).toFloat(),
            (sourceLocation[1] - glassLocation[1] + overscan).toFloat(),
        )
        source.draw(capture)
        destination.set(-overscan, -overscan, width + overscan, height + overscan)
        canvas.drawBitmap(bitmap, null, destination, paint)
        // 限制采样到约 12fps；脱离窗口后不再安排刷新，不影响网页本身的帧率。
        if (isAttachedToWindow && windowVisibility == VISIBLE) postInvalidateDelayed(80L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 不手动 recycle：上一帧的 RenderThread 可能仍在读取该位图。
        sample = null
    }
}
