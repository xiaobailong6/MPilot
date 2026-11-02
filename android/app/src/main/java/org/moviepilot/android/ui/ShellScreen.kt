package org.moviepilot.android.ui

import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
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
 * 1. **玻璃底栏覆盖网页**：原生使用稳定薄材质，网页浮层使用 CSS 背景模糊；
 *    不再定时软件截图 WebView，避免干扰仪表盘图表。
 * 2. **排版不写死高度**：一律 `heightIn(min = …)`，标题/标签单行省略，
 *    页内标签横向可滚动，动作按屏宽收放 —— 系统大字体下不会再截断。
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
    var topHeight by remember { mutableStateOf(0) }
    var bottomHeight by remember { mutableStateOf(0) }

    DisposableEffect(coordinator) {
        coordinator.layoutProvider = { revision ->
            shellLayoutPayload(
                revision, viewport, density,
                if (snapshot?.chrome?.topVisible == true) topHeight else 0,
                if (snapshot?.let { it.chrome.bottomVisible && it.roots.isNotEmpty() } == true) bottomHeight else 0,
            )
        }
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

    val topVisible = snapshot?.chrome?.topVisible == true
    val bottomVisible = snapshot?.let { it.chrome.bottomVisible && it.roots.isNotEmpty() } == true
    LaunchedEffect(coordinator, viewport, topHeight, bottomHeight, topVisible, bottomVisible) {
        if (viewport == IntSize.Zero) return@LaunchedEffect
        withFrameNanos { }
        coordinator.layoutChanged()
    }

    val current = snapshot
    val chrome = current?.chrome
    val accent = chrome?.accentColor?.let(::parseHexColor)


    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // 网页先铺满，再叠放浮层；胶囊四周直接透出网页，不绘制占位底板。
            androidx.compose.runtime.key(coordinator.webView) {
                AndroidView(
                    factory = { coordinator.webView },
                    modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it },
                )
            }
            AnimatedVisibility(
                modifier = Modifier.align(Alignment.TopCenter),
                visible = topVisible,
                enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { -it },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it },
            ) {
                if (current != null) {
                    ShellTopBar(
                        snapshot = current,
                        accent = accent,
                        modifier = Modifier.onSizeChanged { topHeight = it.height },
                        onBack = { coordinator.back() },
                        onAction = { action, menuActionId -> coordinator.invokeAction(action, menuActionId) },
                        onSelectTab = { ownerId, tabId -> coordinator.selectHeaderTab(ownerId, tabId) },
                        onOpenMoreItem = { item -> coordinator.openMoreMenuItem(item) },
                        onSignOut = onSignOut,
                    )
                }
            }

            AnimatedVisibility(
                modifier = Modifier.align(Alignment.BottomCenter),
                visible = bottomVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                if (current != null) {
                    ShellBottomBar(
                        snapshot = current,
                        accent = accent,
                        modifier = Modifier.onSizeChanged { bottomHeight = it.height },
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

// ---------------------------------------------------------------------------- 液态玻璃材质

/** 只在胶囊轮廓内绘制磨砂、薄填充和边缘高光，外侧始终透明。 */
@Composable
private fun GlassPill(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 5.dp, vertical = 5.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()
                .glassSurface(RoundedCornerShape(50))
                .padding(contentPadding),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------- 顶栏

@Composable
private fun ShellTopBar(
    snapshot: NavigationSnapshot,
    accent: Color?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onAction: (ActionSnapshot, String?) -> Unit,
    onSelectTab: (String, String) -> Unit,
    onOpenMoreItem: (MoreMenuItem) -> Unit,
    onSignOut: () -> Unit,
) {
    var moreOpen by remember(snapshot.route.fullPath) { mutableStateOf(false) }
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

    // 参考 iOS：标题区不套白色大卡片，操作按钮使用各自的圆形薄材质。
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (snapshot.route.canGoBack) {
                BarIconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(22.dp))
                }
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
                    style = MaterialTheme.typography.titleMedium.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = MaterialTheme.colorScheme.background,
                            blurRadius = 12f,
                        ),
                    ),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            for (action in leading + inlineActions) {
                androidx.compose.runtime.key(snapshot.route.fullPath, action.ownerId, action.actionId) {
                    ActionButton(action = action, accent = accent, onAction = onAction)
                }
            }
            Box {
                BarIconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(22.dp))
                }
                GlassMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    for (item in overflowItems) {
                        androidx.compose.runtime.key(item.id) {
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
                    }
                    if (overflowItems.isNotEmpty() && overflowActions.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    for (action in overflowActions) {
                        androidx.compose.runtime.key(snapshot.route.fullPath, action.ownerId, action.actionId) {
                            val children = action.menu
                            if (children.isNullOrEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(action.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    enabled = action.enabled,
                                    onClick = { moreOpen = false; onAction(action, null) },
                                )
                            } else {
                                // 在同一菜单中展开子项，避免嵌套 Popup 造成焦点和选中残留。
                                Text(
                                    action.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                for (item in children) {
                                    androidx.compose.runtime.key(item.actionId) {
                                        DropdownMenuItem(
                                            text = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            enabled = action.enabled && item.enabled,
                                            trailingIcon = if (item.selected == true) {
                                                { Text("✓", color = MaterialTheme.colorScheme.primary) }
                                            } else null,
                                            onClick = { moreOpen = false; onAction(action, item.actionId) },
                                        )
                                    }
                                }
                            }
                        }
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
                    .padding(bottom = 2.dp),
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
                        modifier = Modifier.heightIn(min = 34.dp).glassSurface(RoundedCornerShape(50)),
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

/** 顶栏图标按钮：自绘点击区，带按下缩放与圆形水波，不依赖各版本 IconButton 的参数差异。 */
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
            .glassSurface(CircleShape)
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
    var menuOpen by remember(action.ownerId, action.actionId) { mutableStateOf(false) }
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
            GlassMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (item in menu) {
                    androidx.compose.runtime.key(item.actionId) {
                    DropdownMenuItem(
                        text = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        enabled = action.enabled && item.enabled,
                        trailingIcon = if (item.selected == true) {
                            { Text("✓", color = MaterialTheme.colorScheme.primary) }
                        } else null,
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
}

// ---------------------------------------------------------------------------- 底栏

/**
 * 悬浮底栏：玻璃胶囊 + 每项独立的圆角高亮片。
 *
 * 选中项通过不透明度渐显一层 accent 底片（200ms），按下时整项缩到 0.92，
 * 未选中项只保留图标与文字，不画底片。
 */
@Composable
private fun ShellBottomBar(
    snapshot: NavigationSnapshot,
    accent: Color?,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    GlassPill(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (root in snapshot.roots) {
                androidx.compose.runtime.key(root.id) {
                val selected = root.id == snapshot.route.rootId
                val interaction = remember(root.id) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (pressed) 0.92f else 1f,
                    animationSpec = tween(120),
                )
                val highlight by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = tween(200),
                )
                val tint by animateColorAsState(
                    targetValue = if (selected) {
                        accent ?: MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(180),
                )
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(50))
                        .background(
                            (if (dark) Color.White else Color.Black)
                                .copy(alpha = (if (dark) 0.12f else 0.055f) * highlight),
                        )
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            onClick = { onSelect(root.id) },
                        )
                        .heightIn(min = 52.dp)
                        .padding(vertical = 6.dp),
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
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 76.dp),
                    )
                }
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
            GlassButton(onClick = onRetry) { Text("重新加载") }
            GlassButton(onClick = onSignOut) { Text("切换实例") }
        }
    }
}

// ---------------------------------------------------------------------------- 协议辅助

/**
 * 顶部"更多"菜单与底部入口去重：**底栏优先**。
 *
 * 网页给的更多菜单是完整的导航目录，其中常包含底栏已经常驻的主入口。
 * 这里按 path（忽略结尾斜杠与大小写）、id、以及"path 为 / 且标题一致"三种情况剔除重复，
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
 * 网页使用完整视口；浮层遮挡作为 contentInsets / chromeOcclusion 交给网页，
 * 让首尾内容仍可完整滚动到可点击区域。系统安全区已由外层原生布局消费。
 */
private fun shellLayoutPayload(
    revision: Int,
    viewport: IntSize,
    density: Density,
    topHeight: Int,
    bottomHeight: Int,
): JsonObject {
    val width = with(density) { viewport.width.toDp().value.toDouble() }
    val height = with(density) { viewport.height.toDp().value.toDouble() }
    val chromeInsets = buildJsonObject {
        put("top", with(density) { topHeight.toDp().value.toDouble() })
        put("bottom", with(density) { bottomHeight.toDp().value.toDouble() })
        put("left", 0.0)
        put("right", 0.0)
    }
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
        put("chromeOcclusion", chromeInsets)
        put("contentInsets", chromeInsets)
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