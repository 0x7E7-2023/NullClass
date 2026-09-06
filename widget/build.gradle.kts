plugins {
    alias(libs.plugins.android.library)
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
}

dependencies {
    // 只依赖 model（读领域模型），不依赖任何 feature 模块
    implementation(project(":core:model"))

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
}
