plugins {
    id("praxis.kotlin.jvm")
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // R14：测试里用了 runTest / TestDispatcher。本模块的测试从未在 CI 上编译过
    // （testDebugUnitTest 匹配不到 praxis.kotlin.jvm 模块的 test 任务），
    // 所以缺这个依赖一直没被发现。
    testImplementation(libs.kotlinx.coroutines.test)
}
