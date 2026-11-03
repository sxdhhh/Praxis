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

# ── R8 运行时保命规则（2026-11-03 release 闪退排查）──────────────────────────
#
# ## 现象
#
# 正式包 7.5MB，装上打开就闪退。debug 包 88MB 正常。
# 两者唯一差别是 release 走了 R8（isMinifyEnabled=true + isShrinkResources=true）。
#
# ## 为什么之前只有 4 行规则还不够
#
# 用一个空白规则跑 R8，等于让 R8 用「最激进」的策略：
#   - 枚举被拆成 int 常量（优化），valueOf/values 消失 -> NoSuchMethodError
#   - Kotlin 元数据被剥 -> 反射拿不到类型信息
#   - Serializable 的 serialVersionUID 变化 -> 反序列化失败
#   - 注解被剥 -> Room / Compose 运行时找不到
#
# 这个项目里有 31 个 enum class，且 Room / Compose / Kotlin 反射大量使用，
# 所以必须显式保留。

# ## 枚举
#
# `proguard-android-optimize.txt` 默认会做「枚举优化」：把 enum 常量内联成 int，
# 并删除 valueOf(String) / values()。但代码里只要有 `EnumType.valueOf(...)`, `values()`,
# 或依赖 name() 做序列化/持久化，运行时就会抛 NoSuchMethodError。
-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ## Kotlin 反射 / 元数据
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeVisibleTypeAnnotations

# ## Room
#
# Room 生成的 *_Impl 通过反射按名字查找，类名不能被混淆。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class androidx.room.RoomDatabase { *; }

# ## Serializable / Parcelable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ## Shizuku（通过 Class.forName 反射）
#
# AndroidHostShellPort / CapabilityDetector 里用 Class.forName("rikka.shizuku.Shizuku")
# 再 getMethod("pingBinder")。R8 看不到这条路，会把符号改名或删掉。
-dontwarn rikka.shizuku.**
-keep class rikka.shizuku.** { *; }

# ## JNI native 方法
#
# Pty.kt 里的 external fun 由 libpty.so 按符号名查找。
# 类名/方法名被混淆后符号对不上，所以整个含 native 方法的类都要保留。
-keepclasseswithmembernames class * {
    native <methods>;
}

# ## Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ## Kotlin 协程
-dontwarn kotlinx.coroutines.**
