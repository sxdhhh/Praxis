plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.feature.terminal"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":ui:widgets"))
    api(project(":ui:navigation"))
    api(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:terminal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)

    testImplementation(libs.junit)
}
