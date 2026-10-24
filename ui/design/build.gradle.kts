plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.ui.design"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":ui:theme"))
    // 引导页的 hero 图标必须与关于页**同一个 ImageVector**（`PraxisIcons.Logo`）。
    //
    // ## 为什么加这条依赖而不是在引导页里另画一份 path
    //
    // 关于页 `AboutScreen` 的玻璃 logo 是 `PraxisIcons.Logo` + `textureBlur(DstIn)`。
    // 若引导页自己再画一个「P」形，两处立刻会漂移（描边粗细、留白、
    // viewport 都不同），而用户刚看到引导、紧接着看到关于页，
    // 差异会被直接察觉。用户本轮的要求就是「关于页同款图标」，
    // 同款的前提是**同一份矢量**。
    //
    // ## 依赖方向安全
    //
    // `:ui:icons` 只依赖 androidx（见其 build.gradle.kts），不反向依赖
    // `:ui:design`，因此不会形成环。`:ui:design` 用 `api` 暴露它，
    // 上层（`:app`）已经通过 feature 模块拿到 icons，不会重复。
    api(project(":ui:icons"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    // AutoFollowScroll 直接使用 foundation 的 ScrollState / LazyListState /
    // dispatchRawDelta，所以本模块必须自己声明（不能靠 material3/miuix 传递）。
    api(libs.compose.foundation)
    // `AnimatedFluidBackground`（引导页低版本流光）用 `animateColor`，
    // 它在 `androidx.compose.animation:animation`，而 foundation 只传递
    // `animation-core`。属于**直接使用**，按项目纪律必须自己声明。
    api(libs.compose.animation)
    // AutoFollowScroll 的弹簧循环用 `MutableStateFlow` 做「内容变更信号」，
    // `first { }` 做挂起等待。flow 属于 coroutines，同样是**直接使用**，
    // 按本项目纪律必须自己声明，不能靠 compose-runtime 传递。
    api(libs.kotlinx.coroutines.core)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    api(libs.miuix.icons)
    api(libs.miuix.blur)
    api(libs.miuix.shader)

    api(libs.kyant.backdrop)
    api(libs.kyant.shapes)

    testImplementation(libs.junit)
}
