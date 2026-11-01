# iOS → Android 移植步骤与注意事项

本文记录 Android 端的移植决策、实施步骤、验收方式与已知坑位。
阅读顺序建议：先看第 1、2 节理解为什么这么拆，再看第 4 节按阶段推进。

## 1. 移植原则

这个项目不是"把 Swift 翻成 Kotlin"，而是**重写外壳、复用网页**：

| 事实 | 依据 |
| --- | --- |
| 业务页面 100% 在 MoviePilot-Frontend（Vue），iOS 端 0 行业务 UI | [../README.md](../README.md) |
| iOS 原生代码 7684 行，核心协议包 2781 行，其中 22/24 个文件只 `import Foundation` | `ios/MoviePilot`、`Sources/MoviePilotCore` |
| 桥接入口只有一个 JS 全局对象，前端通过它探测容器 | `ios/MoviePilot/Resources/BridgeBootstrap.js` |
| 维护者已把 Android 列入规划 | `ios/README.md` 末尾 |

因此三条硬规则：

1. **协议 v1 一字不改**：`moviepilot.chrome`、`major: 1`、消息类型名、字段名、能力位全部对齐 iOS，
   这样前端、iOS、Android 三端共用同一套契约。
2. **前端零改动**：只在 WebView 里补齐 `window.webkit.messageHandlers.*` 与 `window.MoviePilotNative`，
   让前端"以为"自己仍在 iOS 容器里（见第 3 节）。
3. **不复刻 iOS 观感**：Liquid Glass、`UISheetPresentationController`、`UIMenu` 悬浮预览在 Android 上没有等价物，
   强行模拟只会得到"高成本 + 不自然"。Android 走 Material 3，把预算放在协议与交互正确性上。

## 2. 组件映射

| iOS | Android | 状态 |
| --- | --- | --- |
| `Sources/MoviePilotCore/BridgeProtocol.swift` | `core/BridgeProtocol.kt` | 已移植 |
| `ChromeSession.swift` | `core/ChromeSession.kt` | 已移植 |
| `NavigationSnapshot.swift` | `core/NavigationSnapshot.kt` | 已移植（面板子树只保留"是否存在"） |
| `ServerAddress.swift`（含 `WebOrigin`） | `core/ServerAddress.kt` | 已移植 |
| `Authentication.swift` | `core/Authentication.kt` | 已移植 |
| `ConnectionFailure.swift` | `core/ConnectionFailure.kt` | 已移植（NSURLError → Java 异常链） |
| `AuthenticationClient.swift` | `net/AuthenticationClient.kt` | 已移植（URLSession → OkHttp） |
| `TokenStore.swift` / `LoginCredentialStore.swift`（Keychain） | `data/SecretStore.kt`（Android Keystore AES-GCM） | 已移植令牌；密码记忆未做 |
| `InstanceStore.swift` | `data/InstanceStore.kt` | 已移植 |
| `WebCoordinator.swift` | `web/WebCoordinator.kt` | 已移植协议部分 |
| `Resources/BridgeBootstrap.js` | `web/BridgeScript.kt`（内联脚本） | 已移植 |
| `ShellController.swift`（1578 行） | `ui/ShellScreen.kt` | 顶栏/页内标签/底栏/恢复页；菜单、Sheet、面板、转场未做 |
| `LoginController.swift` | `ui/ConnectionScreen.kt` | 密码 + OTP；通行密钥未做 |
| `NativePanelController.swift`（935 行） | — | 未移植（阶段三） |
| `NativeWebSheetController.swift` | — | 未移植（阶段三） |
| `CardMenuInteraction.swift` / `StandaloneMenuBridge.swift` | — | 未移植（阶段三） |
| `NotificationService.swift`（APNs） | — | 未移植（阶段四） |
| `WidgetService.swift` + `MoviePilotWidgets`（WidgetKit） | — | 未移植（阶段四，改用 Glance） |
| `PasskeyAuthenticator.swift` | — | 未移植（阶段五，Custom Tabs / Credential Manager） |
| `MoviePilotUITests`（4643 行 XCUITest） | — | 不可移植，按需重写 |

