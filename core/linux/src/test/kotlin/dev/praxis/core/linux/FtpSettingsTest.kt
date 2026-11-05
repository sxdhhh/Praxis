package dev.praxis.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FtpSettings] 的校验。
 *
 * ## 为什么这些校验必须在启动前做
 *
 * FTP 的失败模式里有一类**不报错但不可用**：被动端口段写错时，
 * 服务能启动、能登录，但客户端 `LIST` 会卡住 —— 用户看到的是
 * 「登录成功但一个文件都没有」。这种症状极难自己排查，
 * 所以所有能提前判出来的错误都要在打开开关前拦住。
 */
class FtpSettingsTest {

    private val valid = FtpSettings(password = "x")

    @Test
    fun `默认设置合法`() {
        assertNull(valid.problem)
    }

    /** 没密码就不能启动：否则会起一个「谁都能登录」的服务。 */
    @Test
    fun `空密码不合法`() {
        assertEquals("请先设置密码。", FtpSettings(password = "").problem)
    }

    /** 非特权端口：< 1024 需要 CAP_NET_BIND_SERVICE，Android 应用拿不到。 */
    @Test
    fun `特权端口不合法`() {
        assertFalse(valid.copy(port = 21).isPortUsable)
        assertFalse(valid.copy(port = 80).isPortUsable)
    }

    @Test
    fun `端口越界不合法`() {
        assertFalse(valid.copy(port = 0).isPortUsable)
        assertFalse(valid.copy(port = 70000).isPortUsable)
    }

    /** 下界必须小于上界，否则数据通道没有可用端口。 */
    @Test
    fun `被动段顺序颠倒不合法`() {
        assertFalse(valid.copy(passivePortLow = 2140, passivePortHigh = 2130).isPassiveRangeValid)
    }

    @Test
    fun `被动段相等不合法`() {
        // 只有一个端口时并发传输会抢，等于不可用。
        assertFalse(valid.copy(passivePortLow = 2130, passivePortHigh = 2130).isPassiveRangeValid)
    }

    /**
     * 被动段包含控制端口不合法。
     *
     * 两者共用同一端口会导致绑定冲突：服务能启动，但数据传输必然失败。
     */
    @Test
    fun `被动段含控制端口不合法`() {
        assertFalse(valid.copy(port = 2125, passivePortLow = 2120, passivePortHigh = 2130).isPassiveRangeValid)
    }

    @Test
    fun `被动段越界不合法`() {
        assertFalse(valid.copy(passivePortLow = 80, passivePortHigh = 2130).isPassiveRangeValid)
    }

    /** 根目录必须是容器内绝对路径。 */
    @Test
    fun `相对根目录不合法`() {
        assertFalse(valid.copy(homeDirectory = "root").isHomeUsable)
        assertFalse(valid.copy(homeDirectory = "/").isHomeUsable)
    }

    @Test
    fun `绝对根目录合法`() {
        assertTrue(valid.copy(homeDirectory = "/root").isHomeUsable)
        assertTrue(valid.copy(homeDirectory = "/home/praxis").isHomeUsable)
    }

    /** 用户名不能含空格 / 斜杠 / 冒号（会破坏 FTP 的 USER 命令与路径）。 */
    @Test
    fun `非法用户名不合法`() {
        assertFalse(valid.copy(user = "").isUserUsable)
        assertFalse(valid.copy(user = "a b").isUserUsable)
        assertFalse(valid.copy(user = "a/b").isUserUsable)
        assertFalse(valid.copy(user = "a:b").isUserUsable)
    }

    /** `problem` 只返回第一条原因，且顺序稳定（端口优先）。 */
    @Test
    fun `问题按固定顺序返回`() {
        val broken = FtpSettings(port = 80, user = "a b", password = "")
        assertTrue(broken.problem!!.contains("端口"))
    }
}
