plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nullclass.core.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // 用 api 而非 implementation：core:ui 的公开签名里出现 Modifier、Dp 等类型，
    // 下游 6 个模块（app / widget / feature×4）都依赖 core:ui，经传递获得即可。
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
}
