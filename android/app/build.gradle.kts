plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.moviepilot.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.moviepilot.android"
        // 26 起 WebView 多 Profile、Keystore 设备锁绑定、自适应图标都可用。
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.9"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 首期不混淆：桥接协议依赖固定字符串，R8 全量规则留到功能稳定后再加。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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