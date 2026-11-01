package org.moviepilot.android.web

import org.moviepilot.android.core.AuthenticationSession
import org.moviepilot.android.core.jsStringLiteral
import java.util.UUID

/**
 * 注入到网页 document-start 的桥接脚本。
 *
 * 设计目标：**前端不需要任何修改**。
 * MoviePilot-Frontend 通过 `window.webkit.messageHandlers.moviepilotChrome`（iOS 的 WKScriptMessageHandler）
 * 和 `window.MoviePilotNative.transportVersion` 探测容器，这里在 Android WebView 中补出同名对象，
 * 底层转发到 androidx.webkit 的 WebMessageListener（受来源与主 frame 限制）。
 *
 * 与 iOS BridgeBootstrap.js 的差异只有一处：iOS 用两个 WKScriptMessageHandler，
 * Android 用两个 WebMessageListener 对象名，语义等价。
 */
object BridgeScript {
    /** 承载 chrome 协议消息的 WebMessageListener 对象名。 */
    const val CHROME_OBJECT = "MoviePilotChromeBridge"

    /** 承载认证失效通知的 WebMessageListener 对象名。 */
    const val AUTH_OBJECT = "MoviePilotAuthBridge"

    /**
     * 生成完整启动脚本：桥接对象 + 登录态种子。
     *
     * 登录态只写 localStorage 里前端已有的 `auth` / `user` 两个键，
     * 并用 sessionStorage 标记防止同一文档重复写入。
     */
    fun build(installation: String, session: AuthenticationSession?, origin: String): String {
        val marker = "moviepilot.native.authentication.${UUID.randomUUID()}"
        val seed = if (session == null) {
            ""
        } else {
            """
            (function () {
              "use strict";
              if (window.top !== window) { return; }
              if (location.origin !== ${jsStringLiteral(origin)}) { return; }
              if (sessionStorage.getItem(${jsStringLiteral(marker)})) { return; }
              try {
                var initial = JSON.parse(${jsStringLiteral(session.browserStorage.toString())});
                localStorage.setItem("auth", JSON.stringify(initial.auth));
                localStorage.setItem("user", JSON.stringify(initial.user));
                sessionStorage.setItem(${jsStringLiteral(marker)}, "1");
              } catch (error) { }
            })();
            """.trimIndent()
        }
        return """
        (function () {
          "use strict";
          if (window.top !== window) { return; }
          if (window.MoviePilotNative) { return; }
          function post(objectName, message) {
            var bridge = window[objectName];
            if (!bridge) { return; }
            bridge.postMessage(JSON.stringify(message));
          }
          var chrome = Object.freeze({
            postMessage: function (message) { post(CHROME_OBJECT_LITERAL, message); }
          });
          var authentication = Object.freeze({
            postMessage: function (message) { post(AUTH_OBJECT_LITERAL, message); }
          });
          try {
            Object.defineProperty(window, "webkit", {
              value: Object.freeze({
                messageHandlers: Object.freeze({
                  moviepilotChrome: chrome,
                  moviepilotAuthentication: authentication
                })
              }),
              configurable: true,
              writable: false
            });
          } catch (error) { }
          Object.defineProperty(window, "MoviePilotNative", {
            value: Object.freeze({
              transportVersion: 1,
              notificationInstallation: ${jsStringLiteral(installation)},
              postMessage: chrome.postMessage,
              authenticationRequired: function () {
                authentication.postMessage({ version: 1, type: "required" });
              }
            }),
            configurable: false,
            writable: false
          });
        })();
        $seed
        """.trimIndent()
            .replace("CHROME_OBJECT_LITERAL", jsStringLiteral(CHROME_OBJECT))
            .replace("AUTH_OBJECT_LITERAL", jsStringLiteral(AUTH_OBJECT))
    }
}