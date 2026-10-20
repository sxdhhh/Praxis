plugins {
    id("praxis.kotlin.jvm")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    // 写入管线是 suspend 的，测试要用 runTest（结构并发、不 sleep）。
    testImplementation(libs.kotlinx.coroutines.test)
}
