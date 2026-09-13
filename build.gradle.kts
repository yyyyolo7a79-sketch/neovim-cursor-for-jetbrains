plugins {
    id("java")
    // IntelliJ Platform Gradle Plugin 2.x —— 2024.2+ 平台必须使用 2.x 系列
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        // 使用 local() 指向本地 IDE 时，必须从本地安装提取平台构件
        localPlatformArtifacts()
    }
}

dependencies {
    intellijPlatform {
        // 指向本机已安装的 IDEA 2025.2.1，避免下载数 GB 的分发包
        local("D:/develop/idea/IntelliJIDEA2025.2.1")
    }
}

java {
    // 2024.2+ 平台要求 Java 21（本机 D:/develop/jdk/jdk21）
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            // 252 = 2025.2 系列
            sinceBuild.set("252")
            untilBuild.set("252.*")
        }
    }

    // 关闭 searchable options 索引构建：POC 阶段不需要，可大幅缩短构建时间
    buildSearchableOptions.set(false)
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    // 启动沙盒 IDE（runIde）时自动打开本项目目录：
    // 这样 IDE 一启动就有编辑器存在，自动诊断无需人工打开文件
    named("runIde") {
        // 用安全转换：若任务类型不匹配则静默跳过，避免配置阶段直接失败
        (this as? JavaExec)?.args("D:/develop/github/github上传/jetbrainsIDE_neovim-cursor")
    }
}
