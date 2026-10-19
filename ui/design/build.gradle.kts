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

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
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
