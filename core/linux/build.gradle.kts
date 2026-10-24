plugins {
    id("praxis.kotlin.jvm")
}

dependencies {
    // 只依赖 model（ApiProvider 式的纯数据枚举）与协程；不依赖任何 Android 类。
    api(project(":core:model"))
    /**
     * 终端会话的 7 个工具（`TerminalSessionTools.kt`）。
     *
     * ## 为什么工具声明放在 core:linux 而不是 :app
     *
     * 工具的形状（`Tool` / `ToolSpec` / `ToolResult`）来自 `core:tool`，
     * 而「终端会话」的能力来自 `core:linux`。两者都是纯 JVM 契约，
     * 放一起就能在 JVM 单测里用假 `TerminalSessionPort` 跑通全部 7 个工具，
     * 不需要 Robolectric、不需要真机。
     *
     * 若把工具放 `:app`，这 400 行逻辑就只能靠真机验证 —— 那是本项目
     * 已经反复踩过的坑（工具层十几个分支，手工点一遍根本覆盖不到）。
     *
     * ## 依赖方向
     *
     * `core:linux → core:tool → core:model`，单向。
     * 反向（`core:tool` 依赖 `core:linux`）会造成循环，不能加。
     */
    api(project(":core:tool"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}