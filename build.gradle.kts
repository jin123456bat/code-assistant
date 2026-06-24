plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.aiassistant"
version = "2.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // IntelliJ Platform 依赖（替代旧版 intellij { version/type }）
    intellijPlatform {
        create("IC", "2024.3")
        bundledPlugin("com.intellij.java") // Java PSI for code_intelligence
        bundledPlugin("org.intellij.plugins.markdown") // Markdown 解析
    }

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.anthropic:anthropic-java:2.43.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            untilBuild = ""
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    runIde {
        jvmArgs(
            "-Dsun.net.inetaddr.ttl=30",
            "-Dsun.net.inetaddr.negative.ttl=0"
        )
    }
    // 跳过搜索选项构建以加速
    buildSearchableOptions {
        enabled = false
    }
}
