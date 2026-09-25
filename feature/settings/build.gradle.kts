plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nullclass.feature.settings"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":sync"))
    implementation(project(":importer"))
    implementation(project(":ocr"))
    // 设置页「添加桌面小组件」需要引用两个小组件 Receiver 做类型安全 pin
    implementation(project(":widget"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)

    // 二维码：zxing-core 生成；扫码用 CameraX 1080p 分析流 + ML Kit 端侧模型
    // （quickie 同样套 ML Kit，但把分析分辨率锁死 720p，version 27 的码模块不够像素）
    implementation(libs.zxing.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.mlkit)
    implementation(libs.mlkit.barcode.scanning)

    // 适配器库拉取 / 图片课表取图
    implementation(libs.okhttp)
    // 一键刷新记忆（上次学校 + 课表页地址）
    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // 适配器搜索的匹配规则是纯函数，用 JVM 单测锁住（见 JwAdapterSearchTest）
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
