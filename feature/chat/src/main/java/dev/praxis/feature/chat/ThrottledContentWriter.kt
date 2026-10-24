package dev.praxis.feature.chat

/**
 * 流式正文写入节流器。
 *
 * ## 为什么必须节流
 *
 * 模型流式输出时，一个 3000 字的回复会产生数百个 delta。每个 delta 都写一次库，
 * 就是数百次 SQLite `UPDATE` + 磁盘 fsync —— 真机上表现为：
 *  - 回复越长越卡（IO 与 UI 抢资源）；
 *  - 滑走页面后回来还在补写；
 *  - 耗电与写入寿命无谓消耗。
 *
 * ## 策略：时间节流 + 终态强制刷
 *
 * - 两次写入间隔 < [minIntervalMs] 时**只暂存**，不写库；
 * - 到达间隔的第一次提交立即写，并记录时间；
 * - 流结束/失败/取消时调 [flush] **强制写入最新内容**，保证不丢尾。
 *
 * 为什么不用协程 debounce：debounce 需要额外启动协程与调度，且「最后一次一定执行」
 * 依赖协程生命周期；这里用显式时间戳 + flush，行为完全确定，也能纯 JVM 单测。
 *
 * 非线程安全：只在 ViewModel 的单一线程上下文使用。
 */
class ThrottledContentWriter(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val write: (rowId: Long, content: String) -> Unit,
) {

    private val lastWriteAt = mutableMapOf<Long, Long>()
    private val pending = mutableMapOf<Long, String>()

    /** 提交一次候选内容；可能被丢弃（留待后续或 [flush]）。 */
    fun submit(rowId: Long, content: String) {
        val last = lastWriteAt[rowId]
        val now = nowMs()

        if (last == null || now - last >= minIntervalMs) {
            lastWriteAt[rowId] = now
            pending.remove(rowId)
            write(rowId, content)
        } else {
            // 不写库，但必须记住最新内容 —— 否则 flush 时会写成旧的。
            pending[rowId] = content
        }
    }

    /**
     * 强制写入暂存内容（流结束/失败/取消时调用）。
     *
     * 幂等：没有暂存内容时什么都不做，不会重复写同一份正文。
     */
    fun flush(rowId: Long) {
        val content = pending.remove(rowId) ?: return
        lastWriteAt[rowId] = nowMs()
        write(rowId, content)
    }

    /** 清掉某个 row 的节流状态（删除会话/重建时用，防止内存泄漏）。 */
    fun forget(rowId: Long) {
        pending.remove(rowId)
        lastWriteAt.remove(rowId)
    }

    /**
     * 取出**全部**尚未写入的内容，并清空内部暂存。
     *
     * ## 为什么需要它（2026-10-22，第 9 项修）
     *
     * `flush(rowId)` 一次只处理一个 row，而 `onCleared` 时可能有多个气泡
     * 都在节流窗口里（例如工具调用并发产生了多个助手气泡）。
     * 逐个 `flush` 需要外部维护 rowId 列表 —— 而那个列表正是 ViewModel
     * 里的 `assistantRowIds`，它在 `onCleared` 时同样可能已被清空。
     *
     * 所以把「待写集合」整体交出去，由调用方在最后一次统一写库。
     *
     * 调用后内部状态被清空：重复调用返回空 map，不会重复写。
     */
    fun drainPending(): Map<Long, String> {
        if (pending.isEmpty()) return emptyMap()
        val out = pending.toMap()
        pending.clear()
        val now = nowMs()
        for (key in out.keys) lastWriteAt[key] = now
        return out
    }

    companion object {
        /**
         * 100ms：人眼感知不到写入延迟，但把写入次数降到约 1/10。
         * 取太大会丢帧（用户看到文字但库里没有），太小起不到节流作用。
         */
        const val DEFAULT_MIN_INTERVAL_MS = 100L
    }
}
