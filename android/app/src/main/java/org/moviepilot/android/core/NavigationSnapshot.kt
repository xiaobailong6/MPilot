package org.moviepilot.android.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 网页已完成路由守卫后确认的业务位置；原生不自行推测浏览器历史。
 * 本文件与 iOS Sources/MoviePilotCore/NavigationSnapshot.swift 字段一一对应。
 */
data class RouteSnapshot(
    val fullPath: String,
    val rootId: String?,
    val title: String,
    val canGoBack: Boolean,
    val backTarget: String?,
)

/** 经过前端权限过滤的原生底部入口。 */
data class RootSnapshot(
    val id: String,
    val path: String,
    val title: String,
    val icon: String,
)

/** 页内导航项；标识由当前网页注册宿主提供。 */
data class HeaderTabItem(val id: String, val title: String)

/** 当前页内标签注册与选中值；owner 改变后旧命令必须作废。 */
data class HeaderTabsSnapshot(
    val ownerId: String,
    val selectedId: String,
    val items: List<HeaderTabItem>,
)

/** 导航动作的显示位置；业务实现仍保存在网页回调表中。 */
enum class ActionPosition(val wire: String) {
    Leading("leading"),
    Trailing("trailing"),
    Accessory("accessory"),
    ;

    companion object {
        fun from(value: String?): ActionPosition? = values().firstOrNull { it.wire == value }
    }
}

/** 动作所属范围只影响菜单分组，不改变网页回调与权限校验。 */
enum class ActionScope(val wire: String) {
    Global("global"),
    Page("page"),
    ;

    companion object {
        fun from(value: String?): ActionScope? = values().firstOrNull { it.wire == value }
    }
}

/** 纯命令菜单项；业务回调仍由当前网页处理。 */
data class ActionMenuItem(
    val actionId: String,
    val title: String,
    val icon: String,
    val enabled: Boolean,
    val selected: Boolean?,
    val group: String?,
)

/**
 * 不含回调与认证凭据的导航动作及面板展示声明。
 * 面板内容首期不解析完整结构，只保留“是否存在面板”与标题，供后续迭代使用。
 */
data class ActionSnapshot(
    val actionId: String,
    val ownerId: String,
    val title: String,
    val icon: String,
    val position: ActionPosition,
    val scope: ActionScope?,
    val enabled: Boolean,
    val loading: Boolean,
    val dataAttr: String?,
    val menu: List<ActionMenuItem>?,
    val hasPanel: Boolean,
    val panelTitle: String?,
) {
    /** 旧前端沿用已发布的全局动作标识；新前端明确声明范围，未知动作归入当前页面。 */
    val isGlobal: Boolean
        get() {
            scope?.let { return it == ActionScope.Global }
            val parts = actionId.split(":")
            return parts.size == 3 && parts[0].startsWith("r") && parts[1] == "global"
        }
}

/** 原生更多菜单的一项；只包含导航声明，不能携带脚本或外部 URL。 */
data class MoreMenuItem(
    val id: String,
    val path: String,
    val title: String,
    val icon: String,
    val subtitle: String?,
)

data class MoreMenuSection(
    val id: String,
    val title: String,
    val items: List<MoreMenuItem>,
)

/** 容器界面的主题偏好。 */
enum class ChromeTheme(val wire: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun from(value: String?): ChromeTheme? = values().firstOrNull { it.wire == value }
    }
}

/** 业务 HTML 与原生框架菜单使用不同呈现，但共享同一个 Router 会话。 */
enum class ChromeContent(val wire: String) {
    Web("web"),
    Menu("menu"),
    ;

    companion object {
        fun from(value: String?): ChromeContent? = values().firstOrNull { it.wire == value }
    }
}

/** 网页明确给出的导航显示状态，不直接透传 Vue route meta。 */
data class ChromeSnapshot(
    val topVisible: Boolean,
    val bottomVisible: Boolean,
    val theme: ChromeTheme,
    val content: ChromeContent?,
    val accentColor: String?,
    val backgroundColor: String?,
    val surfaceColor: String?,
)

/**
 * 弹层状态。首期只保留回退判定需要的字段：
 * Sheet / 确认框的原生接管属于后续阶段，见 android/PORTING.md。
 */
