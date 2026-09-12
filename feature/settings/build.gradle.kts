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

    // 二维码：zxing-core 纯 Java 生成位图；embedded 封装扫码相机
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

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
}
