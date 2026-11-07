package dev.praxis.core.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 装载时需要的包来源。
 *
 * ## 为什么单独抽一个接口
 *
 * 编排逻辑（跳过停用包、读归档、装载、汇总失败）是纯逻辑，应该在 JVM 上单测；
 * 而 [PluginRepository] 需要真实的包目录。抽一个只有两个方法的接口，
 * [PluginRepository] 直接实现它即可，测试用内存实现。
 *
 * 不直接用 PluginRepository 还有一个理由：编排只需要「列出」和「打开」，
 * 另外五个方法（install/remove/setEnabled…）会让测试被迫造一堆无关状态。
 */
interface PluginSource {
    fun scanAll(): List<InstalledPlugin>
    fun openArchive(packageId: String): PluginArchive?
}

/** 一次启动装载的结果。 */
data class ActivationReport(
    /** 成功装载的包 id。 */
    val loaded: List<String> = emptyList(),
    /** 装载出错的包。 */
    val failed: List<Failure> = emptyList(),
    /** 跳过（停用或有致命问题）的包。 */
    val skipped: List<String> = emptyList(),
) {
    data class Failure(
        val packageId: String,
        /** 逐条原因。失败必须说清为什么，不允许空列表。 */
        val errors: List<String>,
    )

    val isEmpty: Boolean get() = loaded.isEmpty() && failed.isEmpty() && skipped.isEmpty()
}

/**
 * 启动装载：把所有启用的包装进运行时。
 *
 * ## 为什么要有这一步
 *
 * 插件的 UI 入口**只存在于运行时注册**（清单里根本没有这个字段 —— 见
 * [collectEntryPoints] 的说明）。包不装载，运行时里就没有它的入口，
 * 点开只会得到「运行时已释放」。
 *
 * ## 一个包坏了不影响别的
 *
 * 任一环节失败（读不到归档、清单坏了、脚本抛异常）都只记录到这个包的
 * 失败里，继续装下一个。用户装十个包，不该因为其中一个坏掉就全部打不开。
 *
 * ## 没有「静默跳过」
 *
 * 每个包要么进 [ActivationReport.loaded]，要么进 [ActivationReport.failed]
 * 并带原因，要么进 [ActivationReport.skipped]。不存在「装没装不知道」。
 */
class PluginActivation(
    private val source: PluginSource,
    private val host: PluginRuntimeHost,
) {

    /**
     * 最近一次装载结果。null 表示还没跑过。
     *
     * ## 为什么是 StateFlow 而不是返回值就够了
     *
     * 装载发生在应用启动时，入口列表页可能先组合出来。静态返回值的做法是
     * 「先读一次快照」—— 装载完成时页面已经渲染过了，用户看到的是空列表，
     * 要等下一次重组才更新。界面必须能观察到「装完了」这个事件。
     */
    private val _report = MutableStateFlow<ActivationReport?>(null)
    val report: StateFlow<ActivationReport?> = _report.asStateFlow()

    suspend fun activateAll(): ActivationReport {
        val loaded = mutableListOf<String>()
        val failed = mutableListOf<ActivationReport.Failure>()
        val skipped = mutableListOf<String>()

        // 按 id 排序：装载顺序会影响工具注册顺序，稳定顺序让问题可复现。
        val plugins = source.scanAll().sortedBy { it.id }

        for (plugin in plugins) {
            if (!plugin.isActive) {
                // 停用或有致命问题。后者用户能在列表里看到原因，这里只记账。
                skipped += plugin.id
                continue
            }

            val archive = source.openArchive(plugin.id)
            if (archive == null) {
                failed += ActivationReport.Failure(
                    packageId = plugin.id,
                    errors = listOf("读不到安装目录（包可能已被删除）"),
                )
                continue
            }

            val contentResult = try {
                PluginArchiveReader.read(archive)
            } finally {
                // zip 实现必须关，否则 fd 泄漏 —— 装载几十个包会直接耗尽。
                runCatching { archive.close() }
            }

            if (contentResult.isFailure) {
                val reason = contentResult.exceptionOrNull()?.message ?: "未知原因"
                failed += ActivationReport.Failure(
                    packageId = plugin.id,
                    errors = listOf("读取包内容失败：" + reason),
                )
                continue
            }

            val outcome = host.load(plugin, contentResult.getOrThrow())
            if (outcome.errors.isEmpty()) {
                loaded += plugin.id
            } else {
                failed += ActivationReport.Failure(plugin.id, outcome.errors)
            }
        }

        val report = ActivationReport(loaded = loaded, failed = failed, skipped = skipped)
        _report.value = report
        return report
    }
}
