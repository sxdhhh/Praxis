plugins {
    id("praxis.android.library")
}

// HyperCeiler `library/provision` 的 1:1 搬运（50 java / 74 res / 2 aidl /
// 3 assets / 4 glsl，共 131 个文件，源码一字未改）。
//
// ## 与上游的三处差异，全部在 gradle 配置里，不碰源码
//
// | 项 | 上游 | 这里 | 原因 |
// |---|---|---|---|
// | `minSdk` | 35 | 28 | 本项目全局 28。已用 `d8 --min-api 28` 实测全部
// |            |    |    | 字节码可降级，无 API 33 硬依赖 |
// | Java 工具链 | 25 | 21 | 本项目全局 21；上游 class 文件是 v65(Java 25) |
// |            |    |    | 也兼容，但工具链必须与项目一致 |
// | `libhook` | api 依赖 | 去掉 | provision 对 `libhook` **零引用**
// |            |    |    | （grep `import com.sevtinge.hyperceiler.libhook` 结果为空） |
//
// ## 高版本 API 的处理
//
// 全仓唯一需要降级的是 `renderengine/GlowPainter.java`（`RuntimeShader` API 33+、
// `RenderEffect` API 31+）与 `renderengine/GlowController.java`（`setRenderEffect`）。
// 这两个文件里的用法由 `RenderCompat` 做符号隔离，见该文件头部注释。
// 其余 47 个 java 文件只有 `MarkdownView` 里一处 `SDK_INT >= P`，天然安全。
android {
    namespace = "com.sevtinge.hyperceiler.provision"

    buildFeatures {
        // 两个 aidl（`IAnimCallback` / `IProvisionAnim`）用于跨进程动画回调。
        aidl = true
    }

    // 上游 `keepRules/rules.keep` 是给宿主 app 的 consumer proguard 规则。
    defaultConfig {
        consumerProguardFiles("keepRules/rules.keep")
    }

    sourceSets {
        getByName("main") {
            // 上游把 keepRules 放在 src/main 之外，这里保持一致但显式声明。
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // 上游 `api(projects.library.common)`。
    api(project(":core:hyperceiler-common"))

    // miuix 通过 common 的 `api` 已经传递过来，但 provision 的 res 里
    // 直接引用了 `fan.miuxi` 的主题与 style，编译期需要 R 符号 —— 显式声明。
    api(project(":third_party:miuix"))

    // ## provision 直接 import 的 androidx 类型
    //
    // 按本项目纪律「直接使用就必须显式声明」（不能靠 AAR 的 pom 传递）：
    //
    // | import | 来自 |
    // |---|---|
    // | `androidx.fragment.app.*`（18 处，Fragment/FragmentActivity/ListFragment） | `androidx.fragment` |
    // | `androidx.recyclerview.widget.RecyclerView` | `androidx.recyclerview` |
    // | `androidx.activity.OnBackPressedCallback` / `result.*` | `androidx.activity` |
    // | `androidx.core.app.ActivityOptionsCompat` / `content.ContextCompat` | `androidx.core` |
    // | `androidx.preference.Preference` / `SwitchPreference` | **不需要单独声明** |
    //
    // 最后一条特殊：`androidx.preference.Preference` 是
    // `fan.miuix:preference` AAR **自己带进来的同名类**（实测在其
    // `classes.jar` 里确实有 `androidx/preference/Preference.class`），
    // 是 miuix 魔改版。若再从 Maven 拉官方 `androidx.preference`，
    // 会出现同名类冲突（两个 jar 都含 `androidx.preference.Preference`），
    // D8 会报 duplicate class。所以走 AAR 自带的那份。
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity)
    api(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
}