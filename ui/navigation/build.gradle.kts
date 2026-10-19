plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.praxis.ui.navigation"
    buildFeatures { compose = true }
}

dependencies {
    // 路由契约要解析 PraxisDomain。design 仍由这里 api 出去，
    // 上层只声明 navigation 就能同时拿到 design。
    api(project(":core:model"))
    api(project(":ui:design"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    api(libs.miuix.icons)
    api(libs.miuix.navigation)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
