plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.ui.theme"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":core:model"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    // PraxisTheme 使用 foundation 的 isSystemInDarkTheme，显式声明。
    api(libs.compose.foundation)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)

    // M3 色板不再自己算：`PraxisTheme` 从 `MiuixTheme.colorScheme` 直接映射
    // 出 `ColorScheme`（见 `rememberM3FromMiuix`）。所以**不需要**
    // materialKolor —— 它曾经是声明了却从未调用过（全仓零命中），
    // 上一轮曾短暂用它给 M3 派生 seed，但两家算法不同、色阶仍不同族，
    // 与「M3 取色 miuix」的目标相反，已移除。
    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui)
}