## 3. 桥接机制对照

三处等价替换，改的是"传输层"，消息内容与语义完全相同：

| iOS（WebKit） | Android（androidx.webkit） |
| --- | --- |
| `WKUserContentController.add(handler, name: "moviepilotChrome")` | `WebViewCompat.addWebMessageListener(webView, "MoviePilotChromeBridge", rules, listener)` |
| `WKUserScript(injectionTime: .atDocumentStart, forMainFrameOnly: true)` | `WebViewCompat.addDocumentStartJavaScript(webView, script, rules)` |
| `webView.callAsyncJavaScript(...)` 派发 `CustomEvent` | `webView.evaluateJavascript(...)` 派发同名 `CustomEvent` |
| `message.frameInfo.securityOrigin` + `message.webView === webView` | `sourceOrigin: Uri` + `view === webView` + `isMainFrame` |

注入脚本（`web/BridgeScript.kt`）做的事，等价于 iOS 的 `BridgeBootstrap.js`：

```js
window.webkit.messageHandlers.moviepilotChrome.postMessage(msg)
  → window.MoviePilotChromeBridge.postMessage(JSON.stringify(msg))
window.MoviePilotNative = { transportVersion: 1, notificationInstallation, postMessage, authenticationRequired }
```

同时把登录态写进前端已有的 `localStorage.auth` / `localStorage.user`（对应
`WebCoordinator.swift` 的 `browserStorage` 注入），用 `sessionStorage` 标记防止重复写入。

> 安全边界与 iOS 一致：`BridgeValidator` 只接受**当前 WebView、主 frame、精确同源、会话匹配**的消息，
> 伪造 `postMessage` 的脚本无法穿越 iframe 或跨域页面。

## 4. 分阶段步骤

每个阶段都以"Actions 编译通过 + 真机行为正确"为完成标准。

### 阶段 0：工程与 CI（已完成）

1. `android/` 下建立单模块 Gradle 工程，锁定 AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21 / compileSdk 35 / minSdk 26。
2. `.github/workflows/android.yml`：JDK 17 + `gradle/actions/setup-gradle` 提供 Gradle，产物上传 Artifact。
3. 验收：workflow 绿；Artifact 里的 APK 能安装启动到登录页。

### 阶段 1：协议层（已完成）

4. 按第 2 节表格逐个移植 `core/` 下的文件，**保持字段名与错误码不变**。
5. 数字字段统一"先读 Double 再取整"，与 Swift 的 `Double` 解码行为一致（前端可能发 `1` 或 `1.0`）。
6. 验收：真机连上实例，完成 `web.ready → host.configure → navigation.state → chrome.prepare → chrome.accepted`
   五步握手，顶栏标题变成网页给的标题。

### 阶段 2：原生外壳（基本完成）

7. 顶栏：返回（`navigation.back`，`source: "button"`）、标题、动作按钮（`action.invoke`）、更多菜单（`navigation.open`）。
8. 页内标签：`headerTab.select`，携带 `ownerId` + `tabId`。
9. 底栏：`navigation.selectRoot`，按 `route.rootId` 高亮。
10. 布局握手：`chrome.prepare` 与 `host.layout` 共用同一份 payload，几何变化才下发。
11. 生命周期：`host.lifecycle` 上报 `active`/`background`；回前台补一次 `state.request(reason: foreground)`。
12. 恢复页：协商或命令超时（3 s）后进入原生恢复页，重试只 `reload()`，不重放业务动作。
13. 验收：在实例里连续切换底部入口、进出详情页、旋转屏幕、切后台再回来，标题与选中态始终跟随网页。

### 阶段 3：原生面板 / 业务 Sheet / 卡片菜单（未开始）

按依赖顺序做，不要并行：

1. **原生面板**（`NativePanelController`）：先在 `BridgeScript` 声明 `panelsVersion`/`panelFeedsVersion`，
   再实现网格与连续列表，事件用 `action.invoke` + `panelEvent`/`itemId`/`value` 回传。
