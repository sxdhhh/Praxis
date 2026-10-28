plugins {
    id("praxis.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.praxis.feature.settings"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":ui:widgets"))
    api(project(":ui:navigation"))
    api(project(":ui:design"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    // 模型列表拉取的端口与纯解析（`ModelCatalog` / `ModelCatalogFetcher`）。
    //
    // ## 为什么 feature:settings 需要 core:ai
    //
    // 「拉取模型」这个动作的**输入是接口地址 + 密钥**，输出是模型 ID 列表 ——
    // 那是 AI 层的知识，不是设置页的知识。真正的 HTTP 实现在 :app
    // （`OkHttpModelCatalogFetcher`），这里只依赖端口 + 纯解析函数。
    implementation(project(":core:ai"))
    // Linux 环境页（Ubuntu 容器 / SSH / FRP）的纯逻辑：配置、镜像表、命令构造。
    implementation(project(":core:linux"))
    implementation(project(":core:tool"))
    implementation(project(":core:memory"))
    implementation(project(":core:workflow"))
    // 扩展包页（装/卸包 + 市场）。只用到 core:plugin 的纯 JVM 契约与解析器，
    // 文件与网络实现仍在 :app（feature 不碰 Android 文件系统）。
    implementation(project(":core:plugin"))

    implementation(libs.androidx.core.ktx)
    // LinuxEnvViewModel 继承 ViewModel（viewModelScope 做「装环境/起会话」的长任务），
    // 页面侧用 viewModel() 取实例。
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    // foundation 直接使用：isSystemInDarkTheme / LazyColumn / BoxWithConstraints。
    // 显式声明而不靠 material3 传递（与 :feature:chat 显式声明 muiix-icons 同理：
    // 上游一旦把传递依赖降级，报错位置会在本模块，难定位）。
    api(libs.compose.foundation)
    api(libs.compose.material3)

    api(libs.miuix.core)
    api(libs.miuix.ui)
    api(libs.miuix.icons)
    api(libs.miuix.preference)
    // 关于页的玻璃（`textureBlur` / `rememberLayerBackdrop` / `BlurColors`）
    // 需要 miuix-blur。本模块此前没有任何地方用它，属于**直接使用的新依赖**，
    // 按本项目纪律必须自己声明 —— 靠 `:ui:design` 的 `api` 传递虽然眼下能编过，
    // 但上游一旦把那条降成 implementation，报错会出现在本模块且很难定位。
    api(libs.miuix.blur)

    testImplementation(libs.junit)
    // LinuxEnvViewModelTest 用 runTest / setMain 驱动 viewModelScope。
    // 不替换 Main 调度器会在纯 JVM 里直接抛「Main dispatcher 未初始化」。
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.coroutines.core)
}
