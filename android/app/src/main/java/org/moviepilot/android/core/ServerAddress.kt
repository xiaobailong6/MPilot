package org.moviepilot.android.core

import android.net.Uri

/** 实例地址输入错误；只描述配置问题，不包含用户输入。 */
enum class ServerAddressError {
    InvalidUrl,
    UnsupportedScheme,
    CredentialsNotAllowed,
    QueryOrFragmentNotAllowed,
}

/**
 * 标准化后的同源边界，对应 iOS 的 WebOrigin。
 * 协议要求“scheme + host + 有效端口”完全一致才算同一来源，子域与非默认端口都属于另一来源。
 */
class WebOrigin private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    val isSecure: Boolean get() = scheme == "https"

    /** 用于 WebMessageListener 的来源规则，也是同源比较的唯一字符串形式。 */
    val rule: String
        get() {
            val hostPart = if (host.contains(':')) "[$host]" else host
            return if (port == defaultPort(scheme)) "$scheme://$hostPart" else "$scheme://$hostPart:$port"
        }

    fun contains(candidate: Uri?): Boolean = from(candidate) == this

    override fun equals(other: Any?): Boolean =
        other is WebOrigin && other.scheme == scheme && other.host == host && other.port == port

    override fun hashCode(): Int = (scheme.hashCode() * 31 + host.hashCode()) * 31 + port

    override fun toString(): String = rule

    companion object {
        fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

        /** 接收协议、主机与端口；port 传 0 表示使用该协议的默认端口。 */
        fun of(scheme: String, host: String, port: Int): WebOrigin? {
            val normalizedScheme = scheme.lowercase()
            if (normalizedScheme != "https" && normalizedScheme != "http") return null
            val normalizedHost = host.trim().trim('[', ']').lowercase()
            if (normalizedHost.isEmpty()) return null
            if (port < 0 || port > 65535) return null
            val effective = if (port == 0) defaultPort(normalizedScheme) else port
            return WebOrigin(normalizedScheme, normalizedHost, effective)
        }

        /** 从 WebView 提供的来源 URI 提取；端口 -1 表示默认端口。 */
        fun from(uri: Uri?): WebOrigin? {
            if (uri == null) return null
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port < 0) 0 else uri.port
            return of(scheme, host, port)
        }
    }
}

/**
 * 用户配置的 MoviePilot 入口，对应 iOS 的 ServerAddress。
 * 业务路径与认证仍由此地址的网页处理；本类只负责校验、标准化和同源判断。
 */
class ServerAddress private constructor(
    /** 标准化后的完整地址，始终以 `/` 结尾。 */
    val url: String,
    val origin: WebOrigin,
) {
    /** 拼接实例内的固定接口路径，不接受来自网页的任意 URL。 */
    fun apiUrl(path: String): String = (if (url.endsWith("/")) url else "$url/") + path.trimStart('/')

    fun contains(candidate: Uri?): Boolean = origin.contains(candidate)

    /** Foundation 可保存带网卡标识的 IPv6，但浏览器 URL 不支持；此时保留配置并阻止连接。 */
    fun webCompatibilityError(): String? =
        if (origin.host.contains(':') && origin.host.contains('%')) {
            "此 IPv6 地址包含网卡标识，网页无法使用。请改用 .local 主机名或其他可访问的地址。"
        } else {
            null
        }

    override fun equals(other: Any?): Boolean = other is ServerAddress && other.url == url

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String = url

    companion object {
        /**
         * 解析完整 HTTP / HTTPS 服务地址。
         * 禁止 URL 内嵌凭据、查询串和页面片段，并统一主机大小写、去掉默认端口、补齐结尾斜杠。
         */
        fun parse(input: String): ServerAddress {
            val raw = input.trim()
            if (raw.isEmpty() || raw.any { it.isWhitespace() }) throw ServerAddressError.InvalidUrl
            val uri = Uri.parse(raw)
            val scheme = uri.scheme?.lowercase() ?: throw ServerAddressError.InvalidUrl
            if (scheme != "http" && scheme != "https") throw ServerAddressError.UnsupportedScheme
            if (!uri.userInfo.isNullOrEmpty()) throw ServerAddressError.CredentialsNotAllowed
            if (uri.query != null || uri.fragment != null) throw ServerAddressError.QueryOrFragmentNotAllowed
            val host = uri.host
            if (host.isNullOrEmpty()) throw ServerAddressError.InvalidUrl
            val rawPort = uri.port
            if (rawPort != -1 && (rawPort < 1 || rawPort > 65535)) throw ServerAddressError.InvalidUrl
            val origin = WebOrigin.of(scheme, host, if (rawPort == -1) 0 else rawPort)
                ?: throw ServerAddressError.InvalidUrl
            val hostPart = if (origin.host.contains(':')) "[${origin.host}]" else origin.host
            val authority =
                if (origin.port == WebOrigin.defaultPort(scheme)) hostPart else "$hostPart:${origin.port}"
            val rawPath = uri.path ?: ""
            val path = when {
                rawPath.isEmpty() -> "/"
                rawPath.endsWith("/") -> rawPath
                else -> "$rawPath/"
            }
            return ServerAddress("$scheme://$authority$path", origin)
        }

        /** 判断主机是否属于私网、链路本地、回环或本地 DNS，用于界面提示局域网权限。 */
        fun isLocalHost(host: String): Boolean {
            val value = host.lowercase().trim('[', ']')
            if (value == "localhost" || value.endsWith(".local")) return true
            if (value == "::1") return true
            if (value.contains(':')) {
                if (value.startsWith("fc") || value.startsWith("fd")) return true
                for (prefix in arrayOf("fe8", "fe9", "fea", "feb")) {
                    if (value.startsWith(prefix)) return true
                }
            }
            val parts = value.split(".")
            if (parts.size == 4) {
                val numbers = parts.map { it.toIntOrNull() ?: -1 }
                if (numbers.all { it in 0..255 }) {
                    val a = numbers[0]
                    val b = numbers[1]
                    if (a == 10 || a == 127) return true
                    if (a == 192 && b == 168) return true
                    if (a == 172 && b in 16..31) return true
                    if (a == 169 && b == 254) return true
                }
            }
            return !value.contains('.') && !value.contains(':') && value.any { it.isLetter() } &&
                value.all { it.isLetterOrDigit() || it == '-' }
        }
    }
}