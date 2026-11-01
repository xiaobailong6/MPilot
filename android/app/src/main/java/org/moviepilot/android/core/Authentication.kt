package org.moviepilot.android.core

import android.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 已由现有认证接口签发的登录会话；只生成前端已有 Pinia store 的初始数据，不保存密码。
 * 对应 iOS Sources/MoviePilotCore/Authentication.swift。
 */
class AuthenticationSession private constructor(
    val accessToken: String,
    val user: JsonObject,
) {
    /**
     * 与前端 Pinia 持久化字段一致；通过独立的受信启动脚本交付，不进入导航消息。
     * 结构：{ auth: { token, remember, originalPath }, user: {...} }
     */
    val browserStorage: JsonObject
        get() = buildJsonObject {
            put(
                "auth",
                buildJsonObject {
                    put("token", accessToken)
                    put("remember", true)
                    put("originalPath", JsonNull)
                },
            )
            put("user", user)
        }

    companion object {
        /** 头像可能内嵌在登录资料中，认证响应与 64 KiB 导航消息采用不同限制。 */
        const val MAXIMUM_RESPONSE_BYTES = 4 * 1024 * 1024

        /** 消费密码、OTP 登录接口的成功响应；字段缺失或越界返回 null。 */
        fun fromLoginData(text: String): AuthenticationSession? {
            val payload = payloadOf(text) ?: return null
            val token = payload.stringField("access_token") ?: return null
            val superUser = payload.booleanField("super_user") ?: return null
            val userId = payload.numberField("user_id") ?: return null
            val name = payload.stringField("user_name") ?: return null
            if (!validate(token, userId, name)) return null
            return AuthenticationSession(
                accessToken = token,
                user = buildUser(
                    superUser = superUser,
                    userId = userId,
                    name = name,
                    avatar = payload.stringField("avatar"),
                    level = payload.nonNull("level"),
                    permissions = payload.nonNull("permissions"),
                ),
            )
        }

        /** 恢复令牌前先读取当前用户，使用服务器最新权限，不从持久化快照猜测授权。 */
        fun fromCurrentUser(accessToken: String, text: String): AuthenticationSession? {
            val payload = payloadOf(text) ?: return null
            val superUser = payload.booleanField("is_superuser") ?: return null
            val userId = payload.numberField("id") ?: return null
            val name = payload.stringField("name") ?: return null
            if (!validate(accessToken, userId, name)) return null
            return AuthenticationSession(
                accessToken = accessToken,
                user = buildUser(
                    superUser = superUser,
                    userId = userId,
                    name = name,
                    avatar = payload.stringField("avatar"),
                    level = payload.nonNull("level") ?: levelFromToken(accessToken),
                    permissions = payload.nonNull("permissions"),
                ),
            )
        }

        /** OAuth 表单严格编码每个值，用户名中的加号、与号及非 ASCII 字符不会改变字段。 */
        fun passwordForm(username: String, password: String, otp: String?): String =
            "username=${encode(username)}&password=${encode(password)}&otp_password=${encode(otp ?: "")}"

        /** 兼容统一 data envelope 与登录接口的原始响应，拒绝失败 envelope。 */
        private fun payloadOf(text: String): JsonObject? {
            if (text.toByteArray(Charsets.UTF_8).size > MAXIMUM_RESPONSE_BYTES) return null
            val root = parseJsonObjectOrNull(text) ?: return null
            val success = root.booleanField("success")
            if (success != null) {
                if (!success) return null
                return root.objectField("data")
            }
            return root
        }

        /** 与网页登录的空值默认值一致，旧实例返回 null 时不破坏已有 store 的类型。 */
        private fun JsonObject.nonNull(key: String): JsonElement? {
            val value = this[key]
            if (value == null || value is JsonNull) return null
            return value
        }

        private fun buildUser(
            superUser: Boolean,
            userId: Double,
            name: String,
            avatar: String?,
            level: JsonElement?,
            permissions: JsonElement?,
        ): JsonObject = buildJsonObject {
            put("superUser", superUser)
            put("userID", userId.toInt())
            put("userName", name)
            put("avatar", avatar ?: "")
            put("level", level ?: JsonPrimitive(1))
            put("permissions", permissions ?: JsonObject(emptyMap()))
        }

        /** current 不返回站点等级时沿用已由服务器验证的令牌声明，与网页登录恢复一致。 */
        private fun levelFromToken(token: String): JsonElement {
            val parts = token.split(".")
            if (parts.size != 3) return JsonPrimitive(1)
            val encoded = parts[1].replace('-', '+').replace('_', '/')
            val decoded = try {
                Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (error: Exception) {
                return JsonPrimitive(1)
            }
            val claims = parseJsonObjectOrNull(String(decoded, Charsets.UTF_8)) ?: return JsonPrimitive(1)
            val level = claims.numberField("level") ?: return JsonPrimitive(1)
            if (!level.isFinite() || level < 0 || Math.floor(level) != level) return JsonPrimitive(1)
            return JsonPrimitive(level.toInt())
        }

        /** 限制认证响应的必要身份字段，避免把空令牌或非整数用户写入前端。 */
        private fun validate(token: String, userId: Double, name: String): Boolean {
            if (token.isEmpty() || token.toByteArray(Charsets.UTF_8).size > 8192) return false
            if (token.any { it.isISOControl() }) return false
            if (!userId.isFinite() || userId < 0 || Math.floor(userId) != userId) return false
            if (userId > 9_007_199_254_740_991.0) return false
            if (name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > 512) return false
            return true
        }

        /** RFC 3986 unreserved 之外的字节一律百分号编码。 */
        private fun encode(value: String): String {
            val hex = "0123456789ABCDEF"
            val builder = StringBuilder(value.length)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xFF
                val character = code.toChar()
                val unreserved = character in 'A'..'Z' ||
                    character in 'a'..'z' ||
                    character in '0'..'9' ||
                    character == '-' || character == '.' || character == '_' || character == '~'
                if (unreserved) {
                    builder.append(character)
                } else {
                    builder.append('%').append(hex[code shr 4]).append(hex[code and 0x0F])
                }
            }
            return builder.toString()
        }
    }
}