plugins {
    id("praxis.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // 网关导出文件是**规范 JSON**（字段名由网关定义），与清单那种 HJSON 不同，
    // 所以这里用 kotlinx.serialization 直接映射，而不是手写解析器。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
