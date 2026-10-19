plugins {
    id("praxis.kotlin.jvm")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
