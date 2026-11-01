package org.moviepilot.android.ui

import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
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
 * 三条与 iOS 不同的落地约定（Android 侧没有系统级等价物）：
 * 1. **玻璃材质用近似实现**：Android 没有 backdrop blur（系统不提供"模糊背后内容"的公开 API），
 *    这里改用"浮动圆角卡片 + 半透明渐变 surface + 淡 accent 描边 + 投影"，
 *    在深浅色下都能读出景深，且不会因为二次绘制 WebView 造成闪烁。
 * 2. **排版不写死高度**：一律 `heightIn(min = …)`，标题/标签单行省略，
 *    页内标签横向可滚动，动作超过 3 个自动收进溢出菜单 —— 系统大字体下不会再截断。
 * 3. **WebView 始终固定在同一层级**：它一旦跨 Window 或换父容器就会白屏/重载，
 *    所以所有浮层都画在同一个 Box 里，WebView 本体只改修饰符不重建。
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

    // 路由切换时盖一层底色再淡出，给页面切换一个可感知的过渡；
    // 只在"已有上一页且路由确实变了"时触发，首屏不闪。
    val routeKey = current?.route?.fullPath ?: ""
    val pageScrim = remember { Animatable(0f) }
    var lastRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(routeKey) {
        val previous = lastRoute
        lastRoute = routeKey
        if (previous == null || previous == routeKey || routeKey.isEmpty()) return@LaunchedEffect
        pageScrim.snapTo(0.45f)
        pageScrim.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            AnimatedVisibility(
                visible = current != null && chrome != null && chrome.topVisible,
                enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { -it },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it },
            ) {
                if (current != null) {
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
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { coordinator.webView },
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size -> viewport = size },
                )
                // 过渡遮罩：只覆盖在 WebView 之上，不触碰 WebView 自身（避免重绘问题）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = pageScrim.value }
                        .background(MaterialTheme.colorScheme.background),
                )
            }

            AnimatedVisibility(
                visible = current != null && chrome != null && chrome.bottomVisible &&
                    current.roots.isNotEmpty(),
                enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                if (current != null) {
                    ShellBottomBar(
                        snapshot = current,
                        accent = accent,
                        onSelect = { rootId -> coordinator.selectRoot(rootId) },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = loading && snapshot == null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
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

// ---------------------------------------------------------------------------- 玻璃材质

/**
 * 顶栏/底栏共用的"玻璃"容器。
 *
 * Android 没有 iOS 26 那种系统级液态玻璃（无法模糊背后的 WebView 内容），
 * 这里用四层叠加做出可接受的近似：投影拉出景深、半透明渐变做材质、
 * accent 细描边模拟高光边缘、圆角浮动卡片避免与页面底色糊在一起。
 */
@Composable
private fun GlassBar(
    accent: Color?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    val surface = MaterialTheme.colorScheme.surface
    val tint = accent ?: MaterialTheme.colorScheme.primary
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 12.dp, shape = shape, clip = false)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(surface.copy(alpha = 0.94f), surface.copy(alpha = 0.76f)),
                    ),
                    shape = shape,
                )
                .border(width = 1.dp, color = tint.copy(alpha = 0.22f), shape = shape)
                .padding(horizontal = 6.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------- 顶栏

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
    val leading = remember(snapshot.actions) {
        snapshot.actions.filter { it.position == ActionPosition.Leading }
    }
    val trailing = remember(snapshot.actions) {
        snapshot.actions.filter {
            it.position == ActionPosition.Trailing || it.position == ActionPosition.Accessory
        }
    }
    // 顶栏宽度有限：按屏幕宽度决定内联动作数量，其余收进溢出菜单，避免标题被挤成省略号。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val inlineLimit = when {
        screenWidthDp >= 480 -> 4
        screenWidthDp >= 400 -> 3
        else -> 2
    }
    val inlineActions = remember(trailing, inlineLimit) { trailing.take(inlineLimit) }
    val overflowActions = remember(trailing, inlineLimit) { trailing.drop(inlineLimit) }
    val overflowItems = remember(snapshot.moreMenu, snapshot.roots) {
        dedupeMoreMenu(snapshot.moreMenu.orEmpty(), snapshot)
    }

    GlassBar(accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (snapshot.route.canGoBack) {
                BarIconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(22.dp))
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
            for (action in leading) {
                ActionButton(action = action, accent = accent, onAction = onAction)
            }
            AnimatedContent(
                targetState = snapshot.route.title,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 })
                        .togetherWith(fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 4 })
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            ) { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            for (action in inlineActions) {
                ActionButton(action = action, accent = accent, onAction = onAction)
            }
            Box {
                BarIconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    for (item in overflowItems) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    item.subtitle?.let { subtitle ->
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                moreOpen = false
                                onOpenMoreItem(item)
                            },
                        )
                    }
                    if (overflowItems.isNotEmpty() && overflowActions.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    for (action in overflowActions) {
                        DropdownMenuItem(
                            text = { Text(action.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            enabled = action.enabled,
                            onClick = {
                                moreOpen = false
                                onAction(action, null)
                            },
                        )
                    }
                    if (overflowItems.isNotEmpty() || overflowActions.isNotEmpty()) {
                        HorizontalDivider()
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

        // 页内标签：横向可滚动，条目多时不会互相挤压。
        val tabs = snapshot.headerTabs
        if (tabs != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (item in tabs.items) {
                    val selected = item.id == tabs.selectedId
                    val color by animateColorAsState(
                        targetValue = if (selected) {
                            accent ?: MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(180),
                    )
                    TextButton(
                        onClick = { onSelectTab(tabs.ownerId, item.id) },
                        modifier = Modifier.heightIn(min = 36.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 顶栏图标按钮：自绘点击区，带按下缩放，不依赖各版本 IconButton 的参数差异。 */
@Composable
private fun BarIconButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = tween(120),
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
        BarIconButton(
            enabled = action.enabled,
            onClick = {
                if (menu.isNullOrEmpty()) onAction(action, null) else menuOpen = true
            },
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = action.title,
                    tint = accent ?: LocalContentColor.current,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (!menu.isNullOrEmpty()) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (item in menu) {
                    DropdownMenuItem(
                        text = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

// ---------------------------------------------------------------------------- 底栏

@Composable
private fun ShellBottomBar(
    snapshot: NavigationSnapshot,
    accent: Color?,
    onSelect: (String) -> Unit,
) {
    GlassBar(accent = accent) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (root in snapshot.roots) {
                val selected = root.id == snapshot.route.rootId
                val interaction = remember(root.id) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (pressed) 0.92f else 1f,
                    animationSpec = tween(120),
                )
                val tint by animateColorAsState(
                    targetValue = if (selected) {
                        accent ?: MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(180),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            onClick = { onSelect(root.id) },
                        )
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    symbolIcon(root.icon)?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = root.title,
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        text = root.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 80.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 覆盖层

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

// ---------------------------------------------------------------------------- 协议辅助

/**
 * 顶部"更多"菜单与底部入口去重：**底栏优先**。
 *
 * 网页给的更多菜单是完整的导航目录，其中常包含底栏已经常驻的主入口。
 * 这里按 path（忽略结尾斜杠）、id、以及"标题相同且 path 一致"三种情况剔除重复，
 * 只影响原生显示，不改变网页快照本身；点击仍走原 `navigation.open` 校验。
 */
private fun dedupeMoreMenu(
    sections: List<org.moviepilot.android.core.MoreMenuSection>,
    snapshot: NavigationSnapshot,
): List<MoreMenuItem> {
    if (sections.isEmpty()) return emptyList()
    val rootIds = snapshot.roots.map { it.id }.toSet()
    val rootPaths = snapshot.roots.map { normalizePath(it.path) }.toSet()
    val rootTitles = snapshot.roots.map { it.title }.toSet()
    val result = ArrayList<MoreMenuItem>()
    for (section in sections) {
        for (item in section.items) {
            val path = normalizePath(item.path)
            if (item.id in rootIds) continue
            if (path in rootPaths) continue
            if (path.isEmpty() && item.title in rootTitles) continue
            if (result.any { it.id == item.id }) continue
            result.add(item)
        }
    }
    return result
}

private fun normalizePath(path: String): String = path.trimEnd('/').lowercase()

/**
 * chrome.prepare / host.layout 共用的布局声明。
 *
 * 顶栏/底栏是浮动卡片，但 WebView 仍排在两者之间（不重叠），
 * 所以 systemSafeArea / chromeOcclusion / contentInsets 继续报 0，
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