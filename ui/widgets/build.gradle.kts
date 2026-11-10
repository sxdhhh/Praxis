plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.ui.widgets"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":ui:design"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    // foundation 直接使用：Canvas / BoxWithConstraints / clickable / Row.weight。
    // ui:theme 已 api 声明，但本模块自己直接用，就自己声明 ——
    // 上游 ui:theme 一旦把 foundation 降成 implementation，报错会落在本模块。
    api(libs.compose.foundation)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    api(libs.miuix.icons)
    api(libs.miuix.preference)
  // H5 容器（文档 §9.10）。文档明写「不要自己写 AndroidView { WebView } 包一层」，
  // 所以容器本体用库，本模块只留宿主壳（进度/错误/返回键/回调拦截）。
  implementation(libs.compose.webview)

    testImplementation(libs.junit)
}
