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
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)

    api(libs.material.kolor)

    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui)
}
