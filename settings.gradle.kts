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
include(":core:common")
include(":core:model")
include(":core:data")

// ── 业务层 ─────────────────────────────────────────────
include(":core:ai")
include(":core:tool")
include(":core:memory")
include(":core:terminal")
include(":core:workflow")
include(":core:plugin")

// ── UI 基础设施 ────────────────────────────────────────
include(":ui:theme")
include(":ui:design")
include(":ui:widgets")
include(":ui:navigation")

// ── 功能页 ─────────────────────────────────────────────
include(":feature:chat")
include(":feature:settings")
