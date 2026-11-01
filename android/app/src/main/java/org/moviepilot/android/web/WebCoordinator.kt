package org.moviepilot.android.web

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.moviepilot.android.core.ActionSnapshot
import org.moviepilot.android.core.BRIDGE_MAJOR
import org.moviepilot.android.core.BRIDGE_PROTOCOL
import org.moviepilot.android.core.BridgeError
import org.moviepilot.android.core.BridgeException
import org.moviepilot.android.core.BridgeSource
import org.moviepilot.android.core.BridgeValidator
import org.moviepilot.android.core.ChromePhase
import org.moviepilot.android.core.ChromeSession
import org.moviepilot.android.core.MAXIMUM_MESSAGE_BYTES
import org.moviepilot.android.core.MoreMenuItem
import org.moviepilot.android.core.NavigationSnapshot
import org.moviepilot.android.core.WebOrigin
import org.moviepilot.android.core.booleanField
import org.moviepilot.android.core.intField
import org.moviepilot.android.core.intListField
import org.moviepilot.android.core.jsStringLiteral
import org.moviepilot.android.core.parseJsonObjectOrNull
import org.moviepilot.android.core.stringField
import org.moviepilot.android.core.stringListField
import org.moviepilot.android.data.SavedInstance
import org.moviepilot.android.net.AuthenticationResult
import java.util.UUID

/**
 * 单个实例的 WebView 容器与桥接协调器，对应 iOS 的 WebCoordinator。
 *
 * 职责边界：
 * - 只做“传输 + 会话状态机 + 命令分派”，不实现业务；
 * - 所有入站消息都要经过来源、主 frame、会话与修订校验；
 * - 出站消息只通过 CustomEvent 交给页面，不做字符串拼接。
 *
 * 首期能力声明刻意保守（只报 transportVersion），
 * 因此前端会继续使用自己的 HTML 面板 / 弹窗 / 卡片菜单，功能不会缺失，只是暂时不是原生的。
 */
