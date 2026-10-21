package dev.praxis.core.data.log

import timber.log.Timber

/**
 * 把 Timber 的调用转发到 [PraxisLogger]（即「落在 logcat + 落盘」两条腿都走）。
 *
 * ## 为什么不让业务代码直接调 [PraxisLogger]
 *
 * 因为全项目已经有 2 处 `Timber.` 调用，且 `Timber.Tree` 是「一处 plant、处处生效」的
 * 标准门面。业务代码继续写 `Timber.i(...)`，落盘行为是 **plant 这个 Tree 之后自动获得**的，
 * 不需要去改几十个调用点。
 *
 * ## 为什么 DebugTree 还要留着
 *
 * `Timber.DebugTree` 会自己解析调用栈、把 **真实调用类名** 当 TAG。
 * 本 Tree 拿到的 [tag] 参数在多数调用里是 null（业务代码没传），
 * 所以这里补一次栈解析 —— 否则日志页里所有条目都是 `null: 消息`，等于没有来源信息。
 * 解析代价只在日志调用时付一次，且 `Timber.plant` 已保证线程安全。
 *
 * 只在 DEBUG 里额外解析栈：release 里为了省 CPU 用固定 TAG。
 */
class PraxisTimberTree(
    private val resolveCallerTag: Boolean = true,
) : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean = true

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = priority.toLevelChar()
        val caller = tag ?: syntheticTag(resolveCallerTag)
        if (t != null) {
            PraxisLogger.log(level, caller, message, t)
        } else {
            PraxisLogger.log(level, caller, message, null)
        }
    }

    /**
     * 从栈里找一个「业务类」当 tag。
     *
     * ## 必须白名单自己的包名，不能只做黑名单
     *
     * 第一版做成了「跳过本类 / Timber / PraxisLogger，取第一个剩下的」，
     * 实测落到 `VMStack` —— 因为 `Thread.currentThread().stackTrace` 内部
     * 经过 `dalvik.system.VMStack.getThreadStackTrace`，它是**第一个不属于
     * 黑名单**的帧。真机上日志页每一条的来源都显示成 `VMStack`，
     * 等于来源信息完全失效。
     *
     * 所以改成白名单：只接受应用自身包名下的帧。框架帧（`java.*`、`dalvik.*`、
     * `androidx.*`、`kotlinx.*`、`timber.*`）一律不认，找不到就返回 null，
     * 让 UI 如实显示「没有来源」，不编一个错的。
     */
    private fun syntheticTag(enabled: Boolean): String? {
        if (!enabled) return null
        return Thread.currentThread().stackTrace
            .firstOrNull { frame ->
                val name = frame.className ?: return@firstOrNull false
                // 白名单：只要应用自己的包。
                name.startsWith(APP_PACKAGE_PREFIX) &&
                    // 但排除日志器自身这两层，否则每条都记成 `PraxisTimberTree`。
                    name != SELF_CLASS && name != LOGGER_CLASS
            }
            ?.className
            // 匿名 lambda 的类名形如 `dev.praxis.X$foo$1`，取外层 `X`。
            ?.substringAfterLast('.')
            ?.substringBefore('$')
            ?.takeIf { it.isNotBlank() }
    }

    private companion object {
        /** 应用自身包名前缀；只有这些帧才算「业务调用方」。 */
        const val APP_PACKAGE_PREFIX = "dev.praxis."

        const val SELF_CLASS = "dev.praxis.core.data.log.PraxisTimberTree"

        const val LOGGER_CLASS = "dev.praxis.core.data.log.PraxisLogger"
    }

    private fun Int.toLevelChar(): Char = when (this) {
        android.util.Log.VERBOSE -> 'V'
        android.util.Log.DEBUG -> 'D'
        android.util.Log.INFO -> 'I'
        android.util.Log.WARN -> 'W'
        android.util.Log.ERROR -> 'E'
        android.util.Log.ASSERT -> 'A'
        else -> 'I'
    }
}