2. **业务 Sheet**：这是最危险的一块，先读第 6 节第 2 条再动手。要求同一 WebView 原地改布局，
   用 `presentation.prepare → attach → detach/dismiss` 四步握手，**禁止放进 Dialog 或第二个 Window**。
3. **卡片长按菜单**：`cardMenu.prepare/refresh/accessible/invalidate` 四条消息，
   原生这边用长按 + 浮层预览；没有 `UIMenu` 就用自绘卡片，注意关闭后回发 `cardMenu.dismissed`。
4. **独立操作菜单**：`standaloneMenu.open/refresh/invalidate`，关闭后回 `standaloneMenu.dismissed`。
5. 每加一个能力位，先做"关掉能力位时前端自动降级"的回归，确认不会出现两套 UI 同时出现。

### 阶段 4：小组件与通知（未开始）

1. 小组件用 Glance + WorkManager。iOS 的 App Group 共享目录在这里不需要——
   小组件与 App 同进程、同私有目录，`WidgetStore` 可简化为 Room 或 DataStore。
2. 刷新频率受系统限制（`updatePeriodMillis` 最小 30 分钟，WorkManager 最小 15 分钟），与 iOS timeline 同理。
3. 点击小组件的链接：iOS 用 `moviepilot://` scheme，Android 用 intent-filter + `navDeepLink` 等价物。
4. **原生推送需要后端配合**：MoviePilot-Server 目前只有 APNs 与 Web Push 通道，
   Android 原生推送要新增 FCM；不接 FCM 时，保留"把网页添加到主屏幕"的 Chrome Web Push 方案，
   与 iOS 免费签名用户的路径完全一致，成本≈0。

### 阶段 5：手势与登录增强（未开始）

1. 返回手势：用 `OnBackPressedDispatcher` + Predictive Back（Android 13+），
   只在 `route.canGoBack && overlayOpen != true` 时接管，其余交给系统。
2. 离页预览：iOS 用 `takeSnapshot`，Android 用 `PixelCopy`（同 Window）或 `view.draw(Canvas)`，
   失败时直接放弃预览（当前实现就是"立即回 `navigation.previewed`"）。
3. 通行密钥：`ASWebAuthenticationSession` → Chrome Custom Tabs + `moviepilot://` intent-filter；
   若后端已提供 `mfa/passkey/authenticate/start|finish`，也可直接用 Credential Manager 走原生流程。
4. 媒体权限：`WebChromeClient.onPermissionRequest` 现在一律 `deny()`，
   要接麦克风/摄像头须先跑运行时权限，再 `request.grant(...)`。

## 5. 能力位策略：宁可少报，不要虚报

前端按 `window.MoviePilotNative` 上的版本位决定"要不要用原生实现"。当前 Android 只上报：

```js
transportVersion: 1
notificationInstallation: "<安装 UUID>"
```

`panelsVersion` / `sheetsVersion` / `cardMenusVersion` / `standaloneMenusVersion` / `widgetsVersion` /
`readonlyCacheVersion` 一律**不声明**，于是前端继续用自己的 HTML 面板与弹窗——
功能不缺，只是暂时不是原生的，用户无感。

反过来说：**每声明一个能力位，就必须把那套原生交互完整实现并验收**，否则会出现"按钮点了没反应"。

## 6. 注意事项（踩坑清单）

### 6.1 多实例 WebView 数据隔离

- iOS：`WKWebsiteDataStore(forIdentifier: instance.id)`，每个实例独立容器。
- Android：默认全局只有一个 WebView profile（Cookie、localStorage、IndexedDB 全共享）。
- 现状：首期只支持"当前选择的一个实例"，切换实例时 `InstanceStore.select()` 复用配置但**没有清理网页数据**。
- 上多实例前必须做：`androidx.webkit` 的 Profile API（`ProfileStore`，需要较新的系统 WebView），
  或在切实例时执行 `WebStorage.deleteAllData()` + `CookieManager.removeAllCookies()` 并重建 WebView。
  两种方案都要在目标机型上实测 `localStorage` 是否会串号。

### 6.2 业务 Sheet 不能放 Dialog

