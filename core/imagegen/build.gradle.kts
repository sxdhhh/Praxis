plugins {
    id("praxis.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // 引擎契约是**规范 JSON**（字段名由引擎定义，一个都不能改），所以用 kotlinx.serialization
    // 直接映射，而不是手写解析 —— 取舍与 :core:gateway 完全一致。
    //
    // ## 为什么是 implementation 而不是 api
    //
    // 本模块对外只交出 String（ImageGenRequest.toJson）与 ByteArray（complete 的图字节），
    // kotlinx.serialization 的 JsonObject / JsonPrimitive 不会漏进调用方的编译期。
    implementation(libs.kotlinx.serialization.json)
    // 生图进度要走 StateFlow 给聊天层订阅（§6.1：20~60 秒没有反馈，用户会以为卡死）。
    // api 而不是 implementation：`ImageGenProgressBus.state` 的类型 StateFlow 会出现在
    // 调用方的编译期（:app 与 :feature:chat 都要读它）。
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
