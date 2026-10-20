plugins {
    id("praxis.kotlin.jvm")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    // SkillSource 是 suspend 的，测试要用 runTest。
    // （core:memory / core:workflow 也踩过同一个坑：只要模块里有 suspend 端口，
    //   就必须显式加 coroutines-test，Kotlin JVM 插件不会自带。）
    testImplementation(libs.kotlinx.coroutines.test)
}
