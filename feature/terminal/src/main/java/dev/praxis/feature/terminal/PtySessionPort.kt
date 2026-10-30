package dev.praxis.feature.terminal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 真终端会话端口。
 *
 * ## 与 [CommandRunner] 的分工
 *
 * 两者并存，不是替换关系：
 *
 * | | [CommandRunner] | [PtySessionPort] |
 * |---|---|---|
 * | 模型 | 一条命令 = 一个进程 | 一个会话 = 一个长驻 shell |
 * | `cd` / `export` | 不保留 | 保留 |
 * | vim / top | 退化成纯文本 | 正常 |
 * | Ctrl+C | 只能 destroy | 发 0x03，shell 自己处理 |
 * | 用途 | 非容器模式（没装 Linux 环境时） | 容器模式 |
 *
 * 为什么不能把 [CommandRunner] 改造掉：它的签名
 * `suspend fun run(command, onOutput): Int` 表达的是「一次请求-响应」，
 * 而真终端要的是「持续双向字节流 + 窗口尺寸」。硬塞进一个函数会让
 * 两个用途都变形（要么终端拿不到逐键能力，要么模型那条路被迫处理
 * 一个它永远用不上的 Flow）。
 *
 * ## 为什么全是字节而不是 String
 *
 * 终端是字节流设备。三处必须字节：
 *
 *  - ANSI 转义序列（`ESC [ 3 1 m`）用 String 中转会被某些平台按非法码位替换；
 *  - `
` 要保留「回行首」语义，`readLine` 类 API 会吃掉它；
 *  - 输入侧的 `0x03`（Ctrl+C）与半个 UTF-8 字符都不能走 String。
 */
interface PtySessionPort {

    /**
     * 打开（或复用）一个会话。
     *
     * 幂等：同名会话已存在时直接返回它的 id（重复调用不会造出两个 shell）。
     *
     * @return 会话 id；失败时返回 null（原因由实现方日志记录）
     */
    suspend fun open(name: String, rows: Int, cols: Int): String?

    /**
     * 订阅该会话的输出字节流。
     *
     * 返回冷流：每个收集者独立。页面离开时必须取消收集，
     * 否则底层监听者不会释放。
     */
    fun outputFlow(sessionId: String): Flow<ByteArray>

    /** 发送原始字节（逐键输入）。 */
    fun write(sessionId: String, bytes: ByteArray): Boolean

    /**
     * 告诉内核新窗口尺寸。
     *
     * @return 是否真的应用了（管道降级时 false）
     */
    fun resize(sessionId: String, rows: Int, cols: Int): Boolean

    /** 关闭会话。 */
    fun close(sessionId: String): Boolean

    /**
     * 当前屏幕快照（纯文本）。
     *
     * 只在「刚接上已有会话、需要回填历史」时用；实时渲染走 [outputFlow]。
     */
    fun screen(sessionId: String, maxLines: Int = 500): String? = null

    /**
     * 上一次 [open] 失败的原因（失败后由实现方写入，成功后应清空）。
     *
     * ## 为什么需要它（2026-10-30）
     *
     * [open] 只返回 `String?`，失败即 null。而终端页拿不到 null 会**静默降级**
     * 到「一次一条命令」的管道模式 —— 那条路跑的是**宿主自己的 sh**，
     * 完全不在 proot 容器里。用户看到的是「终端能敲命令」，但 `apt`、`pkill`、
     * `/etc` 全是宿主的，与「进到下载好的 Linux 里」完全不同。
     *
     * 症状因此极具误导性：终端可用、`ls` 有输出、甚至 `uname` 也能跑，
     * 只是看到的不是容器。没有报错、没有提示，用户只能从文件名或
     * `cat /etc/os-release` 里察觉不对。
     *
     * 有了这个字段，UI 才能在屏幕上直接写「容器会话没起来，原因：…」，
     * 而不是让用户以为自己在容器里。
     */
    val lastError: String? get() = null
}

/**
 * 空实现：容器环境不可用时终端页回到「假终端」模式。
 *
 * 让它成为**正常对象**而不是 null，UI 层就不必到处判空（与
 * `MarketApi.disabled()` 同一取舍）。
 */
object NoPtySessionPort : PtySessionPort {
    override suspend fun open(name: String, rows: Int, cols: Int): String? = null

    override fun outputFlow(sessionId: String): Flow<ByteArray> = emptyFlow()

    override fun write(sessionId: String, bytes: ByteArray): Boolean = false

    override fun resize(sessionId: String, rows: Int, cols: Int): Boolean = false

    override fun close(sessionId: String): Boolean = false

    override fun screen(sessionId: String, maxLines: Int): String? = null
}
