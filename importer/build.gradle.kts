import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/**
 * 教务适配器库（仓库根 `jw-adapters/`）是**唯一事实来源**：
 * - 构建时同步进资源 → 成为 APK 里的「内置适配器」（见 JwBuiltinLibrary）
 * - 同一个目录也能直接托管成第三方库（含 index.json）
 */
val jwLibraryDir: File = rootProject.layout.projectDirectory.dir("jw-adapters").asFile
val syncJwLibrary = tasks.register<Sync>("syncJwLibrary") {
    from(jwLibraryDir)
    into(layout.buildDirectory.dir("generated/jwLibrary/jw-adapters"))
    exclude("**/.DS_Store", "**/.git/**")
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/jwLibrary"))
    }
}

tasks.named("processResources") {
    dependsOn(syncJwLibrary)
}

tasks.withType<Test>().configureEach {
    // CI 的 Rhino 回归测试直接读磁盘上的库目录（fixture 不参与打包校验之外的用途）
    systemProperty("jwLibraryDir", jwLibraryDir.absolutePath)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.serialization.json)

    testImplementation(libs.kotlin.test)
    // 在 JVM 上跑适配器的 parse.js，对 fixture 做真实回归（不用模拟器）
    testImplementation(libs.rhino)
}
