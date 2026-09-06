plugins {
    alias(libs.plugins.android.library)
    // Glance 的 @Composable 也是 Compose 编译目标，必须 apply compose 编译器插件
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nullclass.widget"
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
    // 读领域模型 + 数据仓库（小组件经 EntryPoint 直读 Room），复用 core:ui 课表色板
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // 只用 @EntryPoint 注解标记接口，运行时由 :app 的 SingletonComponent 满足；
    // 不加 ksp/hilt 编译器（本模块不生成组件）
    implementation(libs.hilt.android)

    testImplementation(libs.kotlin.test)
}
