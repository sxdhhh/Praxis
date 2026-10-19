plugins {
    id("praxis.kotlin.jvm")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
