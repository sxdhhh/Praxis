# Praxis 保留 Room / Compose 反射所需符号
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class dev.praxis.data.db.** { *; }
-dontwarn org.jetbrains.annotations.**

# ── 第三方 / 系统私有 API 的反射引用（2026-11-03 首次 release 构建暴露）────────────────
#
# ## 为什么直到 release 构建才报错
#
# debug 变体 `isMinifyEnabled = false`，**不跑 R8**，所以这些引用从来没被检查过。
# release 一旦开 minify，R8 会做「缺失类检测」，把它们全部报出来：
#
#   ERROR: R8: Missing class com.android.internal.view.menu.MenuBuilder
#     (referenced from: void fan.appcompat.internal.view.menu.MenuBuilder.<init>(...))
#   Missing class miui.util.HapticFeedbackUtil
#     (referenced from: fan.utils.HapticFeedbackCompat.hapticFeedbackUtil)
#
# ## 这些类为什么「缺失」却又是对的
#
# 它们都是**设备厂商 / 系统内部 API**：
#   - `miui.util.HapticFeedbackUtil` 只存在于 MIUI 系统里
#   - `com.android.internal.view.menu.MenuBuilder` 是 AOSP internal，不在公开 SDK 里
#
# App 通过**反射**访问它们（拿不到就降级），所以编译期不需要它们存在。
# R8 的静态分析看不到反射，只会看到「引用了但找不到」，于是报错。
#
# `-dontwarn` 告诉 R8：这些缺失是预期的，不要报错。
# 库本身来自 fan.appcompat（第三方兼容层），不是我们的代码，也不该我们改。

-dontwarn com.android.internal.**
-dontwarn miui.util.**
-dontwarn miui.os.**

# 反射调用的成员不能被混淆，否则运行时会 NoSuchMethodException
-keep class miui.util.HapticFeedbackUtil { *; }
