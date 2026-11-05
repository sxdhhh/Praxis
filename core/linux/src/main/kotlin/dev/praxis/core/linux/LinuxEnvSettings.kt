package dev.praxis.core.linux

/**
 * Linux 环境页需要**跨进程存活**的配置。
 *
 * ## 为什么需要它（2026-11-06）
 *
 * 旧实现把所有配置（发行版 / 镜像 / FTP / SSH / FRP）只放在 ViewModel 的
 * `_state` 里。`viewModel(key = "linux-env")` 只在同一导航层级存活 ——
 * **退出设置页再进来，全部回到默认值**。用户改了 FTP 端口、选了 Kali，
 * 下次进页面又变回 Ubuntu + 2121。
 *
 * ## 为什么不把密码放进来
 *
 * 与 API Key 同一条纪律：密码不该以明文躺在普通偏好里。
 * 这里只存「用户名」「端口」这类非敏感项；密码在内存里持有，
 * 由单独的加密路径负责（设置后立即用于启动，不落明文）。
 */
data class LinuxEnvSettings(
    /** 选中的发行版（枚举名，未知值回落 Ubuntu）。 */
    val distroName: String = "Ubuntu",
    /** 镜像源名（对应 `UbuntuMirror` / Kali 的源标识）。 */
    val mirrorName: String = "",
    /** Ubuntu release 名（如 `noble`）；非 Ubuntu 时为空。 */
    val releaseName: String = "",
    /** FTP 的**非敏感**部分。 */
    val ftpPort: Int = FtpSettings.DEFAULT_PORT,
    val ftpUser: String = "praxis",
    val ftpHomeDirectory: String = "/root",
    val ftpPassivePortLow: Int = 2122,
    val ftpPassivePortHigh: Int = 2132,
    val ftpReadOnly: Boolean = false,
    val ftpMaxConnections: Int = 5,
) {

    /** 还原成 `FtpSettings`（密码为空 —— 由调用方补）。 */
    fun toFtpSettings(password: String = ""): FtpSettings = FtpSettings(
        port = ftpPort,
        user = ftpUser,
        password = password,
        homeDirectory = ftpHomeDirectory,
        passivePortLow = ftpPassivePortLow,
        passivePortHigh = ftpPassivePortHigh,
        readOnly = ftpReadOnly,
        maxConnections = ftpMaxConnections,
    )

    companion object {
        /** 从一个 `FtpSettings` 提取可持久化部分（**丢掉密码**）。 */
        fun fromFtpSettings(s: FtpSettings): LinuxEnvSettings = LinuxEnvSettings(
            ftpPort = s.port,
            ftpUser = s.user,
            ftpHomeDirectory = s.homeDirectory,
            ftpPassivePortLow = s.passivePortLow,
            ftpPassivePortHigh = s.passivePortHigh,
            ftpReadOnly = s.readOnly,
            ftpMaxConnections = s.maxConnections,
        )
    }
}
