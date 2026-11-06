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

# ── 经典 Android 版 miuix（third_party/miuix 1.0.13.0）★ 实测闪退根因（2026-11-04）──
#
# ## 真机崩溃证据（Redmi 25053RT47C / Android 17）
#
#   FATAL EXCEPTION: main
#   java.lang.RuntimeException: Unable to start activity ComponentInfo{dev.praxis/
#       com.sevtinge.hyperceiler.provision.activity.DefaultActivity}
#   Caused by: android.view.InflateException: Binary XML file line #24 in
#       dev.praxis:layout/miuix_appcompat_group_buttons_layout
#   Caused by: java.lang.reflect.InvocationTargetException
#       at android.view.LayoutInflater.createView(LayoutInflater.java:757)
#       at dm.addGroupButtons(...)
#       at com.sevtinge.hyperceiler.provision.activity.DefaultActivity.onCreate(...)
#
#   并且：
#   java.lang.NoSuchMethodError: No static method spring(FF)
#       Lfan/animation/utils/EaseManager$EaseStyle; in class Lfan/animation/utils/EaseManager;
#   Rejecting re-init on previously-failed class java.lang.Class<vh>
#
# ## 因果链
#
#   引导页加载 miuix 的 XML 布局
#     -> 布局里按「类名字符串」反射创建自定义 View
#     -> 该 View 的 <clinit> 调用 EaseManager.spring(float, float)
#     -> R8 判定 spring 无人引用，把它删了
#     -> 类初始化失败 -> NoClassDefFoundError
#     -> 布局 inflate 失败 -> DefaultActivity 起不来 -> 闪退
#
# ## 为什么静态分析看不到
#
#   1. XML 里的类名是**字符串**，R8 不做资源/字符串解析
#   2. miuix 内部大量经过 folme 动画框架做动态调用
#   3. third_party/miuix 下的 AAR 都**不带** consumer ProGuard 规则
#
# 所以整包保留 fan./miui. 不是偷懒 —— 它们的入口无法静态枚举。
# 代价是包体变大（实测 7.5MB -> 9.3MB，可接受），换来的是能打开。

-keep class fan.** { *; }
-dontwarn fan.**
-keep class miui.** { *; }
-dontwarn miui.**

# 引导模块（HyperCeiler 的 provision）同样用 XML 布局 + 自定义 View
-keep class com.sevtinge.** { *; }
-dontwarn com.sevtinge.**

# 兜底：XML 里引用的自定义 View 必须保留三个标准构造器与 setter。
# 即使以后换别的 View 库，这条也能挡住同类问题。
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
    public void set*(...);
    public void get*(...);
}

# XML 里出现的枚举（如 app:xxx="value"）走 valueOf，不能被优化掉
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# ── SLF4J 绑定类（2026-11-06，release 构建暴露）─────────────────────
#
# ## 症状
#
#   ERROR: R8: Missing class org.slf4j.impl.StaticLoggerBinder
#   ERROR: R8: Missing class org.slf4j.impl.StaticMDCBinder
#
# ## 原因
#
# Apache FtpServer（第 6 项的进程内 FTP 服务）依赖 slf4j-api 1.7.x。
# 那一版的 LoggerFactory / MDC 用 **反射** 在运行时查找绑定实现
# （StaticLoggerBinder / StaticMDCBinder），编译期并不存在对这些类的直接引用。
#
# 我们在 build.gradle.kts 里排除了 slf4j-simple（它与项目已有的 slf4j 绑定冲突），
# 于是 R8 做静态分析时找不到这两个类，直接判失败。
#
# ## 为什么用 -dontwarn 而不是补一个绑定
#
# 项目本身已经有 slf4j 绑定（Timber 那条链），这里缺的只是 **1.7 那一版的
# 老式绑定入口**。补一个 slf4j-simple 回来会变成「两个绑定」——那才是真正的
# 运行时问题（启动时告警 + 行为不确定）。
#
# 所以正解是：告诉 R8「这些是反射目标，缺失是预期内的」。
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder
-dontwarn org.slf4j.impl.StaticMarkerBinder

# ## 可选集成带来的缺失类（2026-11-06 修，CI release 实测报错）
#
# 上一次我写了 `-keep class org.apache.ftpserver.**` —— **那条规则本身是错的**。
# 它把 `org.apache.ftpserver.config.spring.*` 也保住了，逼 R8 去解析那些类里对
# Spring 的引用，于是报出：
#
#   Missing class org.springframework.beans.factory.FactoryBean
#   Missing class org.springframework.beans.factory.support.AbstractBeanDefinition
#   Missing class org.springframework.context.support.FileSystemXmlApplicationContext
#
# 同理 `mina-core` 的代理处理器引用 GSSAPI / SASL（只在 SOCKS5 认证时需要）：
#
#   Missing class org.ietf.jgss.GSSContext / GSSManager / GSSCredential / GSSName
#   Missing class javax.security.sasl.AuthenticationException
#
# 这些**都是可选集成**，我们的进程内 FTP 一个都不用：
#  - Spring 配置：我们用 Java API 构造 FtpServerFactory，从不读 XML 配置；
#  - SOCKS5 代理：FTP 控制连接不经代理。
#
# 所以正解是「排除 + 不警告」，而不是「全部保留」。
# 保留的代价不只是包变大 —— 是让 R8 必须解析一整条用不到的依赖链。
-dontwarn org.springframework.**
-dontwarn javax.security.sasl.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.mina.proxy.**
-dontwarn org.apache.ftpserver.config.spring.**
-dontwarn org.apache.ftpserver.main.**

# ## 只保留真正需要的部分
#
# FtpServerFactory / ListenerFactory / BaseUser 这些是**我们直接调用**的，
# 不是反射目标，R8 自然会保留它们引用的类。
#
# 唯一需要显式保留的是 `ftplet` 的接口与实现方：FtpServer 在运行时通过它们
# 回调（`Authority` / `UserManager`）。不保留的话，R8 可能把实现类裁掉，
# 表现为「启动正常，一登录就崩」。
-keep interface org.apache.ftpserver.ftplet.** { *; }
-keep class * implements org.apache.ftpserver.ftplet.Authority { *; }
