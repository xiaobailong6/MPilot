package org.moviepilot.android.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.moviepilot.android.core.ActionPosition
import org.moviepilot.android.core.ActionSnapshot
import org.moviepilot.android.core.MoreMenuItem
import org.moviepilot.android.core.NavigationSnapshot
import org.moviepilot.android.data.Installation
import org.moviepilot.android.data.SavedInstance
import org.moviepilot.android.net.AuthenticationResult
import org.moviepilot.android.web.WebCoordinator

/**
 * 原生外壳：顶栏、页内标签、底部入口、WebView 与恢复页。
 *
 * 结构上刻意把 WebView 固定在同一层级：
 * Android 的 WebView 一旦跨 Window 或跨父容器重新挂载，就会出现重绘丢失甚至重载，
 * 因此业务 Sheet 必须做成“同窗口浮层”，不能用 Dialog（详见 android/PORTING.md）。
 */
@Composable
fun ShellScreen(
    instance: SavedInstance,
    authentication: AuthenticationResult,
    epoch: Int,
    onSignOut: () -> Unit,
    onFatal: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coordinator = remember(instance.id, epoch) {
        // 必须用 Activity context：JS 弹窗、文件选择、权限申请都要靠它拿 window token。
        WebCoordinator(context, instance, authentication, Installation.of(context))
    }
    var snapshot by remember { mutableStateOf<NavigationSnapshot?>(null) }
    var recovery by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var prepareRequested by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(coordinator) {
        coordinator.layoutProvider = { revision -> shellLayoutPayload(revision, viewport, density) }
        coordinator.onSnapshot = { value -> snapshot = value }
        coordinator.onRecovery = { message -> recovery = message }
        coordinator.onLoadingChanged = { value -> loading = value }
        coordinator.onPrepareRequested = { prepareRequested = true }
        coordinator.onFatal = { onFatal() }
        coordinator.onAuthenticationRequired = { onSignOut() }
        coordinator.start()
        onDispose {
            coordinator.stop()
            (coordinator.webView.parent as? ViewGroup)?.removeView(coordinator.webView)
            coordinator.webView.destroy()
        }
    }

    DisposableEffect(lifecycleOwner, coordinator) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> coordinator.onLifecycleChange("active")
                Lifecycle.Event.ON_STOP -> coordinator.onLifecycleChange("background")
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 网页送来首份快照后，等两帧确保 WebView 已完成布局，再确认 chrome.prepare。
    LaunchedEffect(coordinator, prepareRequested) {
        if (!prepareRequested) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        prepareRequested = false
        coordinator.completePrepare()
    }

    LaunchedEffect(coordinator, viewport) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        withFrameNanos { }
        coordinator.layoutChanged()
    }

    val current = snapshot
    val chrome = current?.chrome
    val accent = chrome?.accentColor?.let(::parseHexColor)

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            if (current != null && chrome != null && chrome.topVisible) {
                ShellTopBar(
                    snapshot = current,
                    accent = accent,
                    onBack = { coordinator.back() },
                    onAction = { action, menuActionId -> coordinator.invokeAction(action, menuActionId) },
                    onSelectTab = { ownerId, tabId -> coordinator.selectHeaderTab(ownerId, tabId) },
                    onOpenMoreItem = { item -> coordinator.openMoreMenuItem(item) },
                    onSignOut = onSignOut,
                )
            }
            AndroidView(
                factory = { coordinator.webView },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { size -> viewport = size },
            )
            if (current != null && chrome != null && chrome.bottomVisible && current.roots.isNotEmpty()) {
                ShellBottomBar(
                    snapshot = current,
                    accent = accent,
                    onSelect = { rootId -> coordinator.selectRoot(rootId) },
                )
            }
        }
        if (loading && snapshot == null) {
            LoadingOverlay()
        }
        recovery?.let { message ->
            RecoveryOverlay(
                message = message,
                onRetry = {
                    recovery = null
                    coordinator.retry()
                },
                onSignOut = onSignOut,
            )
        }
    }
}