data class PresentationSnapshot(
    val overlayOpen: Boolean,
    val fullscreen: Boolean?,
    val requestedPanel: String?,
    val sheetId: String?,
    val sheetTitle: String?,
    val sheetCanDismiss: Boolean,
    val confirmationId: String?,
    val confirmationTitle: String?,
    val confirmationMessage: String?,
    val confirmationConfirmText: String?,
    val confirmationCancelText: String?,
    val confirmationDestructive: Boolean,
)

/** 原生显示所需的完整原子快照，受大小、数量及修订约束。 */
data class NavigationSnapshot(
    val route: RouteSnapshot,
    val roots: List<RootSnapshot>,
    val headerTabs: HeaderTabsSnapshot?,
    val actions: List<ActionSnapshot>,
    val chrome: ChromeSnapshot,
    val moreMenu: List<MoreMenuSection>?,
    val presentation: PresentationSnapshot?,
) {
    /** 验证所有路径、数量与标识唯一性；不把不完整快照提交到原生控件。 */
    fun validate() {
        require(isRoute(route.fullPath)) { "route" }
        require(isLabel(route.title)) { "title" }
        if (route.backTarget != null) require(isRoute(route.backTarget)) { "backTarget" }
        require(!route.canGoBack || route.backTarget != null) { "backTarget" }
        for (color in listOf(chrome.accentColor, chrome.backgroundColor, chrome.surfaceColor)) {
            if (color != null) require(isColor(color)) { "color" }
        }
        val requestedPanel = presentation?.requestedPanel
        if (requestedPanel != null) require(BridgeValidator.isIdentifier(requestedPanel)) { "panel" }
        require(roots.size <= 4) { "roots" }
        require(actions.size <= 16) { "actions" }
        require(roots.map { it.id }.toSet().size == roots.size) { "roots" }
        require(actions.map { it.actionId }.toSet().size == actions.size) { "actions" }
        route.rootId?.let { id -> require(roots.any { it.id == id }) { "rootId" } }
        for (root in roots) {
            require(BridgeValidator.isIdentifier(root.id)) { "root" }
            require(isRoute(root.path)) { "root" }
            require(isLabel(root.title)) { "root" }
            require(isLabel(root.icon)) { "root" }
        }
        validateHeaderTabs()
        validateMoreMenu()
        validateActions()
        validatePresentation()
    }

    private fun validateHeaderTabs() {
        val tabs = headerTabs ?: return
        require(BridgeValidator.isIdentifier(tabs.ownerId)) { "tabs" }
        require(tabs.items.isNotEmpty() && tabs.items.size <= 32) { "tabs" }
        require(tabs.items.map { it.id }.toSet().size == tabs.items.size) { "tabs" }
        require(tabs.items.any { it.id == tabs.selectedId }) { "tabs" }
        for (item in tabs.items) {
            require(BridgeValidator.isIdentifier(item.id) && isLabel(item.title)) { "tabs" }
        }
    }

    private fun validateMoreMenu() {
        val sections = moreMenu ?: emptyList()
        val entries = sections.flatMap { it.items }
        require(sections.size <= 32 && entries.size <= 128) { "moreMenu" }
        require(sections.map { it.id }.toSet().size == sections.size) { "moreMenu" }
        require(entries.map { it.id }.toSet().size == entries.size) { "moreMenu" }
        require(chrome.content != ChromeContent.Menu || moreMenu != null) { "moreMenu" }
        for (section in sections) {
            require(BridgeValidator.isIdentifier(section.id) && isLabel(section.title)) { "moreMenu" }
        }
        for (entry in entries) {
            require(BridgeValidator.isIdentifier(entry.id)) { "moreMenu" }
            require(isRoute(entry.path) && isLabel(entry.title) && isLabel(entry.icon)) { "moreMenu" }
            entry.subtitle?.let { require(isLabel(it)) { "moreMenu" } }
        }
    }

    private fun validateActions() {
        val actionIds = HashSet<String>(actions.map { it.actionId })
        for (action in actions) {
            require(BridgeValidator.isIdentifier(action.actionId)) { "action" }
            require(BridgeValidator.isIdentifier(action.ownerId)) { "action" }
            require(isLabel(action.title) && isLabel(action.icon)) { "action" }
            action.dataAttr?.let { require(BridgeValidator.isIdentifier(it)) { "action" } }
            require((action.menu?.size ?: 0) <= 32) { "action" }
            for (item in action.menu ?: emptyList()) {
                require(BridgeValidator.isIdentifier(item.actionId)) { "action" }
                require(isLabel(item.title) && isLabel(item.icon)) { "action" }
                require(actionIds.add(item.actionId)) { "action" }
            }
            action.panelTitle?.let { require(isLabel(it)) { "panel" } }
        }
    }

    private fun validatePresentation() {
        val presentation = presentation ?: return
        val sheetId = presentation.sheetId
        if (sheetId != null) {
            require(presentation.overlayOpen) { "sheet" }
            require(BridgeValidator.isIdentifier(sheetId)) { "sheet" }
            presentation.sheetTitle?.let { require(isLabel(it)) { "sheet" } }
        }
        val confirmationId = presentation.confirmationId
        if (confirmationId != null) {
            require(presentation.overlayOpen) { "confirmation" }
            require(BridgeValidator.isIdentifier(confirmationId)) { "confirmation" }
            presentation.confirmationTitle?.let { require(isLabel(it)) { "confirmation" } }
            presentation.confirmationConfirmText?.let { require(isLabel(it)) { "confirmation" } }
            presentation.confirmationCancelText?.let { require(isLabel(it)) { "confirmation" } }
            val message = presentation.confirmationMessage
            if (message != null) {
                require(message.toByteArray(Charsets.UTF_8).size <= 8192) { "confirmation" }
                require(message.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
                    "confirmation"
                }
            }
        }
    }

    companion object {
        private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

        /** 只接受 Vue 内部路径，禁止绝对来源、反斜杠与控制字符。 */
        fun isRoute(value: String): Boolean =
            value.startsWith("/") &&
                !value.startsWith("//") &&
                !value.contains('\\') &&
                value.toByteArray(Charsets.UTF_8).size <= 4096 &&
                value.none { it.isISOControl() }

        /** 展示文本保持简短，不接受控制字符。 */
        fun isLabel(value: String): Boolean =
            value.isNotEmpty() &&
                value.toByteArray(Charsets.UTF_8).size <= 512 &&
                value.none { it.isISOControl() }

        /** 颜色仅允许六位十六进制值，不能透传 CSS 指令。 */
        private fun isColor(value: String): Boolean = HEX_COLOR.matches(value)

        /** 从网页消息解析完整快照；任一必需字段缺失或类型不符则整条丢弃。 */
        fun parse(root: JsonObject): NavigationSnapshot? {
            val route = root.objectField("route")?.let(::parseRoute) ?: return null
            val roots = strictMap(root.arrayField("roots"), ::parseRoot) ?: return null
            val headerTabs = root.objectField("headerTabs")?.let(::parseHeaderTabs)
            val actions = strictMap(root.arrayField("actions"), ::parseAction) ?: return null
            val chrome = root.objectField("chrome")?.let(::parseChrome) ?: return null
            val moreMenu = root.arrayField("moreMenu")?.let { array ->
                strictMap(array, ::parseMoreMenuSection)
            }
            val presentation = root.objectField("presentation")?.let(::parsePresentation)
            return NavigationSnapshot(
                route = route,
                roots = roots,
                headerTabs = headerTabs,
                actions = actions,
                chrome = chrome,
                moreMenu = moreMenu,
                presentation = presentation,
            )
        }

        private fun parseRoute(obj: JsonObject): RouteSnapshot? {
            val fullPath = obj.stringField("fullPath") ?: return null
            val title = obj.stringField("title") ?: return null
            val canGoBack = obj.booleanField("canGoBack") ?: return null
            return RouteSnapshot(
                fullPath = fullPath,
                rootId = obj.stringField("rootId"),
                title = title,
                canGoBack = canGoBack,
                backTarget = obj.stringField("backTarget"),
            )
        }

        private fun parseRoot(obj: JsonObject): RootSnapshot? {
            val id = obj.stringField("id") ?: return null
            val path = obj.stringField("path") ?: return null
            val title = obj.stringField("title") ?: return null
            val icon = obj.stringField("icon") ?: return null
            return RootSnapshot(id, path, title, icon)
        }

        private fun parseHeaderTabs(obj: JsonObject): HeaderTabsSnapshot? {
            val ownerId = obj.stringField("ownerId") ?: return null
            val selectedId = obj.stringField("selectedId") ?: return null
            val items = strictMap(obj.arrayField("items")) { item ->
                val id = item.stringField("id") ?: return@strictMap null
                val title = item.stringField("title") ?: return@strictMap null
                HeaderTabItem(id, title)
            } ?: return null
            return HeaderTabsSnapshot(ownerId, selectedId, items)
        }

        private fun parseAction(obj: JsonObject): ActionSnapshot? {
            val actionId = obj.stringField("actionId") ?: return null
            val ownerId = obj.stringField("ownerId") ?: return null
            val title = obj.stringField("title") ?: return null
            val icon = obj.stringField("icon") ?: return null
            val position = ActionPosition.from(obj.stringField("position")) ?: return null
            val enabled = obj.booleanField("enabled") ?: return null
            val panel = obj.objectField("panel")
            val menu = obj.arrayField("menu")?.let { array ->
                strictMap(array) { item ->
                    val menuActionId = item.stringField("actionId") ?: return@strictMap null
                    val menuTitle = item.stringField("title") ?: return@strictMap null
                    val menuIcon = item.stringField("icon") ?: return@strictMap null
                    val menuEnabled = item.booleanField("enabled") ?: return@strictMap null
                    ActionMenuItem(
                        actionId = menuActionId,
                        title = menuTitle,
                        icon = menuIcon,
                        enabled = menuEnabled,
                        selected = item.booleanField("selected"),
                        group = item.stringField("group"),
                    )
                }
            }
            return ActionSnapshot(
                actionId = actionId,
                ownerId = ownerId,
                title = title,
                icon = icon,
                position = position,
                scope = ActionScope.from(obj.stringField("scope")),
                enabled = enabled,
                loading = obj.booleanField("loading") ?: false,
                dataAttr = obj.stringField("dataAttr"),
                menu = menu,
                hasPanel = panel != null,
                panelTitle = panel?.stringField("title"),
            )
        }

        private fun parseChrome(obj: JsonObject): ChromeSnapshot? {
            val topVisible = obj.booleanField("topVisible") ?: return null
            val bottomVisible = obj.booleanField("bottomVisible") ?: return null
            val theme = ChromeTheme.from(obj.stringField("theme")) ?: return null
            return ChromeSnapshot(
                topVisible = topVisible,
                bottomVisible = bottomVisible,
                theme = theme,
                content = ChromeContent.from(obj.stringField("content")),
                accentColor = obj.stringField("accentColor"),
                backgroundColor = obj.stringField("backgroundColor"),
                surfaceColor = obj.stringField("surfaceColor"),
            )
        }

        private fun parseMoreMenuSection(obj: JsonObject): MoreMenuSection? {
            val id = obj.stringField("id") ?: return null
            val title = obj.stringField("title") ?: return null
            val items = strictMap(obj.arrayField("items")) { item ->
                val itemId = item.stringField("id") ?: return@strictMap null
                val path = item.stringField("path") ?: return@strictMap null
                val itemTitle = item.stringField("title") ?: return@strictMap null
                val icon = item.stringField("icon") ?: return@strictMap null
                MoreMenuItem(itemId, path, itemTitle, icon, item.stringField("subtitle"))
            } ?: return null
            return MoreMenuSection(id, title, items)
        }

        private fun parsePresentation(obj: JsonObject): PresentationSnapshot {
            val sheet = obj.objectField("sheet")
            val confirmation = obj.objectField("confirmation")
            return PresentationSnapshot(
                overlayOpen = obj.booleanField("overlayOpen") ?: false,
                fullscreen = obj.booleanField("fullscreen"),
                requestedPanel = obj.stringField("requestedPanel"),
                sheetId = sheet?.stringField("id"),
                sheetTitle = sheet?.stringField("title"),
                sheetCanDismiss = sheet?.booleanField("canDismiss") ?: false,
                confirmationId = confirmation?.stringField("id"),
                confirmationTitle = confirmation?.stringField("title"),
                confirmationMessage = confirmation?.stringField("message"),
                confirmationConfirmText = confirmation?.stringField("confirmText"),
                confirmationCancelText = confirmation?.stringField("cancelText"),
                confirmationDestructive = confirmation?.booleanField("destructive") ?: false,
            )
        }

        /**
         * 严格映射数组：任一元素不是对象或解析失败，整条快照作废。
         * 与 iOS Codable 的“整体解码失败”行为对齐，避免半份快照进入原生控件。
         */
        private inline fun <T> strictMap(array: JsonArray?, parse: (JsonObject) -> T?): List<T>? {
            if (array == null) return null
            val result = ArrayList<T>(array.size)
            for (element in array) {
                val obj = element as? JsonObject ?: return null
                val value = parse(obj) ?: return null
                result.add(value)
            }
            return result
        }
    }
}