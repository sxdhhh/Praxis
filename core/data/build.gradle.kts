plugins {
    id("praxis.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.praxis.core.data"
}

dependencies {
    // 网关领域模型（AccountView 等）。方向：data -> 领域，无环。
    implementation(project(":core:gateway"))
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
