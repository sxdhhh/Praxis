package dev.praxis

import android.app.Application
import dev.praxis.core.data.log.PraxisLogger
import dev.praxis.core.data.log.PraxisTimberTree
import dev.praxis.di.appModule
import dev.praxis.di.networkModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

/**
 * 应用入口。
 *
 * ## 初始化顺序有要求，改前先读这段
 *
 * 1. **先 init 日志器，再 plant Timber**：反过来会让「第一个 Tree 里发出的日志」
 *    落不到盘（`init` 之前 `resolveLogsDir()` 返回 null，记录直接丢）。
 * 2. **再 startKoin**：DI 构建期出错属于最需要落盘的那类问题，必须已经在收集。
 * 3. 最后打第一条日志：看到它说明整条链路（logcat + 文件）都通了。
 *    它是日志页的自检信号。
 *
 * ## 为什么 Timber 只 plant 一个 Tree
 *
 * `PraxisTimberTree` 内部已同时写 logcat 与文件。若再 plant `Timber.DebugTree`，
 * 每条日志会走两遍 logcat（重复行），而文件侧只写一次 ——
 * 表现为「logcat 两条、日志页一条」，排查时会误以为丢日志。
 * DebugTree 的额外价值（调用栈解析）已在本项目 Tree 里实现。
 */
class PraxisApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        PraxisLogger.init(this)
        Timber.plant(PraxisTimberTree(resolveCallerTag = BuildConfig.DEBUG))

        startKoin {
            androidContext(this@PraxisApplication)
            modules(appModule, networkModule)
        }

        // 自检信号：日志页能看到这一行，说明写入链路完整。
        // 用 I 级别而不是 D：release 也保留，用户反馈问题时第一屏就有信息。
        Timber.i(
            "Praxis started, baseUrl=%s, debug=%s",
            BuildConfig.DEFAULT_BASE_URL,
            BuildConfig.DEBUG,
        )
    }
}
