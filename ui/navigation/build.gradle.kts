plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.ui.navigation"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":ui:design"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    api(libs.miuix.icons)
    api(libs.miuix.navigation)

    testImplementation(libs.junit)
}
