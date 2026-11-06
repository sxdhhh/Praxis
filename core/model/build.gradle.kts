plugins {
    id("praxis.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // 角色卡的酒馆（SillyTavern）JSON 导入导出。
    //
    // 放在 core:model 而不是 app 层：转换逻辑是**纯字段映射**，
    // 没有 Context、没有 Room、没有 Flow。留在这里就能用纯 JVM 单测
    // 锁住「V1/V2 解析」「往返保真」「世界书拼装」这些容易出错的规则，
    // 而不是等装了 APK 才能验证。
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // R14：测试里用了 runTest / TestDispatcher。本模块的测试从未在 CI 上编译过
    // （testDebugUnitTest 匹配不到 praxis.kotlin.jvm 模块的 test 任务），
    // 所以缺这个依赖一直没被发现。
    testImplementation(libs.kotlinx.coroutines.test)
}
