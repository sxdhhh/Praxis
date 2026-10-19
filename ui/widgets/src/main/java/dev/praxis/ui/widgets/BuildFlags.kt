package dev.praxis.ui.widgets

/**
 * 构建类型标记（library 模块没有 BuildConfig，由 :app 在启动时注入）。
 *
 * 为什么不直接读 `android.os.Build`：那是「系统版本」，不是「App 构建类型」。
 * 为什么不用反射读 :app 的 BuildConfig：跨模块反射脆弱且 R8 会改名。
 * 所以用一个可写单例，:app 的 Application.onCreate 里设一次即可。
 */
object BuildFlags {

    /** 默认 false；debug 包在 PraxisApplication 里会置 true。 */
    @Volatile
    var isDebug: Boolean = false
}

/** 便捷读法，供 .kt 内部直接调用。 */
internal fun isDebugBuild(): Boolean = BuildFlags.isDebug
