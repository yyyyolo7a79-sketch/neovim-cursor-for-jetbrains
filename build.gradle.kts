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
            // 242 = 2024.2 系列。
            //
            // 下限之所以卡在 242 而不是更低：2024.2 是平台改用 JBR 21 的起点，
            // 与本项目的编译目标（Java 21）一致。2024.1 及更早跑在 JBR 17 上，
            // 加载 Java 21 字节码会直接抛 UnsupportedClassVersionError 崩溃。
            sinceBuild.set("242")

            // 【刻意不设置 untilBuild】
            //
            // 本插件只依赖平台核心 API（Editor / CaretListener / EditorColors 等），
            // 不含任何版本特定代码，因此对后续版本天然兼容。
            //
            // JetBrains 官方建议：这种情况应留空 untilBuild，表示兼容所有后续版本。
            // 写死上限（如曾经的 "252.*"）会导致每次 IDE 升级插件都被判为"不兼容"，
            // 用户只能等新版本发布 —— 这正是本插件先前只能在 2025.2 上使用的原因。
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
