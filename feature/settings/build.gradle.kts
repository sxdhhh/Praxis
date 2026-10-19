plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.feature.settings"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":ui:widgets"))
    api(project(":ui:navigation"))
    api(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:tool"))

    implementation(libs.androidx.core.ktx)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    api(libs.miuix.icons)
    api(libs.miuix.preference)

    testImplementation(libs.junit)
}
