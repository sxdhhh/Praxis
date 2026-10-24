pluginManagement {
    includeBuild("build-plugins")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }
}

rootProject.name = "Praxis"

// ── 组装层 ─────────────────────────────────────────────
include(":app")

// ── 基础层（无 Android 依赖）────────────────────────────
// :core:common 已删除（0 个 main 源文件，只剩构建脚本与空目录）。
// 它的定位由 :core:model（纯数据契约）覆盖；真要放纯工具函数时再按需新建。
include(":core:model")
include(":core:data")

// ── 业务层 ─────────────────────────────────────────────
include(":core:ai")
include(":core:tool")
include(":core:memory")
include(":core:terminal")
// Linux 环境层：proot 容器配置 / 国内镜像源 / SSH 与 FRP 参数的**纯逻辑**。
// 纯 JVM 模块 —— 不碰 Context / ProcessBuilder，命令只「拼字符串」，
// 真正拉起进程由 :app 的 ProotCommandRunner 负责（端口在里、实现在外，与 CommandRunner 同套路）。
include(":core:linux")
include(":core:workflow")
include(":core:plugin")

// ── UI 基础设施 ────────────────────────────────────────
include(":ui:theme")
include(":ui:design")
include(":ui:widgets")
include(":ui:navigation")
// 图标集：与 InstallerX 的 ui/icons 同位。独立成模块是为了让
// 「图标的唯一来源」这件事有物理边界 —— 图标散在 :app 里时，
// feature 模块想换图标只能靠 import :app，方向是反的。
include(":ui:icons")

// ── 功能页 ─────────────────────────────────────────────
include(":feature:chat")
include(":feature:settings")
include(":feature:terminal")
