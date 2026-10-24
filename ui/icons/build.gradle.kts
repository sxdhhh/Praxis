plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.ui.icons"
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    // PraxisIcons 当前以 Material outlined 为载体（矢量图与 miuix 的实心风格
    // 通过统一的 24dp / 线宽参数对齐）。这条依赖是本模块**唯一**的图标来源，
    // 换载体（改用 miuix 图标）只动 PraxisIcons.kt 一个文件。
    api(libs.compose.material.icons.extended)

    testImplementation(libs.junit)
}
