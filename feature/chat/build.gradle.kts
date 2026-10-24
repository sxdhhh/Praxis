plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.feature.chat"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":ui:widgets"))
    api(project(":ui:navigation"))
    api(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:ai"))
    implementation(project(":core:tool"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    // 图标：输入栏的发送/停止键要用 MiuixIcons。
    //
    // 之前 :feature:chat 没有这条依赖 —— 它通过 `api(project(":ui:widgets"))`
    // 间接拿到 miuix-icons（ui:widgets 里是 `api(libs.miuix.icons)`）。
    // 但那是**传递**依赖，ui:widgets 一旦把 icons 从 api 降成 implementation，
    // 这里就会突然编不过，且报错位置在 feature:chat，很难立刻想到根因。
    // 本模块直接用到图标，就自己声明。
    implementation(libs.miuix.icons)
    // `miuix-squircle`：只为一个用途引入 —— 在工具卡上局部关闭 squircle。
    //
    // ## 为什么需要它（2026-10-24，性能）
    //
    // 用户报「翻文字丝滑，滑到工具就卡」。根因是 miuix `Card` 内部走
    // `Modifier.squircleSurface(...)`，而它会给每张卡开
    // `CompositingStrategy.Offscreen` 离屏图层 + 跑 AGSL shader 求遮罩。
    // 列表里连续 5~10 张工具卡时，滚动每帧要合成这么多离屏层 → 明显掉帧。
    //
    // 修法是给工具卡局部 `LocalSquircleEnabled provides false`（miuix 官方
    // 降级开关，`SquircleBackground.kt:370` 直接返回 null → 走 clip+background）。
    //
    // ## 为什么必须显式声明而不能靠 miuix-ui 传递
    //
    // `kmp.squircle` 是**独立的 artifact**：`miuix-ui` 内部用了它，但
    // Gradle 的 `implementation` 不会把依赖暴露给下游编译期。
    // 不声明就是 `Unresolved reference: LocalSquircleEnabled`。
    implementation(libs.miuix.squircle)
    // ## 为什么需要 material-icons-extended
    //
    // 1:1 复刻 Operit 的三块控件用到 `VerticalAlignTop`（队列置顶）、
    // `Tag` / `Code`（消息编辑器的 XML 片段卡）。这三个都**不在**
    // `material-icons-core` 里（core 只有几十个最常用图标）：
    // `Icons.Default.VerticalAlignTop` 与 `Icons.Outlined.Tag` 属于 extended。
    //
    // 不引就会报 `Unresolved reference: VerticalAlignTop`，且报错位置在
    // 调用处，很容易误以为是 import 写错。`ui:icons` 与 `:app` 早已引入
    // 同一个库（版本由 compose-bom 裁决），这里补上不增加下载体积。
    implementation(libs.compose.material.icons.extended)

    testImplementation(libs.junit)
    // ChatStageTest 用 runTest / advanceUntilIdle 驱动状态机。
    // 不用 Thread.sleep：那会让「取消后是否真的停下」这类断言变成时间赌博。
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.core)
}
