package org.moviepilot.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.moviepilot.android.core.AuthenticationSession
import org.moviepilot.android.core.ServerAddress
import org.moviepilot.android.core.arrayField
import org.moviepilot.android.core.booleanField
import org.moviepilot.android.core.objectField
import org.moviepilot.android.core.parseJsonObjectOrNull
import java.util.concurrent.TimeUnit

/** 认证失败原因；只向界面暴露可操作提示，不回显服务器响应、令牌或输入。 */
enum class AuthenticationReason {
    Credentials,
    OtpRequired,
    UnsupportedVerification,
    NotInitialized,
    Unavailable,
    InvalidResponse,
    Throttled,
}

class AuthenticationException(
    val reason: AuthenticationReason,
    val retryAfterSeconds: Int = 60,
) : Exception(reason.name)

/** 一次认证交接包含既有用户数据和服务签发的 Cookie，不包含用户密码。 */
data class AuthenticationResult(
    val session: AuthenticationSession,
    val cookies: List<String>,
)

/**
 * 只访问当前实例的既有认证接口，对应 iOS 的 AuthenticationClient。
 *
 * 与 iOS 一致的三条边界：
 * 1. 固定相对路径，不成为通用网络代理；
 * 2. 关闭自动重定向，绝不把密码或令牌转发到其他来源；
 * 3. Cookie 只交给同一实例的 WebView 容器。
 */
class AuthenticationClient(private val address: ServerAddress) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 首次连接探测现有初始化接口；没有该接口的旧实例仍可尝试原有登录。 */
    suspend fun isInitialized(): Boolean = withContext(Dispatchers.IO) {
        val response = request("api/v1/login/initialization", null, null)
        if (response.status == 404) return@withContext true
        if (response.status != 200) throw AuthenticationException(AuthenticationReason.InvalidResponse)
        val root = parseJsonObjectOrNull(response.body)
            ?: throw AuthenticationException(AuthenticationReason.InvalidResponse)
        val data = root.objectField("data")
            ?: throw AuthenticationException(AuthenticationReason.InvalidResponse)
        data.booleanField("initialized")
            ?: throw AuthenticationException(AuthenticationReason.InvalidResponse)
    }

    /** 只在内存中发送密码与 OTP，不记录、不自动重试。 */
    suspend fun login(username: String, password: String, otp: String?): AuthenticationResult =
        withContext(Dispatchers.IO) {
            val form = AuthenticationSession.passwordForm(username, password, otp)
                .toRequestBody(FORM_MEDIA_TYPE)
            val response = request("api/v1/login/access-token", form, null)
            if (response.status == 401) {
                if (response.mfaRequired) {
                    val methods = parseJsonObjectOrNull(response.body)
                        ?.objectField("data")
                        ?.arrayField("mfa_methods")
                        ?.mapNotNull { element -> element.toString().trim('"').takeIf { it.isNotEmpty() } }
                        .orEmpty()
                    throw AuthenticationException(
                        if (methods == listOf("otp")) {
                            AuthenticationReason.OtpRequired
                        } else {
                            AuthenticationReason.UnsupportedVerification
                        },
                    )
                }
                throw AuthenticationException(AuthenticationReason.Credentials)
            }
            if (response.status != 200) throw AuthenticationException(AuthenticationReason.InvalidResponse)
            val session = AuthenticationSession.fromLoginData(response.body)
                ?: throw AuthenticationException(AuthenticationReason.InvalidResponse)
            AuthenticationResult(session, response.cookies)
        }

    /** 本机保存的令牌必须通过现有 current 接口验证后才交给页面。 */
    suspend fun restore(token: String): AuthenticationResult = withContext(Dispatchers.IO) {
        val response = request("api/v1/user/current", null, token)
        if (response.status == 401 || response.status == 403) {
            throw AuthenticationException(AuthenticationReason.Credentials)
        }
        if (response.status != 200) throw AuthenticationException(AuthenticationReason.InvalidResponse)
        val session = AuthenticationSession.fromCurrentUser(token, response.body)
            ?: throw AuthenticationException(AuthenticationReason.InvalidResponse)
        AuthenticationResult(session, response.cookies)
    }

    private class Response(
        val status: Int,
        val body: String,
        val cookies: List<String>,
        val mfaRequired: Boolean,
    )

    /** 固定相对路径 + 不跟随重定向的传输边界。 */
    private fun request(path: String, body: RequestBody?, token: String?): Response {
        val builder = Request.Builder()
            .url(address.apiUrl(path))
            .header("Accept", "application/json")
            .header("X-MoviePilot-Locale", "zh-CN")
        if (body != null) builder.post(body)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val call = client.newCall(builder.build())
        val response = try {
            call.execute()
        } catch (error: Exception) {
            throw AuthenticationException(AuthenticationReason.Unavailable)
        }
        return response.use { result ->
            val text = result.body?.string().orEmpty()
            if (text.toByteArray(Charsets.UTF_8).size > AuthenticationSession.MAXIMUM_RESPONSE_BYTES) {
                throw AuthenticationException(AuthenticationReason.InvalidResponse)
            }
            if (result.code == 429) {
                val seconds = result.header("Retry-After")?.toIntOrNull() ?: 60
                throw AuthenticationException(
                    AuthenticationReason.Throttled,
                    retryAfterSeconds = seconds.coerceIn(1, 3600),
                )
            }
            if (result.code >= 500) throw AuthenticationException(AuthenticationReason.Unavailable)
            Response(
                status = result.code,
                body = text,
                cookies = result.headers("Set-Cookie"),
                mfaRequired = result.header("X-MFA-Required")?.lowercase() == "true",
            )
        }
    }

    private companion object {
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    }
}