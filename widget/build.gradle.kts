plugins {
    alias(libs.plugins.android.library)
    // Glance 的 @Composable 也是 Compose 编译目标，必须 apply compose 编译器插件
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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

    // @EntryPoint 注解接口必须过 Hilt KSP 聚合（hilt_aggregated_deps），
    // :app 的 Hilt Gradle 插件才会把本接口实现进 SingletonComponent；
    // 本模块无 @HiltAndroidApp，不生成组件，只做聚合
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.kotlin.test)
}
