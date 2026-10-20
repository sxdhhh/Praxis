plugins {
    id("praxis.kotlin.jvm")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    // 引擎 run() 是 suspend 的，测试要用 runTest（不用 Thread.sleep）。
    testImplementation(libs.kotlinx.coroutines.test)
}
