plugins {
    id("praxis.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // 网关导出文件是**规范 JSON**（字段名由网关定义），与清单那种 HJSON 不同，
    // 所以这里用 kotlinx.serialization 直接映射，而不是手写解析器。
    // 文档 10：core:gateway 可以依赖 core:model 与 core:ai。
    // 我们要把「AI 错误 -> 失败分类」的映射放在可单测的领域层。
    api(project(":core:ai"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
