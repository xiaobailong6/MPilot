package org.moviepilot.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.moviepilot.android.core.ServerAddress
import org.moviepilot.android.core.ServerAddressError
import org.moviepilot.android.core.classifyFailure
import org.moviepilot.android.core.message
import org.moviepilot.android.net.AuthenticationClient
import org.moviepilot.android.net.AuthenticationException
import org.moviepilot.android.net.AuthenticationReason
import org.moviepilot.android.net.AuthenticationResult

/**
 * 原生登录页，对应 iOS 的 LoginController。
 *
 * 与 iOS 保持一致的两条约束：
 * 1. 密码与 OTP 只在内存中发送，不落盘、不自动重试；
 * 2. 失败提示不包含服务器响应正文。
 */
@Composable
fun ConnectionScreen(
    initialAddress: String,
    initialMessage: String?,
    onAuthenticated: (ServerAddress, AuthenticationResult, Boolean) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    var otpRequired by rememberSaveable { mutableStateOf(false) }
    var rememberToken by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialMessage) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        error = null
        val parsed = try {
            ServerAddress.parse(address)
        } catch (failure: ServerAddressError) {
            error = when (failure) {
                ServerAddressError.UnsupportedScheme -> "地址必须以 http:// 或 https:// 开头。"
                ServerAddressError.CredentialsNotAllowed -> "地址中不能包含用户名或密码。"
                ServerAddressError.QueryOrFragmentNotAllowed -> "地址中不能包含 ? 或 # 参数。"
                ServerAddressError.InvalidUrl -> "请填写完整可访问的 MoviePilot 地址。"
            }
            return
        }
        parsed.webCompatibilityError()?.let { message ->
            error = message
            return
        }
        if (username.isEmpty() || password.isEmpty()) {
            error = "请填写用户名与密码。"
            return
        }
        busy = true
        scope.launch {
            try {
                val client = AuthenticationClient(parsed)
                if (!client.isInitialized()) {
                    throw AuthenticationException(AuthenticationReason.NotInitialized)
                }
                val result = client.login(username, password, otp.takeIf { otpRequired })
                onAuthenticated(parsed, result, rememberToken)
            } catch (failure: AuthenticationException) {
                error = when (failure.reason) {
                    AuthenticationReason.Credentials -> "用户名、密码或验证码不正确。"
                    AuthenticationReason.OtpRequired -> "请输入身份验证器中的 6 位验证码。"
                    AuthenticationReason.UnsupportedVerification ->
                        "当前实例要求的验证方式暂不支持，请在账户设置中改用密码或验证码登录。"
                    AuthenticationReason.NotInitialized ->
                        "此实例尚未初始化，请先在浏览器中创建管理员，再返回 App 重新检查。"
                    AuthenticationReason.Unavailable -> "无法连接此实例，请检查地址、网络和服务状态。"
                    AuthenticationReason.InvalidResponse -> "认证接口返回了不支持的数据，请更新实例后重试。"
                    AuthenticationReason.Throttled ->
                        "登录尝试过于频繁，请在 ${failure.retryAfterSeconds} 秒后重试。"
                }
                if (failure.reason == AuthenticationReason.OtpRequired) otpRequired = true
            } catch (failure: Exception) {
                error = classifyFailure(failure).message ?: "无法连接此实例。"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("MoviePilot", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "连接你自己的 MoviePilot 实例。业务页面由实例前端提供，App 负责原生导航与登录。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("实例地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            placeholder = { Text("http://192.168.1.10:3000/") },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        if (otpRequired) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = otp,
                onValueChange = { otp = it.filter { character -> character.isDigit() }.take(6) },
                label = { Text("身份验证器验证码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberToken, onCheckedChange = { rememberToken = it })
            Spacer(Modifier.width(4.dp))
            Text("在本机记住登录状态", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        GlassButton(
            onClick = { submit() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "正在登录…" else "登录")
        }
        if (busy) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        error?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth().glassSurface().padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "连接失败时请先确认：实例是否已在浏览器完成初始化、地址能否在手机访问、HTTPS 证书是否受系统信任。" +
                "自签 CA 需在系统设置中安装后才会被 App 信任。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}