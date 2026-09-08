plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nullclass.ocr"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // .onnx 是压缩率极低的二进制，别让 aapt 再压一遍（首次加载会慢）
    androidResources {
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            // 不做 legacy 压缩：.so 本身已很小，压缩只会带来安装期解压与设备上双份占用
            useLegacyPackaging = false
        }
    }
}

dependencies {
    api(project(":importer"))

    implementation(libs.coroutinesAndroid)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.kotlin.test)
    // JVM 单测用桌面版 ORT 跑同一份模型（Android AAR 的 .so 在 JVM 上加载不了）
    testImplementation(libs.onnxruntime.desktop)
}

// 模型是同一份资产，JVM 单测直接从磁盘读
tasks.withType<Test>().configureEach {
    systemProperty("ocrAssetsDir", file("src/main/assets/ocr").absolutePath)
    // 单测用 AWT 合成测试图，CI 无显示环境
    systemProperty("java.awt.headless", "true")
}

// 桌面 ORT 与 Android ORT 提供同样的 ai.onnxruntime 类，单测时只保留桌面版
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}
