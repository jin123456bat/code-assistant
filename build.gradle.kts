plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.aiassistant"
version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.jetbrains:markdown:0.7.3")
    implementation("com.anthropic:anthropic-java:2.40.1") {
        // 排除 SDK 自带的 Kotlin stdlib，避免与 IntelliJ Platform 内置版本冲突
        exclude("org.jetbrains.kotlin", "kotlin-stdlib")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.22")
}

intellij {
    version.set("2023.3")
    type.set("IC") // IntelliJ IDEA Community — 兼容所有基于 IntelliJ Platform 的 IDE
    plugins.set(listOf("com.intellij.java")) // Java PSI for code_intelligence
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("")
    }
    // 热重载：启动 sandbox IDE 后编译代码即可自动加载，无需重启
    runIde {
        autoReloadPlugins.set(true)
    }
    // 跳过搜索选项构建以加速
    buildSearchableOptions {
        enabled = false
    }
}
