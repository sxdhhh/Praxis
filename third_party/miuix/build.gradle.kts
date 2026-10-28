plugins {
    id("praxis.android.library")
}

// `fan.miuix:*:1.0.13.0` 的本地 Maven 仓库载体。
//
// ## 为什么需要这个模块，而不是直接写坐标
//
// 这 23 个 AAR 只发布在 `maven.pkg.github.com/ReChronoRain/HyperCeiler`，
// 且 GitHub Packages 的 Maven 网关要求「对承载仓库有读权限」——
// 非 ReChronoRain 组织成员即使 package visibility=public 也拿不到
// （实测无 token 401、有 token 429，`/rate_limit` 却显示配额正常，
// 429 是伪装错误）。CI 环境没有凭证，必然失败。
//
// 因此把 AAR 落到仓库内 `third_party/maven/`，用本地 Maven 仓库坐标引用，
// 构建完全离线、可复现、不依赖任何令牌。
//
// ## 版本与来源
//
// 全部来自 HyperCeiler 2.10.166 发布时使用的 `fan.miuix:1.0.13.0`，
// 与上游 `gradle/libs.versions.toml` 的 `miuix = "1.0.13.0"` 一致。
//
// ## 依赖闭包已闭合
//
// 23 个 artifact 与它们的 pom 互相引用后形成的闭包**恰好是这 23 个**，
// 无遗漏、无多余（脚本核对：pom 中出现的 fan.miuix artifactId 去重后
// 与本地已有集合完全相等）。上游 `libs.bundles.miuix` 声明的 16 个是
// 直接依赖，另外 7 个（`flexible` / `graphics` / `popupwidget` /
// `slidingwidget` / `smooth` / `stretchablewidget` / `viewpager2`）
// 是通过 pom 传递进来的，这里一并显式声明，保证 `api` 语义透传。
android {
    namespace = "fan.miuix.bundle"
}

dependencies {
    api("fan.miuix:animation:1.0.13.0")
    api("fan.miuix:appcompat:1.0.13.0")
    api("fan.miuix:basewidget:1.0.13.0")
    api("fan.miuix:bottomsheet:1.0.13.0")
    api("fan.miuix:cardview:1.0.13.0")
    api("fan.miuix:core:1.0.13.0")
    api("fan.miuix:folme:1.0.13.0")
    api("fan.miuix:navigator:1.0.13.0")
    api("fan.miuix:nestedheader:1.0.13.0")
    api("fan.miuix:pickerwidget:1.0.13.0")
    api("fan.miuix:preference:1.0.13.0")
    api("fan.miuix:recyclerview:1.0.13.0")
    api("fan.miuix:springback:1.0.13.0")
    api("fan.miuix:theme:1.0.13.0")
    api("fan.miuix:transition:1.0.13.0")
    api("fan.miuix:viewpager:1.0.13.0")

    // 以下 7 个在上游是 pom 传递进来的。显式声明是为了让 Gradle 的
    // 变体解析不依赖 pom 元数据（本地仓库没有 Gradle Module Metadata，
    // 只靠 pom 时传递依赖仍能解析，但显式声明更稳）。
    api("fan.miuix:flexible:1.0.13.0")
    api("fan.miuix:graphics:1.0.13.0")
    api("fan.miuix:popupwidget:1.0.13.0")
    api("fan.miuix:slidingwidget:1.0.13.0")
    api("fan.miuix:smooth:1.0.13.0")
    api("fan.miuix:stretchablewidget:1.0.13.0")
    api("fan.miuix:viewpager2:1.0.13.0")

    // ## 为什么还要单独挂两个 jar
    //
    // `fan.miuix:animation` 与 `fan.miuix:recyclerview` 这两个 AAR 采用
    // **fat-aar 打包**：真正的类在 `libs/*.jar` 里，`classes.jar` 是空壳
    // （animation 的 classes.jar 只有 22 字节）。
    //
    // AGP 只把 AAR 的 `classes.jar` 放进编译类路径，`libs/` 目录里的 jar
    // **不会自动参与编译** —— 它们是给「从源码构建该 AAR 时的本地依赖」
    // 用的。实测：provision 里 `import fan.animation.Folme` 等 9 个符号
    // 在 23 个 AAR 的 `classes.jar` 中全部找不到，但 100% 在
    // `animation/libs/miuix-animation.jar`（385 个类）里。
    //
    // 所以把这两个 jar 抽到 `third_party/jars/` 并用 `api(files(...))` 挂载。
    // 用 `api` 而非 `implementation`：`provision` 会直接 import
    // `fan.animation.*`，属于「直接使用」，必须透传。
    api(files("${rootProject.projectDir}/third_party/jars/animation-miuix-animation.jar"))
    api(files("${rootProject.projectDir}/third_party/jars/recyclerview-miuix-core.jar"))
}