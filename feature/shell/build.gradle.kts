plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.feature.shell"
    buildFeatures { compose = true }
}

dependencies {
    // 根目的地 = 底栏 + Pager + 每页自己的 Scaffold。
    // 它消费 ui 层的三件套，但不消费任何 feature —— 四个 Tab 的内容
    // 由 :app 以 slot 传进来（见 MainShellPage 的签名）。
    //
    // 这一点很重要：若本模块直接 api(project(":feature:chat"))，
    // 就变成「壳依赖业务」，而壳的职责恰恰是不知道业务是什么。
    api(project(":ui:navigation"))
    api(project(":ui:design"))
    api(project(":ui:widgets"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    api(libs.miuix.icons)

    testImplementation(libs.junit)
}