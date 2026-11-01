package org.moviepilot.android.core

import kotlinx.serialization.json.JsonObject

/** 桥接协议的固定标识，与 iOS 端 BridgeProtocol.swift 完全一致。 */
const val BRIDGE_PROTOCOL = "moviepilot.chrome"
const val BRIDGE_MAJOR = 1

/** 单条消息的字节上限；超限消息在任何解析之前就被拒绝。 */
const val MAXIMUM_MESSAGE_BYTES = 65_536

/** 协议错误码；错误信息不携带地址、正文或认证数据。 */
enum class BridgeError(val code: String) {
    InvalidMessage("invalid_message"),
    UnsupportedVersion("unsupported_version"),
    UnsupportedCapability("unsupported_capability"),
    UntrustedFrame("untrusted_frame"),
    StaleSession("stale_session"),
    StaleRoute("stale_route"),
    ActionNotAvailable("action_not_available"),
    Busy("busy"),
}

class BridgeException(val error: BridgeError) : Exception(error.code)

/**
 * 来源信息只能由容器提供，不能从消息正文读取。
 * isCurrentWebView 对应 iOS 的 `message.webView === webView`：只有当前那一个 WebView 算受信。
 */
data class BridgeSource(
    val isMainFrame: Boolean,
    val isCurrentWebView: Boolean,
    val origin: WebOrigin?,
)

/** 原生与网页共享的消息外壳；sessionId 是关联标识，不是认证凭据。 */
class BridgeEnvelope(
    val type: String,
    val requestId: String,
    val sessionId: String?,
    val routeRevision: Int?,
    val stateRevision: Int?,
    val payload: JsonObject,
)

/**
 * 所有入站消息共同使用的边界检查：先校验来源与大小，再解析 JSON。
 * 与 iOS BridgeValidator 一一对应，任何一项不满足都抛 BridgeException。
 */
class BridgeValidator(private val origin: WebOrigin) {

    fun decode(text: String, source: BridgeSource, sessionId: String?): BridgeEnvelope {
        if (!source.isMainFrame || !source.isCurrentWebView || source.origin != origin) {
            throw BridgeException(BridgeError.UntrustedFrame)
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAXIMUM_MESSAGE_BYTES) {
            throw BridgeException(BridgeError.InvalidMessage)
        }
        if (!isBoundedJson(text)) throw BridgeException(BridgeError.InvalidMessage)
        val root = parseJsonObjectOrNull(text) ?: throw BridgeException(BridgeError.InvalidMessage)
        val type = root.stringField("type")
        if (root.stringField("protocol") != BRIDGE_PROTOCOL || type.isNullOrEmpty()) {
            throw BridgeException(BridgeError.InvalidMessage)
        }
        val requestId = root.stringField("requestId")
        if (requestId == null || !isIdentifier(requestId)) {
            throw BridgeException(BridgeError.InvalidMessage)
        }
        val routeRevision = optionalRevision(root, "routeRevision")
        val stateRevision = optionalRevision(root, "stateRevision")
        if (root.intField("major") != BRIDGE_MAJOR) {
            throw BridgeException(BridgeError.UnsupportedVersion)
        }
        val envelopeSession = root.stringField("sessionId")
        if (type != "web.ready" && (sessionId == null || envelopeSession != sessionId)) {
            throw BridgeException(BridgeError.StaleSession)
        }
        return BridgeEnvelope(
            type = type,
            requestId = requestId,
            sessionId = envelopeSession,
            routeRevision = routeRevision,
            stateRevision = stateRevision,
            payload = root.objectField("payload") ?: JsonObject(emptyMap()),
        )
    }

    companion object {
        /** 标识限制为可打印短字符串，阻止日志与注册表被无界标识污染。 */
        fun isIdentifier(value: String): Boolean =
            value.isNotEmpty() &&
                value.toByteArray(Charsets.UTF_8).size <= 128 &&
                value.all { it.code in 0x21..0x7E }

        /** 修订号必须是 0..2^53-1 的整数，与 JS 的 Number 安全范围一致。 */
        private fun optionalRevision(root: JsonObject, key: String): Int? {
            if (root[key] == null) return null
            val value = root.intField(key) ?: throw BridgeException(BridgeError.InvalidMessage)
            if (value < 0 || value > 9_007_199_254_740_991L) {
                throw BridgeException(BridgeError.InvalidMessage)
            }
            return value
        }

        /** 解码前扫描结构深度；引号内的括号不影响深度，JSON 语法仍交给解析器。 */
        private fun isBoundedJson(text: String): Boolean {
            var depth = 0
            var quoted = false
            var escaped = false
            for (character in text) {
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> quoted = false
                    }
                } else {
                    when (character) {
                        '"' -> quoted = true
                        '{', '[' -> {
                            depth++
                            if (depth > 16) return false
                        }
                        '}', ']' -> depth--
                    }
                }
            }
            return depth == 0 && !quoted
        }
    }
}