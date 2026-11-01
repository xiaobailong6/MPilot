package org.moviepilot.android.data

import android.content.Context
import java.util.UUID

/**
 * 通知安装标识，对应 iOS 的 NotificationService.installation。
 * 与账号无关，只用于服务端按安装维度绑定通知；桥接脚本把它交给前端。
 */
object Installation {
    private const val PREFERENCES = "moviepilot.installation.v1"
    private const val KEY = "id"

    fun of(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (existing != null && existing.isNotEmpty()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, created).apply()
        return created
    }
}