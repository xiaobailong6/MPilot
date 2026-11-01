package org.moviepilot.android.core

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 原生认证与网页加载共用的网络失败分类，不输出底层 URL、响应或凭据。
 * 对应 iOS Sources/MoviePilotCore/ConnectionFailure.swift，只是把 NSURLError 换成 Java 异常链。
 */
enum class ConnectionFailure {
    Cancelled,
    Offline,
    TimedOut,
    Hostname,
    Certificate,
    SecureConnection,
    Unavailable,
}

/** 沿异常链判断一次失败的真实原因；未知异常一律归为不可用。 */
fun classifyFailure(error: Throwable): ConnectionFailure {
    var current: Throwable? = error
    repeat(6) {
        val cause = current ?: return ConnectionFailure.Unavailable
        when {
            cause is CancellationException -> return ConnectionFailure.Cancelled
            cause is SocketTimeoutException -> return ConnectionFailure.TimedOut
            cause is UnknownHostException -> return ConnectionFailure.Hostname
            cause is SSLPeerUnverifiedException -> return ConnectionFailure.Certificate
            cause is CertificateException -> return ConnectionFailure.Certificate
            cause is SSLException -> return ConnectionFailure.SecureConnection
            cause is InterruptedIOException -> return ConnectionFailure.TimedOut
        }
        current = cause.cause
    }
    return ConnectionFailure.Unavailable
}

/** 只给出用户可处理的原因，不推断系统权限被拒。 */
val ConnectionFailure.message: String?
    get() = when (this) {
        ConnectionFailure.Cancelled -> null
        ConnectionFailure.Offline -> "当前网络不可用，请检查 Wi-Fi 或移动数据后重试。"
        ConnectionFailure.TimedOut -> "连接超时，请检查网络及服务状态后重试。"
        ConnectionFailure.Hostname -> "无法解析服务地址，请检查主机名及当前网络的 DNS 设置。"
        ConnectionFailure.Certificate -> "服务器的 HTTPS 证书未受系统信任或已失效。请修复证书，或在系统中安装对应 CA 后重试。"
        ConnectionFailure.SecureConnection -> "无法建立安全的 HTTPS 连接，请检查服务器的 TLS 配置。"
        ConnectionFailure.Unavailable -> "无法连接服务，请检查地址、端口和服务状态后重试。"
    }