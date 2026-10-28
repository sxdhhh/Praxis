package dev.praxis.data.linux

import dev.praxis.core.linux.TerminalSessionPort
import dev.praxis.feature.terminal.PtySessionPort
import kotlinx.coroutines.flow.Flow

/**
 * `TerminalSessionPort` → `PtySessionPort` 适配器。
 *
 * ## 为什么需要这一层
 *
 * `core:linux` 的 [TerminalSessionPort] 是**给模型用的**：它的会话是
 * 「AI 在哪个 shell 里跑命令」，所以接口围绕 `execute(command)` /
 * `screen()` / `sendInput(control)` 设计 —— 一个请求、一个响应。
 *
 * 终端页要的是另一套：**用户的键盘直接连到 shell**。它需要的是
 * 「开一个会话」「给我原始字节流」「把这个键写进去」「窗口变了」。
 * 这套能力 `ProotTerminalSessionPort` 里其实已经全有了（forkpty、
 * `outputFlow`、`writeBytes`、`resize`），只是长在一个以命令为中心的
 * 接口上。
 *
 * 所以这里不复制逻辑，只做**形状转换**：把同一份会话表用 UI 关心的
 * 那四个动词暴露出去。两个用途共用同一批 PTY 进程，不会出现
 * 「AI 在 t1 里、用户在 t2 里」的错位 —— 共用 `ProotTerminalSessionPort`
 * 单例就够了。
 *
 * ## 为什么放在 `:app` 而不是 `feature:terminal` 或 `core:linux`
 *
 * 同 `TerminalSnapshotPort` 的接线纪律：`feature:terminal` 不依赖
 * `core:linux` 的具体实现，`core:linux` 也不该知道 UI 端口存在。
 * 适配器两端都懂，只有 `:app` 符合这个位置。
 *
 * ## `open` 的尺寸参数怎么用
 *
 * [TerminalSessionPort.create] 只收名字，PTY 起在 60×80。这里在
 * 会话建好后立刻 `resize` 到调用方给的真实值 —— 不做「先 60×80 再改」
 * 会有一段短暂的排版错位（`top` 按 60 行画完再被压成 24 行）。
 */
class ProotPtySessionAdapter(
    private val delegate: TerminalSessionPort,
) : PtySessionPort {

    override suspend fun open(name: String, rows: Int, cols: Int): String? {
        // create 是幂等的（同名返回既有会话），所以重复进页面不会造出
        // 第二个 shell —— 这正是 `startPtySession` 敢在 addTab 里无脑调的前提。
        val info = runCatching { delegate.create(name) }.getOrNull() ?: return null
        if (info.closed) return null
        // 建完立刻对齐尺寸。失败不算错：管道降级时 resize 恒 false，
        // 那种模式下没有窗口概念，UI 也不该因此报错。
        if (rows > 0 && cols > 0) {
            runCatching { delegate.resize(info.id, rows, cols) }
        }
        return info.id
    }

    override fun outputFlow(sessionId: String): Flow<ByteArray> =
        delegate.outputFlow(sessionId)

    override fun write(sessionId: String, bytes: ByteArray): Boolean =
        delegate.writeBytes(sessionId, bytes)

    override fun resize(sessionId: String, rows: Int, cols: Int): Boolean =
        delegate.resize(sessionId, rows, cols)

    override fun close(sessionId: String): Boolean =
        delegate.close(sessionId)

    override fun screen(sessionId: String, maxLines: Int): String? =
        delegate.screen(sessionId, maxLines)
}
