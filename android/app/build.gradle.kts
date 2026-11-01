plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------- 版本号

// 默认取 gradle.properties 的基线；CI 传 -PmpVersionCode / -PmpVersionName 覆盖，
// 这样每次产物的 versionCode 都递增，可以覆盖安装升级（Android 的硬要求）。
val mpVersionCode = (project.findProperty("mpVersionCode") as String?)?.toIntOrNull() ?: 1
val mpVersionName = (project.findProperty("mpVersionName") as String?) ?: "0.0.dev"

// ---------------------------------------------------------------- 发布签名

// 密钥不进仓库：CI 把 Secrets 里的 base64 解码成 android/keystore.p12；
// 本地构建可放同名文件，或用 -PmpKeystorePath=... 指定路径。
val mpKeystorePath = (project.findProperty("mpKeystorePath") as String?) ?: "keystore.p12"
val mpKeystoreFile = rootProject.file(mpKeystorePath)
val mpKeystorePassword = (project.findProperty("mpKeystorePassword") as String?)
    ?: System.getenv("MP_KEYSTORE_PASSWORD")
val mpKeyAlias = (project.findProperty("mpKeyAlias") as String?)
    ?: System.getenv("MP_KEY_ALIAS")
    ?: "mpilot"
val mpKeyPassword = (project.findProperty("mpKeyPassword") as String?)
    ?: System.getenv("MP_KEY_PASSWORD")
    ?: mpKeystorePassword
val hasReleaseSigning = mpKeystoreFile.isFile && !mpKeystorePassword.isNullOrEmpty()

android {
    namespace = "org.moviepilot.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.moviepilot.android"
        // 26 起 WebView 消息监听、Keystore 设备锁绑定、自适应图标都可用。
        minSdk = 26
        targetSdk = 35
        versionCode = mpVersionCode
        versionName = mpVersionName

        // 只产出 arm64-v8a：目标设备都是 arm64，去掉其他 ABI 可显著缩小体积。
        // 注意：x86 模拟器与 32 位老机型将无法安装。
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = mpKeystoreFile
                // 仓库自带的密钥是 PKCS12；显式声明避免 Gradle 按扩展名猜错类型。
                storeType = "PKCS12"
                storePassword = mpKeystorePassword
                keyAlias = mpKeyAlias
                keyPassword = mpKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 首期不混淆：桥接协议依赖固定字符串，R8 全量规则留到功能稳定后再加。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // 没配置密钥时退回 debug 签名，产物仍可安装（但不能用于正式发布）。
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // WebView 兼容层：WebMessageListener 与 document-start 脚本是桥接的基础。
    implementation("androidx.webkit:webkit:1.12.1")

    // 协议 JSON 只使用 runtime 的 JsonElement API，不引入序列化编译插件。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}