class WebCoordinator(
    private val context: Context,
    private val instance: SavedInstance,
    private val authentication: AuthenticationResult,
    private val installation: String,
) {
    val webView: WebView = WebView(context)
    val session = ChromeSession()

    var onSnapshot: ((NavigationSnapshot) -> Unit)? = null
    var onRecovery: ((String) -> Unit)? = null
    var onFatal: ((String) -> Unit)? = null
    var onLoadingChanged: ((Boolean) -> Unit)? = null
    var onPrepareRequested: (() -> Unit)? = null
    var onAuthenticationRequired: (() -> Unit)? = null

    /** 由 Compose 侧提供当前几何；参数是本次要写入的 layoutRevision。 */
    var layoutProvider: ((Int) -> JsonObject)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val validator = BridgeValidator(instance.address.origin)
    private val pendingCommands = LinkedHashMap<String, Runnable>()
    private val commandCompletions = LinkedHashMap<String, (Boolean) -> Unit>()
    private var layoutRevision = 0
    private var lastLayout: JsonObject? = null
    private var awaitingPrepare = false
    private var lifecycle = "background"
    private var documentStartScript: String? = null
    private var started = false

    private val negotiationTimeout = Runnable { recover() }

    fun start() {
        if (started) return
        started = true
        configureWebView()
        if (!configureBridge()) {
            onRecovery?.invoke("当前系统 WebView 版本过低，无法建立原生桥接。请更新 Android System WebView 后重试。")
            return
        }
        injectDocumentStartScript()
        primeCookies()
        session.beginDocument()
        onLoadingChanged?.invoke(true)
        webView.loadUrl(instance.address.url)
    }

    /** 切换实例或销毁界面时解绑回调与监听器；WebView 本身由 Compose 侧销毁。 */
    fun stop() {
        onSnapshot = null
        onRecovery = null
        onFatal = null
        onLoadingChanged = null
        onPrepareRequested = null
        onAuthenticationRequired = null
        layoutProvider = null
        cancelCommands()
        cancelTimeout()
        handler.removeCallbacksAndMessages(null)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.removeWebMessageListener(webView, BridgeScript.CHROME_OBJECT)
                WebViewCompat.removeWebMessageListener(webView, BridgeScript.AUTH_OBJECT)
            } catch (error: Exception) {
                // 已经销毁的 WebView 不再需要清理监听器。
            }
        }
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        started = false
    }

    // ---------------------------------------------------------------- 容器配置

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url
            if (instance.address.contains(target)) return false
            openExternal(target)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // 顶层文档更换：会话、命令与待准备状态全部作废。
            session.beginDocument()
            awaitingPrepare = false
            lastLayout = null
            cancelCommands()
            cancelTimeout()
            onLoadingChanged?.invoke(true)
            // 不支持 document-start 脚本的旧 WebView 退化为这里补注入，官方支持范围见 PORTING.md。
            documentStartScript?.let { script -> if (!supportsDocumentStart()) view.evaluateJavascript(script, null) }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // 渲染进程已不可用，必须由上层重建整个 WebView。
            onFatal?.invoke("页面渲染进程已结束，正在重新加载。")
            return true
        }
    }

    private fun createWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            // TODO(阶段四)：麦克风/摄像头需要先跑运行时权限申请，这里是首期的默认拒绝。
            request.deny()
        }
    }

    /** 注册两个受来源限制的消息监听器；旧 WebView 不支持时返回 false。 */
    private fun configureBridge(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        val rules = setOf(instance.address.origin.rule)
        return try {
            WebViewCompat.addWebMessageListener(webView, BridgeScript.CHROME_OBJECT, rules, chromeListener)
            WebViewCompat.addWebMessageListener(webView, BridgeScript.AUTH_OBJECT, rules, authListener)
            true
        } catch (error: Exception) {
            false
        }
    }

    private fun supportsDocumentStart(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private fun injectDocumentStartScript() {
        val script = BridgeScript.build(
            installation = installation,
            session = authentication.session,
            origin = instance.address.origin.rule,
        )
        documentStartScript = script
        if (supportsDocumentStart()) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(instance.address.origin.rule))
        }
    }

    /** Cookie 只交给同一实例的 WebView，不复制到系统浏览器或其他实例。 */
    private fun primeCookies() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)
        for (cookie in authentication.cookies) {
            cookies.setCookie(instance.address.url, cookie)
        }
        cookies.flush()
    }

    private fun openExternal(target: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (error: Exception) {
            // 没有可处理该链接的应用时保持当前页面。
        }
    }

    // ---------------------------------------------------------------- 入站消息

    private val chromeListener = WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, _ ->
        receiveMessage(view, message.data, sourceOrigin, isMainFrame)
    }

    private val authListener = WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, _ ->
        val text = message.data
        if (text != null &&
            view === webView &&
            isMainFrame &&
            WebOrigin.from(sourceOrigin) == instance.address.origin
        ) {
            val body = parseJsonObjectOrNull(text)
            if (body != null && body.intField("version") == 1 &&
                body.stringField("type") == "required"
            ) {
                cancelCommands()
                cancelTimeout()
                onAuthenticationRequired?.invoke()
            }
        }
    }

    private fun receiveMessage(view: WebView, data: String?, sourceOrigin: Uri?, isMainFrame: Boolean) {
        val text = data ?: return
        val source = BridgeSource(
            isMainFrame = isMainFrame,
            isCurrentWebView = view === webView,
            origin = WebOrigin.from(sourceOrigin),
        )
        val envelope = try {
            validator.decode(text, source, session.sessionId)
        } catch (error: BridgeException) {
            // 拒绝无效、过期或不受信消息，不回显正文、地址和认证信息。
            return
        }
        if (!session.markProcessed(envelope.requestId)) return
        try {
            handle(envelope)
        } catch (error: BridgeException) {
            if (envelope.type == "navigation.state" && session.sessionId != null) recover()
        }
    }

    /** 消息采用封闭分派表，未知消息不会成为通用原生调用入口。 */
    private fun handle(envelope: org.moviepilot.android.core.BridgeEnvelope) {
        when (envelope.type) {
            "web.ready" -> handleReady(envelope.payload)
            "navigation.state" -> handleNavigationState(envelope)
            "navigation.departing" -> handleDeparting(envelope.payload)
            "chrome.accepted" -> handleAccepted(envelope.payload)
            "command.result" -> handleCommandResult(envelope.payload)
            "session.state" -> handleSessionState()
            "chrome.failed" -> recover()
            "presentation.state" -> Unit
            else -> Unit
        }
    }

    private fun handleReady(payload: JsonObject) {
        val majors = payload.intListField("majors") ?: throw BridgeException(BridgeError.InvalidMessage)
        val capabilities = payload.stringListField("capabilities")?.toSet()
            ?: throw BridgeException(BridgeError.InvalidMessage)
        val id = session.negotiate(majors, capabilities)
        send(
            "host.configure",
            buildJsonObject {
                put("sessionId", id)
                put("major", BRIDGE_MAJOR)
                put("instanceId", instance.id)
                put(
                    "capabilities",
                    buildJsonArray {
                        for (capability in ChromeSession.REQUIRED_CAPABILITIES.sorted()) {
                            add(capability)
                        }
                    },
                )
            },
        )
        armTimeout()
    }

    private fun handleNavigationState(envelope: org.moviepilot.android.core.BridgeEnvelope) {
        val routeRevision = envelope.routeRevision ?: throw BridgeException(BridgeError.InvalidMessage)
        val stateRevision = envelope.stateRevision ?: throw BridgeException(BridgeError.InvalidMessage)
        val snapshot = NavigationSnapshot.parse(envelope.payload)
            ?: throw BridgeException(BridgeError.InvalidMessage)
        session.receive(snapshot, routeRevision, stateRevision)
        when (session.phase) {
            ChromePhase.Negotiating -> {
                onSnapshot?.invoke(snapshot)
                awaitingPrepare = true
                onPrepareRequested?.invoke()
                armTimeout()
            }
            ChromePhase.NativeNavigation -> {
                cancelTimeout()
                onSnapshot?.invoke(snapshot)
                layoutChanged()
            }
            else -> Unit
        }
    }

    /**
     * 离页预览：iOS 会先截图再确认，首期不做截图，直接确认，
     * 让网页按原逻辑完成返回（视觉上退化为普通切换）。
     */
    private fun handleDeparting(payload: JsonObject) {
        if (session.phase != ChromePhase.NativeNavigation) return
        if (lifecycle != "active") return
        val id = payload.stringField("id") ?: return
        if (!BridgeValidator.isIdentifier(id)) return
        send("navigation.previewed", buildJsonObject { put("id", id) })
    }

    private fun handleAccepted(payload: JsonObject) {
        val revision = payload.intField("layoutRevision")
            ?: throw BridgeException(BridgeError.InvalidMessage)
        session.accept(revision)
        cancelTimeout()
        session.snapshot?.let { snapshot -> onSnapshot?.invoke(snapshot) }
        layoutChanged()
        onLoadingChanged?.invoke(false)
    }

    private fun handleCommandResult(payload: JsonObject) {
        val requestId = payload.stringField("requestId") ?: return
        val accepted = payload.booleanField("accepted") ?: return
        val pending = pendingCommands.remove(requestId) ?: return
        handler.removeCallbacks(pending)
        commandCompletions.remove(requestId)?.invoke(accepted)
    }

    private fun handleSessionState() {
        if (session.phase == ChromePhase.NativeNavigation) {
            val previous = session.sessionId
            val next = session.rotateIdentity()
            send(
                "host.rekey",
                buildJsonObject { put("sessionId", next) },
                sessionOverride = previous,
            )
            armTimeout()
        } else {
            recover()
        }
    }

    // ---------------------------------------------------------------- 出站消息

    private fun send(
        type: String,
        payload: JsonObject,
        requestId: String = UUID.randomUUID().toString(),
        sessionOverride: String? = null,
    ) {
        val id = sessionOverride ?: session.sessionId ?: return
        val envelope = buildJsonObject {
            put("protocol", BRIDGE_PROTOCOL)
            put("major", BRIDGE_MAJOR)
            put("type", type)
            put("requestId", requestId)
            put("sessionId", id)
            put("routeRevision", maxOf(0, session.routeRevision))
            put("stateRevision", maxOf(0, session.stateRevision))
            put("payload", payload)
        }
        val text = envelope.toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAXIMUM_MESSAGE_BYTES) return
        val script = "window.dispatchEvent(new CustomEvent('moviepilot:native-message'," +
            "{detail:JSON.parse(${jsStringLiteral(text)})}));"
        webView.evaluateJavascript(script, null)
    }

    /** 原生命令只执行一次；超时按既有路径释放导航，不重放可能已执行的业务动作。 */
    fun command(type: String, payload: JsonObject, completion: ((Boolean) -> Unit)? = null) {
        if (session.phase != ChromePhase.NativeNavigation ||
            pendingCommands.size >= 16 ||
            lifecycle != "active"
        ) {
            completion?.invoke(false)
            return
        }
        val requestId = UUID.randomUUID().toString()
        val timeout = Runnable {
            pendingCommands.remove(requestId)
            commandCompletions.remove(requestId)?.invoke(false)
            recover()
        }
        pendingCommands[requestId] = timeout
        if (completion != null) commandCompletions[requestId] = completion
        handler.postDelayed(timeout, COMMAND_TIMEOUT_MS)
        send(type, payload, requestId = requestId)
    }

    // ---------------------------------------------------------------- 布局握手

    /** 网页送来首份快照后，等待 Compose 完成一次布局，再发送 chrome.prepare。 */
    fun completePrepare() {
        if (!awaitingPrepare) return
        awaitingPrepare = false
        layoutRevision += 1
        try {
            session.prepare(layoutRevision)
        } catch (error: BridgeException) {
            recover()
            return
        }
        send("chrome.prepare", layoutProvider?.invoke(layoutRevision) ?: JsonObject(emptyMap()))
        armTimeout()
    }

    /** 可见几何变化后只发送改变过的布局，避免同一轮多次回调重复下发。 */
    fun layoutChanged() {
        if (session.phase != ChromePhase.NativeNavigation) return
        val provider = layoutProvider ?: return
        val candidate = provider(0)
        if (candidate == lastLayout) return
        lastLayout = candidate
        layoutRevision += 1
        send("host.layout", provider(layoutRevision))
    }

    // ---------------------------------------------------------------- 界面动作

    fun back() {
        if (session.phase != ChromePhase.NativeNavigation) return
        val snapshot = session.snapshot ?: return
        if (!snapshot.route.canGoBack) return
        command("navigation.back", buildJsonObject { put("source", "button") })
    }

    fun selectRoot(rootId: String) {
        if (session.phase != ChromePhase.NativeNavigation) return
        val snapshot = session.snapshot ?: return
        if (snapshot.route.rootId == rootId) return
        if (snapshot.roots.none { it.id == rootId }) return
        command("navigation.selectRoot", buildJsonObject { put("rootId", rootId) })
    }

    fun selectHeaderTab(ownerId: String, tabId: String) {
        command(
            "headerTab.select",
            buildJsonObject {
                put("tabId", tabId)
                put("ownerId", ownerId)
            },
        )
    }

    fun openMoreMenuItem(item: MoreMenuItem) {
        val snapshot = session.snapshot ?: return
        val known = snapshot.moreMenu
            ?.flatMap { section -> section.items }
            ?.any { entry -> entry.id == item.id && entry.path == item.path }
            ?: false
        if (!known) return
        command(
            "navigation.open",
            buildJsonObject {
                put("itemId", item.id)
                put("path", item.path)
            },
        )
    }

    /** 原生控件只能分派当前已确认动作，旧菜单对象在这里再次复核。 */
    fun invokeAction(action: ActionSnapshot, menuActionId: String? = null) {
        val sessionId = session.sessionId ?: return
        val actionId = menuActionId ?: action.actionId
        try {
            session.validateAction(sessionId, session.routeRevision, actionId, action.ownerId)
        } catch (error: BridgeException) {
            command("state.request", buildJsonObject { put("reason", "stale_action") })
            return
        }
        command(
            "action.invoke",
            buildJsonObject {
                put("actionId", actionId)
                put("ownerId", action.ownerId)
                put("layoutRevision", layoutRevision)
            },
        )
    }

    fun onLifecycleChange(state: String) {
        if (lifecycle == state) return
        lifecycle = state
        if (session.sessionId != null) {
            send("host.lifecycle", buildJsonObject { put("state", state) })
        }
        if (state == "active") {
            if (session.phase == ChromePhase.NativeNavigation) {
                command("state.request", buildJsonObject { put("reason", "foreground") })
                layoutChanged()
            }
        } else {
            cancelTimeout()
        }
    }

    /** 恢复页的重试只重新加载当前文档，不重放业务动作。 */
    fun retry() {
        cancelCommands()
        cancelTimeout()
        session.beginDocument()
        awaitingPrepare = false
        lastLayout = null
        onLoadingChanged?.invoke(true)
        webView.reload()
    }

    // ---------------------------------------------------------------- 内部工具

    private fun armTimeout() {
        cancelTimeout()
        if (lifecycle != "active") return
        handler.postDelayed(negotiationTimeout, NEGOTIATION_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        handler.removeCallbacks(negotiationTimeout)
    }

    private fun cancelCommands() {
        for (timeout in pendingCommands.values) handler.removeCallbacks(timeout)
        pendingCommands.clear()
        for (completion in commandCompletions.values) completion(false)
        commandCompletions.clear()
    }

    /** 失去文档响应后不声称已恢复网页，直接展示独立恢复界面。 */
    private fun recover() {
        cancelCommands()
        cancelTimeout()
        awaitingPrepare = false
        session.failed()
        onLoadingChanged?.invoke(false)
        onRecovery?.invoke("无法确认页面仍可控制，请重试或更换服务地址。")
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 3_000L
        const val NEGOTIATION_TIMEOUT_MS = 3_000L
    }
}