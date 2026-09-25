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

    // 文案走真实资源：单测经 Robolectric 取 strings.xml
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 在 SDK 36 沙箱里要反射 FileDescriptor 内部，新版 JDK 默认不导出
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    // 用 api 而非 implementation：core:ui 的公开签名里出现 Modifier、Dp 等类型，
    // 下游 6 个模块（app / widget / feature×4）都依赖 core:ui，经传递获得即可。
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    // NullClassTheme 的公开参数是 ThemeMode
    api(project(":core:model"))
    // 主题里按手动深浅色重设系统栏（enableEdgeToEdge / LocalActivity）
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
}
