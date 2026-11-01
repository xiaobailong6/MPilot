package org.moviepilot.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.moviepilot.android.data.InstanceStore
import org.moviepilot.android.data.SavedInstance
import org.moviepilot.android.data.SecretStore
import org.moviepilot.android.net.AuthenticationClient
import org.moviepilot.android.net.AuthenticationResult

/** 应用级页面状态，只活在内存中；持久化只有实例配置与加密令牌。 */
private sealed interface Stage {
    data object Connecting : Stage
    data object Restoring : Stage
    data class Connect(val address: String, val message: String?) : Stage
    data class Shell(val instance: SavedInstance, val authentication: AuthenticationResult, val epoch: Int) : Stage
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val instanceStore = remember { InstanceStore(context) }
    val secretStore = remember { SecretStore(context) }
    var stage by remember { mutableStateOf<Stage>(Stage.Connecting) }

    // 冷启动：只有本机保存过令牌且 current 接口仍通过时才直接进入外壳。
    LaunchedEffect(instanceStore) {
        val selected = instanceStore.selected()
        val token = selected?.let { secretStore.get(SecretStore.tokenKey(it.id)) }
        if (selected != null && token != null) {
            stage = Stage.Restoring
            val restored = try {
                AuthenticationClient(selected.address).restore(token)
            } catch (error: Exception) {
                null
            }
            stage = if (restored != null) {
                Stage.Shell(selected, restored, 0)
            } else {
                Stage.Connect(selected.address.url, "登录已过期，请重新登录。")
            }
        } else {
            stage = Stage.Connect(selected?.address?.url ?: "", null)
        }
    }

    when (val current = stage) {
        Stage.Connecting, Stage.Restoring -> SplashScreen(
            message = if (current is Stage.Restoring) "正在恢复登录…" else "正在准备…",
        )

        is Stage.Connect -> ConnectionScreen(
            initialAddress = current.address,
            initialMessage = current.message,
            onAuthenticated = { address, result, rememberToken ->
                val saved = instanceStore.select(address)
                val key = SecretStore.tokenKey(saved.id)
                if (rememberToken) secretStore.put(key, result.session.accessToken) else secretStore.remove(key)
                stage = Stage.Shell(saved, result, 0)
            },
        )

        is Stage.Shell -> ShellScreen(
            instance = current.instance,
            authentication = current.authentication,
            epoch = current.epoch,
            onSignOut = {
                secretStore.remove(SecretStore.tokenKey(current.instance.id))
                stage = Stage.Connect(current.instance.address.url, "已退出登录。")
            },
            // 渲染进程崩溃或需要整体重建 WebView 时换一个 epoch，触发全新容器。
            onFatal = { stage = current.copy(epoch = current.epoch + 1) },
        )
    }
}

@Composable
private fun SplashScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}