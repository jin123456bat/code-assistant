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
    }

    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.jetbrains:markdown:0.7.3")
    implementation("com.anthropic:anthropic-java:2.40.1") {
        exclude("org.jetbrains.kotlin", "kotlin-stdlib")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
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

// 检查文件描述符限制，避免 sandbox IDE 索引时触发 "Too many open files"
val checkFdLimit by tasks.registering {
    doLast {
        val softLimit = try {
            ProcessBuilder("bash", "-c", "launchctl limit maxfiles 2>/dev/null | awk '{print \$2}'")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().toIntOrNull()
        } catch (_: Exception) { null }
        if (softLimit != null && softLimit < 1024) {
            logger.warn("═══════════════════════════════════════════════════════════")
            logger.warn("⚠️  系统文件描述符软限制: $softLimit（过低！最小需要 1024）")
            logger.warn("")
            logger.warn("   这是 macOS launchd 的系统默认值，Gradle Daemon 和 Sandbox IDE")
            logger.warn("   都继承此限制。Shell 的 ulimit 设置对此无效。")
            logger.warn("")
            logger.warn("   永久修复（需要管理员密码）:")
            logger.warn("     sudo launchctl limit maxfiles 65536 200000")
            logger.warn("")
            logger.warn("   临时绕过（不需要 sudo）:")
            logger.warn("     ./gradlew --stop    # 杀所有 daemon")
            logger.warn("     pkill -9 -f GradleDaemon  # 确保无残留")
            logger.warn("     ./gradlew runIde    # 重新从终端启动")
            logger.warn("═══════════════════════════════════════════════════════════")
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
        dependsOn(checkFdLimit)
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
