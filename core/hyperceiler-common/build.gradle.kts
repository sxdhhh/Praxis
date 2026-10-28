plugins {
    id("praxis.android.library")
}

// HyperCeiler `library/common` 的原样搬运。
//
// ## 为什么整包搬，而不是只挑 provision 用到的 6 个类
//
// `provision` 直接 import 的只有 `AndroidLog` / `AppLanguageHelper` /
// `PrefsConfigurator` / `PrefsBridge` / `PermissionUtils` / `AppSettingsStore`，
// 但这 6 个类又引用了 `utils.prefs.PrefType` / `utils.prefs.PrefsChangeObserver`、
// 而后者又引用 `utils.api.ProjectApi` —— 逐个裁剪等于重走一遍依赖闭包，
// 只要漏一个就编译不过。整包只有 31 个源文件 / 4939 行，直接搬更省事，
// 也符合用户「1:1 copy，一点都不能少」的要求。
//
// ## 与上游的差异（只有一处，且不改代码）
//
// 上游 `minSdk = 35`，本项目是 28。已用 `d8 --min-api 28` 实测过全部
// 字节码可以降到 28，这里只改 gradle 配置，源码一字不动。
android {
    namespace = "com.sevtinge.hyperceiler.common"

    defaultConfig {
        buildConfigField("String", "APP_MODULE_ID", "\"com.sevtinge.hyperceiler\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // 上游 `api(libs.bundles.miuix)` 的等价物。用 `api` 是因为
    // `provision` 通过本模块的 `api` 传递拿到 miuix 类型。
    api(project(":third_party:miuix"))

    // 上游 `compileOnlyApi(libs.libxposed.api)`。
    // 只有 `log/XposedLog.kt` 用到 `io.github.libxposed.api.XposedInterface`，
    // 而 `XposedLog` 在 `provision` 里零引用 —— 但仍然保留，因为它是
    // `common` 的公开 API 面的一部分，删掉就破坏了「1:1」。
    compileOnlyApi("io.github.libxposed:api:102.0.0")
}
