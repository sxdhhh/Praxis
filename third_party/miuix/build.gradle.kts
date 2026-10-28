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
    // `haptic` 是 `folme` 的 pom 依赖（震动反馈，Folme 触感动画要用）。
    //
    // 上一轮漏了它没被发现，因为本机 ~/.gradle 里已有缓存；
    // CI 是干净环境，第一次跑就报：
    //   Could not find fan.miuix:haptic:1.0.13.0
    // 教训：**依赖闭包必须用「pom 声明的全集」去比，不能凭调用关系推**。
    api("fan.miuix:haptic:1.0.13.0")

    // ## 关于 animation / recyclerview 的 libs/*.jar
    //
    // `fan.miuix:animation` 与 `fan.miuix:recyclerview` 是 fat-aar：
    // 真正的类在 AAR 内部的 `libs/*.jar` 里（animation 385 个类、
    // recyclerview 65 个类），`classes.jar` 只装各自的少量类。
    //
    // **不要额外用 `api(files(...))` 去挂这两个 jar。**
    //
    // 我第一版这么做过，结果 CI 报：
    //   Duplicate class fan.animation.Folme found in
    //     animation-1.0.13.0.aar  and  animation-miuix-animation.jar
    // 因为 AGP 本来就会把 AAR 内的 `libs/*.jar` 一并放进编译与运行时
    // 类路径（这也是 fat-aar 能工作的前提）。手动再挂一次就是两份。
    //
    // 结论：声明 AAR 坐标即可，`libs/` 里的东西由 AGP 自动带入。
}