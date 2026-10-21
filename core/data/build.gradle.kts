plugins {
    id("praxis.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.praxis.core.data"
}

dependencies {
    api(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.timber)
    implementation(libs.androidx.datastore.preferences)

    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
