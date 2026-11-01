plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
    // ## 为什么必须应用 Paparazzi 的 Gradle 插件（2026-11-01 补）
    //
    // 只加 `testImplementation(libs.paparazzi)` 是**不够**的。运行时
    // Paparazzi 的 detectEnvironment() 会读这几个系统属性：
    //
    //     paparazzi.project.dir / paparazzi.build.dir
    //     paparazzi.artifacts.cache.dir / paparazzi.test.resources
    //
    // 它们由本插件在 test task 上注入。缺了插件，System.getProperty 返回
    // null，Paths.get(null) 抛 NPE，5 个预览用例全挂在 <init>。
    // 注释里那句「显式声明 layoutlib 能让依赖解析更早暴露问题」是对的，
    // 但它替代不了插件。
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "dev.praxis.preview"
    buildFeatures { compose = true }
    // ## 为什么关掉 lint
    //
    // 本模块只有测试代码（`src/test`），没有 `src/main` 的产物。
    // lint 会报「没有可分析的主要源集」，那是个噪音错误 —— 而项目
    // 约定 `abortOnError = true`，一条噪音就能让 CI 红。
    lint {
        abortOnError = false
        checkTestSources = false
    }
    testOptions {
        unitTests {
            // Paparazzi 需要访问被测试类的 resources（miuix 的字体/主题）。
            isIncludeAndroidResources = true
            all {
                // ## 为什么必须关掉「无变更跳过」
                //
                // 预览的价值是「改了 UI 就能看到新图」。Gradle 的 up-to-date
                // 判定基于 task 的输入输出，而我们的输入包含 `feature:chat`
                // 的源码 —— 那个依赖是 project 依赖，改动会不会触发
                // 本 task 重跑并不直观。宁可每次都跑（渲染一屏 ~3 秒），
                // 也不要出现「改了代码但图没变」这种最难察觉的假象。
                it.outputs.upToDateWhen { false }
            }
        }
    }
}

dependencies {
    // 预览对象是真实 Composable，所以直接依赖功能模块（`api` 让
    // `ChatScreen` 这类类型在本模块可见）。
    api(project(":feature:chat"))
    api(project(":feature:settings"))
    api(project(":ui:widgets"))
    api(project(":ui:design"))
    api(project(":ui:theme"))
    implementation(project(":core:model"))
    implementation(project(":core:ai"))

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.miuix.core)
    api(libs.miuix.ui)
    implementation(libs.miuix.icons)

    // `ui-tooling-preview`：@Preview 注解本身（渲染由 Paparazzi 负责，
    // 但注解在 tooling-preview 里，不是 tooling）。
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.paparazzi)
    testImplementation(libs.layoutlib)
}