- 现象：把 WebView 移进 `Dialog`/`ModalBottomSheet`（Material 3 的 ModalBottomSheet 走独立 Window）后，
  页面白屏、表单状态丢失，甚至整页重载。
- 原因：Android 的 `View` 不能跨 Window 移动，WebView 尤其严重（渲染进程与 Surface 绑定）。
- 做法：Sheet 必须是**同窗口浮层**——在同一个 `Box` 里用 `zIndex` + 高度动画做出"底部抽屉"，
  WebView 始终是同一个实例、同一个父容器，只改布局参数；
  同时保留 iOS 的 `layoutRevision` 握手，让网页知道可用高度变了。

### 6.3 WebView 特性是有版本门槛的

- `WEB_MESSAGE_LISTENER`：WebView 88+；`DOCUMENT_START_SCRIPT`：WebView 105+；
  多 Profile：更新的版本。
- 代码已做降级：不支持 document-start 时在 `onPageStarted` 补注入，
  但这会晚于页面首屏脚本，前端可能已经判定"没有原生容器"。
- 做法：启动时检查 `WebViewFeature.isFeatureSupported`，不满足就显示可操作的恢复页，提示更新 Android System WebView。

### 6.4 明文 HTTP 与自签证书

- iOS 用 `NSAllowsArbitraryLoads`。Android 需要两件事：
  `android:usesCleartextTraffic="true"`（已配）+ `network_security_config.xml` 里显式信任 `user` 证书（已配）。
- 不配置 `user` 证书时，用户在系统里装了自签 CA 也不会被 App 采用（Android 7 起的默认行为），
  表现为"浏览器能开、App 报证书错误"。

### 6.5 布局 payload 需要真机校准

- 当前 `systemSafeArea` / `chromeOcclusion` / `contentInsets` / `keyboardOcclusion` 全部报 0，
  因为 WebView 被放在系统栏与顶栏之内的矩形区域里（网页拿到的是已经扣好的视口）。
- 如果改成"WebView 全屏 + 顶栏浮在上面"（更接近 iOS），就必须改成上报真实 safe area，
  否则网页会多留一层内边距。改动只涉及 `ui/ShellScreen.kt` 的 `shellLayoutPayload`。
- 校准方法：在网页控制台打印收到的 payload，逐项与 `document.documentElement.clientHeight` 对照。

### 6.6 键盘

- `windowSoftInputMode=adjustResize` + edge-to-edge 下，Compose 侧用 `imePadding()` 收缩视口，
  所以 `keyboardOcclusion` 报 0。
- 如果发现输入框被键盘挡住，优先检查宿主是否有 `fitsSystemWindows`，不要急着改 payload。

### 6.7 文件选择、下载、相机、外链

首期都没做，各自对应一个 WebView 回调，别漏：

> 构造 `WebView` 时必须传 **Activity context**，不要传 `applicationContext`：
> JS 弹窗（`window.alert/confirm`）、文件选择、权限申请都要用它拿 window token，否则会抛
> `Unable to add window -- token null is not for an application`。

| 能力 | 回调 | 备注 |
| --- | --- | --- |
| 上传文件/照片 | `WebChromeClient.onShowFileChooser` | 需要 Activity 转发 `startActivityForResult` |
| 下载 | `setDownloadListener` + FileProvider | 需要存储权限或 MediaStore |
| 摄像头/麦克风 | `WebChromeClient.onPermissionRequest` | 现在故意 `deny()` |
| `target="_blank"` | `setSupportMultipleWindows(false)` | 当前同窗口打开，够用 |
| 外链 | `shouldOverrideUrlLoading` | 已实现：非同源交给系统浏览器 |

### 6.8 渲染进程崩溃

- iOS：`webViewWebContentProcessDidTerminate` → 恢复。
- Android：`WebViewClient.onRenderProcessGone` 返回 `true` 后这个 WebView 就废了，**必须重建**。
- 现状：通过 `onFatal` 回调让 Compose 换 `epoch`，用 `remember(instance.id, epoch)` 重建整个容器。
  任何"复用旧 WebView"的写法都会崩溃。

