package dev.praxis.data.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⑤ 容器会话读取中断：哪些异常算「正常收尾」，哪些必须报给用户。
 *
 * ## 用户报的现象
 *
 * ```
 * 会话读取结束：read interrupted by close() on another thread
 * ```
 *
 * 用户点关闭标签页之后看到这行红字，以为容器「跑着跑着自己挂了」。
 * 实际上那是他自己关的 —— 正常关闭不该产生错误文案。
 *
 * ## 机制
 *
 * `close()` 会关掉 PTY 的 master fd，而泵线程此刻正阻塞在 `read()` 上，
 * JDK 于是抛 `IOException("read interrupted by close() on another thread")`。
 * 这条异常对**主动关闭**是预期内的。
 *
 * ## 判据为什么收得这么紧
 *
 * 只放行「会话已被标记为关闭」。若放宽成「凡是 read interrupted 都忽略」，
 * 真正的 IO 故障会被一起吞掉，会话静默死掉、调用方只能等到超时 ——
 * 那比多打一行日志糟得多。
 */
class SessionShutdownTest {

    /**
     * 单条用例 30 秒闸门（2026-11-05）。
     *
     * ## 为什么加在每一个 :app 测试类上
     *
     * `:app:testDebugUnitTest` 在 CI 上卡住过 17 分钟不结束
     * （正常 0.3~1.4 分钟），而 Gradle **默认不给测试任务设超时**，
     * 也**不打印正在跑哪条用例** —— 于是只看到一个不动的进度条。
     *
     * 试过在 app/build.gradle.kts 里配 `Test.timeout` 与
     * `testLogging.events(started)`：对**被取消**的任务，Gradle 会把
     * 缓冲的输出一起丢掉，日志里一行都没有。
     *
     * 所以改成让**断言层**自己超时 —— 失败信息里直接带类名与方法名，
     * 不依赖 Gradle 的输出缓冲。
     *
     * 30 秒是宽裕值：本文件最快的用例不到 10 毫秒。
     */
    @get:org.junit.Rule
    val praxisTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    /**
     * 主动关闭时读线程抛异常 —— 不该报错。
     *
     * 这正是 ⑤ 的修复点。
     */
    @Test
    fun `会话被标记关闭后读线程异常算预期`() {
        assertTrue(
            "用户主动关标签页导致的 read interrupted 不该报成故障",
            ProotTerminalSessionPort.isExpectedShutdown(failed = true, closing = true),
        )
    }

    /**
     * 没有关闭标记时的异常必须报出来。
     *
     * 这是「不吞真故障」的底线：磁盘错、fd 被系统回收都要能让用户看见。
     */
    @Test
    fun `没有关闭标记时异常必须报出来`() {
        assertFalse(
            "真正的 IO 故障不能被静默吞掉，否则会话会无声死掉",
            ProotTerminalSessionPort.isExpectedShutdown(failed = true, closing = false),
        )
    }

    /**
     * 读线程正常读到 EOF（没抛异常）—— 不必报错。
     */
    @Test
    fun `正常 EOF 不报错`() {
        assertTrue(
            "shell 执行 exit 是正常结束",
            ProotTerminalSessionPort.isExpectedShutdown(failed = false, closing = false),
        )
    }

    /**
     * 已被标记关闭、且没抛异常 —— 同样不必报错。
     */
    @Test
    fun `关闭标记加正常 EOF 不报错`() {
        assertTrue(
            ProotTerminalSessionPort.isExpectedShutdown(failed = false, closing = true),
        )
    }
}
