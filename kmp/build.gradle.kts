plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
}

// 广告依赖黑名单门禁（设计 §2 / AGENTS.md §11）：依赖声明出现广告 SDK 即构建失败，
// 保证复刻 App 永远无广告。黑名单放在 kmp/config/ad-sdk-blacklist.txt（本文件会被
// 扫描，黑名单写在代码里会命中自身），覆盖 6 家 ADN、自有 ADX 与常见海外 SDK。
tasks.register("checkAdDeps") {
    group = "verification"
    description = "扫描全部构建脚本与版本目录，命中广告 SDK 黑名单即失败"
    doLast {
        val blacklistFile = file("config/ad-sdk-blacklist.txt")
        val blacklist = blacklistFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val offenders = mutableListOf<String>()
        fileTree(projectDir) {
            include("**/build.gradle.kts", "**/settings.gradle.kts", "**/libs.versions.toml")
            exclude("**/build/**", "**/.gradle/**")
        }.forEach { f ->
            val text = f.readText()
            blacklist.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
                offenders += "${f.relativeTo(projectDir)}: 命中黑名单「$it」"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("广告依赖黑名单检查失败（App 必须无广告）：\n" + offenders.joinToString("\n"))
        }
        println("广告依赖黑名单检查通过：${blacklist.size} 个黑名单项未命中")
    }
}

// 挂到每个子工程的 preBuild，使 assembleDebug 前置执行本检查。
subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(rootProject.tasks.named("checkAdDeps"))
    }
}
