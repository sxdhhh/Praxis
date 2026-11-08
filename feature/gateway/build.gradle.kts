plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.feature.gateway"
    buildFeatures { compose = true }
}

dependencies {
    // 与其它 feature 模块同向：只依赖 ui 层与 core 的纯契约。
    api(project(":ui:widgets"))
    api(project(":ui:navigation"))
    api(project(":ui:design"))
    implementation(project(":core:model"))
    // 网关领域模型与纯逻辑（路由引擎、状态推导、导入导出）。
    implementation(project(":core:gateway"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
}
