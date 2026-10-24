plugins {
    id("praxis.kotlin.jvm")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // ## 为什么依赖 core:tool
    //
    // 插件系统的最终产物是「工具」。`PluginRuntimeHost` 需要
    // `ToolSpec` / `Tool` / `ToolRegistry` 才能把插件注册的工具挂上去。
    //
    // 用 `api` 而不是 `implementation`：上层（`:app`）拿到
    // `PluginRuntimeHost` 时要能直接看见 `ToolRegistry` 类型，
    // 否则每个调用点都得自己再声明一次依赖。
    api(project(":core:tool"))

    testImplementation(libs.junit)
    // SkillSource 是 suspend 的，测试要用 runTest。
    // （core:memory / core:workflow 也踩过同一个坑：只要模块里有 suspend 端口，
    //   就必须显式加 coroutines-test，Kotlin JVM 插件不会自带。）
    testImplementation(libs.kotlinx.coroutines.test)

    // ## 刻意**不**依赖 kotlinx.serialization
    //
    // 清单是 HJSON（不是 JSON），市场返回的 JSON 字段又极不规范
    // （同一个字段可能是字符串/数组/对象/null）。两种情况都用
    // 手写解析更稳：见 ManifestParser 与 MarketJsonParser 的 KDoc。
    //
    // 加序列化依赖只会让人误以为「加个 @Serializable 就行」，
    // 然后在遇到 null/类型漂移时踩坑。
}
