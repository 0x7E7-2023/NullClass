// 顶层只声明插件版本；各模块按需 apply。
// 注意：AGP 9 起 Android 模块使用内置 Kotlin 支持，不再 apply kotlin-android。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