@Composable
private fun ShellTopBar(
    snapshot: NavigationSnapshot,
    accent: Color?,
    onBack: () -> Unit,
    onAction: (ActionSnapshot, String?) -> Unit,
    onSelectTab: (String, String) -> Unit,
    onOpenMoreItem: (MoreMenuItem) -> Unit,
    onSignOut: () -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (snapshot.route.canGoBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = snapshot.route.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                // 顺序与 iOS 一致：leading 在标题左侧语义，这里统一放到尾部按钮组，先 accessory 后 trailing。
                val buttons = snapshot.actions.filter {
                    it.position == ActionPosition.Accessory || it.position == ActionPosition.Trailing
                }
                for (action in buttons) {
                    ActionButton(action = action, accent = accent, onAction = onAction)
                }
                Box {
                    IconButton(onClick = { moreOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        for (section in snapshot.moreMenu.orEmpty()) {
                            for (item in section.items) {
                                DropdownMenuItem(
                                    text = { Text(item.title) },
                                    onClick = {
                                        moreOpen = false
                                        onOpenMoreItem(item)
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("退出登录") },
                            onClick = {
                                moreOpen = false
                                onSignOut()
                            },
                        )
                    }
                }
            }
            val tabs = snapshot.headerTabs
            if (tabs != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (item in tabs.items) {
                        val selected = item.id == tabs.selectedId
                        TextButton(onClick = { onSelectTab(tabs.ownerId, item.id) }) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) {
                                    accent ?: MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    action: ActionSnapshot,
    accent: Color?,
    onAction: (ActionSnapshot, String?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val icon = symbolIcon(action.icon)
    val menu = action.menu
    Box {
        IconButton(
            onClick = {
                if (menu.isNullOrEmpty()) onAction(action, null) else menuOpen = true
            },
            enabled = action.enabled,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = action.title,
                    tint = accent ?: LocalContentColor.current,
                )
            } else {
                Text(action.title, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!menu.isNullOrEmpty()) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (item in menu) {
                    DropdownMenuItem(
                        text = { Text(item.title) },
                        enabled = item.enabled,
                        onClick = {
                            menuOpen = false
                            onAction(action, item.actionId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellBottomBar(
    snapshot: NavigationSnapshot,
    accent: Color?,
    onSelect: (String) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (root in snapshot.roots) {
                val selected = root.id == snapshot.route.rootId
                val tint = if (selected) {
                    accent ?: MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(root.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    symbolIcon(root.icon)?.let { icon ->
                        Icon(imageVector = icon, contentDescription = root.title, tint = tint)
                    }
                    Text(
                        text = root.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在加载实例前端…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecoveryOverlay(message: String, onRetry: () -> Unit, onSignOut: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("页面无法继续控制", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("重新加载") }
            TextButton(onClick = onSignOut) { Text("切换实例") }
        }
    }
}

/**
 * chrome.prepare / host.layout 共用的布局声明。
 *
 * 首期把 WebView 放在系统栏之内的矩形区域里，
 * 因此 systemSafeArea / chromeOcclusion / contentInsets 都报 0，
 * 避免网页再叠加一层内边距（校准项见 android/PORTING.md）。
 */
private fun shellLayoutPayload(revision: Int, viewport: IntSize, density: Density): JsonObject {
    val width = with(density) { viewport.width.toDp().value.toDouble() }
    val height = with(density) { viewport.height.toDp().value.toDouble() }
    return buildJsonObject {
        put("layoutRevision", revision)
        put(
            "viewportPoints",
            buildJsonObject {
                put("width", width)
                put("height", height)
            },
        )
        put("systemSafeArea", zeroInsets())
        put("chromeOcclusion", zeroInsets())
        put("contentInsets", zeroInsets())
        put("keyboardOcclusion", 0.0)
        put("consumedByNativeFrame", false)
    }
}

private fun zeroInsets(): JsonObject = buildJsonObject {
    put("top", 0.0)
    put("bottom", 0.0)
    put("left", 0.0)
    put("right", 0.0)
}

/** 网页只下发 `#RRGGBB`；解析失败时回退到主题色。 */
private fun parseHexColor(value: String): Color? {
    if (value.length != 7 || !value.startsWith("#")) return null
    return try {
        Color(android.graphics.Color.parseColor(value))
    } catch (error: IllegalArgumentException) {
        null
    }
}