// 插件版本集中锁定，与 .github/workflows/android.yml 中的 Gradle 版本配合使用。
// 升级时三处必须一起改：本文件、workflow 的 gradle-version、app 的 compileSdk。
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}