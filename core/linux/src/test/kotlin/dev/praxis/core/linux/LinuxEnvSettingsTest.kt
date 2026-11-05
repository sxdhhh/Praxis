package dev.praxis.core.linux

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LinuxEnvSettings] 与 [FtpSettings] 之间的转换。
 *
 * ## 为什么这条转换值得测
 *
 * 它决定了「哪些字段会被存下来」。漏掉一个字段的表现是：用户改了设置，
 * 退出设置页再进又变回去 —— 而那**不会有任何报错**，只是「保存不生效」。
 */
class LinuxEnvSettingsTest {

    @Test
    fun `FtpSettings 往返保持全部非敏感字段`() {
        val original = FtpSettings(
            port = 2323,
            user = "tester",
            password = "secret",
            homeDirectory = "/home/tester",
            passivePortLow = 2400,
            passivePortHigh = 2500,
            readOnly = true,
            maxConnections = 9,
        )
        val restored = LinuxEnvSettings.fromFtpSettings(original).toFtpSettings(password = "")
        assertEquals(2323, restored.port)
        assertEquals("tester", restored.user)
        assertEquals("/home/tester", restored.homeDirectory)
        assertEquals(2400, restored.passivePortLow)
        assertEquals(2500, restored.passivePortHigh)
        assertEquals(true, restored.readOnly)
        assertEquals(9, restored.maxConnections)
    }

    /**
     * 密码**不能**被持久化。
     *
     * 与 API Key 同一条纪律：密码不该以明文躺在普通偏好里。
     * 往返后密码必须为空，由调用方在启动时补上。
     */
    @Test
    fun `持久化时丢掉密码`() {
        val original = FtpSettings(password = "secret")
        val restored = LinuxEnvSettings.fromFtpSettings(original).toFtpSettings()
        assertEquals("", restored.password)
    }

    @Test
    fun `默认设置自洽`() {
        val defaults = LinuxEnvSettings()
        assertEquals("Ubuntu", defaults.distroName)
        assertEquals(FtpSettings.DEFAULT_PORT, defaults.ftpPort)
        // 默认值本身必须通过校验，否则用户一进来就看到红字报错。
        assertEquals(null, defaults.toFtpSettings(password = "x").problem)
    }
}