### 6.9 UI 测试不可移植

`ios/MoviePilotUITests`（4643 行）依赖 XCUITest 与可访问性标识，Android 侧需要用 Compose UI Test
或 Espresso 重写。建议只在阶段 3 之后补"协商成功率""切根""恢复页"三条冒烟用例。

### 6.10 不要给前端加平台分支

一旦为了 Android 在 MoviePilot-Frontend 里写 `if (isAndroid)`，三端协议就开始分叉。
需要平台差异时，正确做法是新增**能力位**（例如 `sheetsVersion: 2`），由能力位驱动行为。

### 6.11 外壳观感约定（玻璃 / 排版 / 动画 / 去重）

实现集中在 `ui/ShellScreen.kt`，改观感只动这一个文件：

- **液态玻璃**：Android 没有 backdrop blur，`Modifier.blur()` 模糊的是自己而不是背后内容。
  `GlassPill` 用四层叠加逼近：accent 径向光晕（`blur(26.dp)`，API 31+ 真实高斯模糊）、18dp 投影、
  深浅色两套半透明分层填充（thick/thin material）、顶部亮底部暗的边缘光描边。
  真要"内容从 bar 下透出来"，得改成全屏 WebView + 按 `chromeOcclusion` 协商，改动面很大，暂不做。
- **排版不再写死高度**：一律 `heightIn(min = …)`；标题、标签单行 + `Ellipsis`；
  页内标签横向可滚动；顶栏内联动作按屏宽取 2/3/4 个，其余进溢出菜单。
  系统大字体下外壳会自然变高，不会再截断文字。
- **动画**：标题 `AnimatedContent` 交叉淡入 + 轻微位移；顶/底栏进出场；
  路由变化时在 WebView 上盖一层底色淡出（240 ms）；按钮按下缩放 0.90 / 底栏 0.92。
- **禁止**为了做转场把 WebView 放进 `AnimatedContent`/`AnimatedVisibility` 的内容分支，
  那会重建容器（白屏、丢状态）。转场只能通过叠加层和修饰符实现。
- **菜单去重（底栏优先）**：`更多` 菜单里与底部入口同 path（忽略结尾斜杠）或同 id 的条目直接过滤，
  只影响原生显示；点击仍走 `navigation.open` 的原快照校验。

## 7. 待真机验证的未定项

1. **前端是否有 UA / `isIOS` 门控**：前端仓库不在本工作区，无法静态确认。
   验证方法：加载兼容前端后在 Chrome DevTools 里看是否发出 `web.ready`。没有则说明前端只看
   `window.webkit.messageHandlers`（已补齐）或还有其它判断，需要按实际调整 shim。
2. **未声明 `panelsVersion` 时搜索/通知按钮的行为**：预期前端回落到自己的 HTML 弹窗，需要实测。
3. **多 Profile 可用性**：见 6.1，需在真机与不同 WebView 版本上确认。
4. **layout payload 四项是否需要非零值**：见 6.5。
5. **自签 CA 场景**：安装 CA 后能否正常访问实例。

## 8. 待办清单

- [x] 阶段 0：工程骨架 + Actions 编译 + APK 产物
- [x] 阶段 1：协议层（Bridge / Session / Snapshot / Address / Authentication）
- [x] 阶段 2：外壳（登录、顶栏、页内标签、底栏、布局握手、恢复页、生命周期）
- [ ] 阶段 2 收尾：返回手势与 Predictive Back；离页预览（PixelCopy）
- [ ] 阶段 3：原生面板 → 业务 Sheet（同窗口）→ 卡片长按菜单 → 独立操作菜单
- [ ] 阶段 4：小组件（Glance）、通知（先复用主屏幕 Web Push，再评估 FCM）
- [ ] 阶段 5：通行密钥（Custom Tabs）、媒体权限、下载与文件选择
- [ ] 多实例数据隔离方案选型与实测
- [ ] Compose UI 冒烟测试（协商、切根、恢复）
- [ ] 真机验收矩阵：横屏、大字体、刘海/挖孔、深浅色、弱网、后台恢复