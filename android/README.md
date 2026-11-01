# Android 客户端（Kotlin + Compose + WebView）

[返回首页](../README.md) · [移植步骤与注意事项](PORTING.md)

MoviePilot 的 Android 客户端。与 iOS 端同构：**原生外壳（Kotlin/Compose）+ 复用 MoviePilot-Frontend 的业务网页**，
桥接协议沿用 `moviepilot.chrome` v1，前端不需要为 Android 做任何改动（依据见 [PORTING.md](PORTING.md)）。

> 状态：首期骨架。协议、登录、原生导航外壳已实现；原生面板 / 业务 Sheet / 卡片长按菜单 / 小组件 / 原生推送尚未实现，
> 这些能力通过**不声明对应能力位**的方式让前端继续使用自己的实现，因此不会缺功能，只是暂时不是原生的。

## 本机没有 Android 编译环境时怎么出包

仓库不提交 `gradle-wrapper.jar`，APK 由 GitHub Actions 产出：

1. 推送改动到 `main`，或手动触发 **Actions → Android Debug APK → Run workflow**
   （`workflow_dispatch`，不需要等 push）。
2. 工作流用 Temurin JDK 17 + Gradle 8.9 执行 `gradle --no-daemon --stacktrace assembleDebug`。
3. 在本次 Run 页面底部 **Artifacts** 下载 `moviepilot-android-debug`（保留 14 天）。

产物是 debug 签名的可安装 APK，可直接 `adb install` 或传到手机点击安装。
工作流只在 `android/**` 或它自身变化时触发，不会干扰 iOS 的构建。

## 本地编译（可选）

需要 JDK 17 与 Android SDK 35：

```sh
cd android

# 首次需要生成 wrapper（仓库未提交该 jar）
gradle wrapper --gradle-version 8.9

./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/
./gradlew installDebug         # 连接设备后直接安装
```

没有系统 Gradle 时，直接用 Android Studio 打开 `android/` 目录也可以（IDE 会自动补 wrapper）。
工具链版本集中锁定在三处，升级时必须一起改：`android/build.gradle.kts`、`android/app/build.gradle.kts` 的 `compileSdk`、
`.github/workflows/android.yml` 的 `gradle-version`。

## 首次使用

1. 打开 App，填入手机能访问的实例地址（例如 `http://192.168.1.10:3000/`）。
2. 使用原 MoviePilot 账户登录；启用 MFA 的账户会要求输入身份验证器验证码。
3. 勾选「在本机记住登录状态」后，令牌用 Android Keystore 加密保存，冷启动自动恢复。

支持 HTTP 与 HTTPS。HTTPS 仍需系统信任的证书；自签 CA 需先在系统设置中安装——
`network_security_config.xml` 已显式信任用户证书，否则 Android 7 以后默认不信任。

## 目录

```text
android/app/src/main/java/org/moviepilot/android/
├── MainActivity.kt            入口 Activity（edge-to-edge + Compose）
├── core/                      协议与领域层，对应 iOS Sources/MoviePilotCore
│   ├── Json.kt                宽松 JSON 读取（数字 Int/Double 兼容）
│   ├── BridgeProtocol.kt      消息外壳、来源校验、深度/大小限制
│   ├── ChromeSession.kt       接管状态机（negotiating → prepared → nativeNavigation）
│   ├── NavigationSnapshot.kt  快照模型、解析与完整性校验
│   ├── ServerAddress.kt       地址标准化与同源边界（WebOrigin）
│   ├── Authentication.kt      登录响应 → 前端 Pinia 初始数据
│   └── ConnectionFailure.kt   网络失败分类与用户可读提示
├── data/                      实例配置、Keystore 令牌、通知安装标识
├── net/                       认证接口（OkHttp，固定路径、不跟随重定向）
├── web/                       WebView 容器与桥接
│   ├── BridgeScript.kt        注入脚本（补齐 window.webkit.messageHandlers）
│   └── WebCoordinator.kt      会话、消息分派、布局握手、命令超时
└── ui/                        Compose 界面
    ├── AppRoot.kt             页面状态机（连接 / 恢复 / 外壳）
    ├── ConnectionScreen.kt    原生登录
    ├── ShellScreen.kt         顶栏、页内标签、底栏、恢复页
    ├── Symbols.kt             SF Symbol → Material 图标映射
    └── Theme.kt               Material 3 主题
```

## 与 iOS 的差异（摘要）

- 视觉走 Material 3，不复刻 iOS 26 的 Liquid Glass。
- 返回不使用侧滑卡片转场，目前只有顶栏返回按钮（Predictive Back 计划见 PORTING.md）。
- 单实例单 WebView；多实例的数据隔离方案见 PORTING.md 的「注意事项」。