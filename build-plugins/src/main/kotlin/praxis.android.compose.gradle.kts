// Compose 模块叠加配置：只开 buildFeatures.compose，不引入插件依赖。
// 使用方需要同时应用 praxis.android.library 与 org.jetbrains.kotlin.plugin.compose。
plugins {
    id("com.android.library")
}

android {
    buildFeatures {
        compose = true
    }
}
