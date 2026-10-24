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
    // 底栏图标（PraxisBottomBar 从 :app 迁入后需要）。
    api(project(":ui:icons"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    api(libs.miuix.icons)
    api(libs.miuix.navigation)
    // 底栏玻璃（BottomBarGlass.kt）直接引用 miuix-blur 的 Backdrop/blur/drawBackdrop。
    // 这条原来声明在 :app，底栏迁入本模块后必须跟着搬 —— 否则靠 design 的
    // api 传递虽然也能编过，但那是隐式依赖，design 一改就断。
    api(libs.miuix.blur)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
