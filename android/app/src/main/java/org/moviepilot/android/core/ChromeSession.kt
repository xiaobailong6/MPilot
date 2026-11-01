package org.moviepilot.android.core

import java.util.UUID

/** 接管事务状态；恢复状态必须由容器展示可操作的恢复界面。 */
enum class ChromePhase {
    WebNavigation,
    Negotiating,
    Prepared,
    NativeNavigation,
    Releasing,
    Recovery,
}

/**
 * 单个顶层文档的导航会话，对应 iOS ChromeSession。
 * 所有写入都由主线程上的协调器串行驱动，因此这里不再加锁。
 */
class ChromeSession {
    var phase: ChromePhase = ChromePhase.WebNavigation
        private set
    var sessionId: String? = null
        private set
    var snapshot: NavigationSnapshot? = null
        private set
    var routeRevision: Int = -1
        private set
    var stateRevision: Int = -1
        private set
    var layoutRevision: Int = -1
        private set
    var takeoverAllowed: Boolean = true
        private set

    private val completedRequests = LinkedHashSet<String>()
    private val requestOrder = ArrayList<String>()

    /** 顶层文档更换时清理所有显示与命令；恢复重载可以禁用本次原生接管。 */
    fun beginDocument(allowTakeover: Boolean = true) {
        phase = ChromePhase.WebNavigation
        sessionId = null
        snapshot = null
        routeRevision = -1
        stateRevision = -1
        layoutRevision = -1
        takeoverAllowed = allowTakeover
        completedRequests.clear()
        requestOrder.clear()
    }

    /** 双方能力匹配后建立新关联会话，旧文档消息不能沿用此会话。 */
    fun negotiate(majors: List<Int>, capabilities: Set<String>): String {
        if (phase != ChromePhase.WebNavigation || !takeoverAllowed) {
            throw BridgeException(BridgeError.Busy)
        }
        if (!majors.contains(BRIDGE_MAJOR)) {
            throw BridgeException(BridgeError.UnsupportedVersion)
        }
        if (!capabilities.containsAll(REQUIRED_CAPABILITIES)) {
            throw BridgeException(BridgeError.UnsupportedCapability)
        }
        val id = UUID.randomUUID().toString()
        sessionId = id
        phase = ChromePhase.Negotiating
        return id
    }

    /** 只接受递增的完整快照；路由代次不能倒退，业务返回也产生新的代次。 */
    fun receive(value: NavigationSnapshot, routeRevision: Int, stateRevision: Int) {
        if (phase != ChromePhase.Negotiating &&
            phase != ChromePhase.Prepared &&
            phase != ChromePhase.NativeNavigation
        ) {
            throw BridgeException(BridgeError.Busy)
        }
        if (routeRevision < this.routeRevision || stateRevision <= this.stateRevision ||
            routeRevision < 0 || stateRevision < 0
        ) {
            throw BridgeException(BridgeError.StaleRoute)
        }
        value.validate()
        snapshot = value
        this.routeRevision = routeRevision
        this.stateRevision = stateRevision
    }

    /** 原生完成可用布局量测后进入准备阶段，等待网页恢复布局的确认。 */
    fun prepare(layoutRevision: Int) {
        if (phase != ChromePhase.Negotiating || snapshot == null || layoutRevision < 0) {
            throw BridgeException(BridgeError.Busy)
        }
        this.layoutRevision = layoutRevision
        phase = ChromePhase.Prepared
    }

    /** 网页必须确认同一份布局后原生才能显示业务导航。 */
    fun accept(layoutRevision: Int) {
        if (phase != ChromePhase.Prepared || layoutRevision != this.layoutRevision) {
            throw BridgeException(BridgeError.InvalidMessage)
        }
        phase = ChromePhase.NativeNavigation
    }

    /** 先请求网页恢复导航；确认到达前仍保留当前原生出口。 */
    fun requestRelease() {
        if (phase == ChromePhase.NativeNavigation ||
            phase == ChromePhase.Prepared ||
            phase == ChromePhase.Negotiating
        ) {
            phase = ChromePhase.Releasing
        }
    }

    /** 网页确认恢复后撤销所有会话引用与过期动作。 */
    fun released() {
        if (phase != ChromePhase.Releasing) return
        beginDocument(allowTakeover = false)
    }

    /** 无法确认 JS 存活时保留恢复状态；不能假设此时网页导航已经恢复。 */
    fun failed() {
        phase = ChromePhase.Recovery
        snapshot = null
        sessionId = null
        completedRequests.clear()
        requestOrder.clear()
    }

    /** 同一文档身份改变后更换会话并撤销旧动作，保留导航外壳直到新快照确认。 */
    fun rotateIdentity(): String {
        if (phase != ChromePhase.NativeNavigation) throw BridgeException(BridgeError.Busy)
        val id = UUID.randomUUID().toString()
        sessionId = id
        snapshot = null
        routeRevision = -1
        stateRevision = -1
        completedRequests.clear()
        requestOrder.clear()
        return id
    }

    /** 用户点击时复核当前会话、路由和动作，较新的展示修订不会自动重放旧命令。 */
    fun validateAction(sessionId: String, routeRevision: Int, actionId: String, ownerId: String) {
        if (this.sessionId != sessionId) throw BridgeException(BridgeError.StaleSession)
        if (phase != ChromePhase.NativeNavigation || this.routeRevision != routeRevision) {
            throw BridgeException(BridgeError.StaleRoute)
        }
        val current = snapshot
        val available = current != null && current.actions.any { action ->
            if (action.ownerId != ownerId || !action.enabled || action.loading) {
                false
            } else {
                action.actionId == actionId ||
                    action.menu?.any { it.actionId == actionId && it.enabled } == true
            }
        }
        if (!available) throw BridgeException(BridgeError.ActionNotAvailable)
    }

    /** 在当前会话内标记已处理消息，有限缓存只负责短期重复分派防护。 */
    fun markProcessed(requestId: String): Boolean {
        if (!BridgeValidator.isIdentifier(requestId)) return false
        if (!completedRequests.add(requestId)) return false
        requestOrder.add(requestId)
        if (requestOrder.size > 256) {
            val oldest = requestOrder.removeAt(0)
            completedRequests.remove(oldest)
        }
        return true
    }

    companion object {
        /** 原生必须的能力集合；网页未全部声明时不做接管。 */
        val REQUIRED_CAPABILITIES: Set<String> =
            setOf("navigation.v1", "actions.v1", "layout.v1", "menu.v1")
    }
}