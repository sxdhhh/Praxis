package dev.praxis.core.linux

/**
 * FTP 服务的设置（纯数据，可在 JVM 单测里构造）。
 *
 * ## 与旧 `FtpConfig` 的关系
 *
 * 旧模型描述的是「容器内 vsftpd 的配置」——里面有 `homeDir`（chroot 根）、
 * `passivePorts`、用户名密码，还隐含着「配置文件路径」「PAM 路径」等
 * vsftpd 专属概念。换成 Apache FtpServer 后这些都不存在了：
 *
 * | 旧（vsftpd） | 新（Apache FtpServer） |
 * |---|---|
 * | `/etc/vsftpd.praxis.conf` | 无（配置在内存里） |
 * | PAM 配置 | 无（用户表在内存里） |
 * | `local_root` | `homeDirectory` |
 * | 依赖 apt 装包 | 无依赖 |
 *
 * 所以这是一个**新模型**而不是给旧的加字段 —— 旧的那套概念已经整体不适用。
 */
data class FtpSettings(
    /** 控制连接端口。默认 2121（避免与常见 21 端口冲突）。 */
    val port: Int = DEFAULT_PORT,
    /** 用户名。 */
    val user: String = "praxis",
    /** 密码（只在设置时短暂持有，不落明文库）。 */
    val password: String = "",
    /** 容器内可访问的根目录（FTP 客户端看到的 `/`）。 */
    val homeDirectory: String = "/root",
    /** 被动模式端口段下界。 */
    val passivePortLow: Int = 2122,
    /** 被动模式端口段上界。 */
    val passivePortHigh: Int = 2132,
    /** 只读模式（禁止上传/删除/改名）。 */
    val readOnly: Boolean = false,
    /** 最大并发连接数。 */
    val maxConnections: Int = 5,
) {

    /** 端口是否可用（非特权端口，且不与默认的 21/22 冲突）。 */
    val isPortUsable: Boolean
        get() = port in MIN_PORT..MAX_PORT

    /**
     * 被动端口段是否合法。
     *
     * ## 为什么必须校验（不是「填错也能启动」就算了）
     *
     * FTP 的数据通道走被动端口段。段写错时服务**能启动、能登录**，
     * 但客户端执行 `LIST` 会卡住 —— 症状是「登录成功但看不到任何文件」。
     * 这种表现极难自己排查出来，所以在打开前就拦住。
     */
    val isPassiveRangeValid: Boolean
        get() = passivePortLow in MIN_PORT..MAX_PORT &&
            passivePortHigh in MIN_PORT..MAX_PORT &&
            passivePortLow < passivePortHigh &&
            // 数据端口不能与控制端口相同。
            port !in passivePortLow..passivePortHigh

    /** 用户名是否可用（不含空格/斜杠/冒号，非空）。 */
    val isUserUsable: Boolean
        get() = user.isNotBlank() &&
            user.none { it == ' ' || it == '/' || it == ':' || it == '\\' }

    /** 根目录是否可用（必须是容器内绝对路径）。 */
    val isHomeUsable: Boolean
        get() = homeDirectory.startsWith("/") && homeDirectory.length > 1

    /** 第一条不满足的原因（给 UI 显示）；全部满足返回 null。 */
    val problem: String?
        get() = when {
            !isPortUsable -> "端口必须在 $MIN_PORT-$MAX_PORT 之间。"
            !isHomeUsable -> "根目录必须是容器内的绝对路径。"
            !isPassiveRangeValid ->
                "被动端口段无效：需要两个 $MIN_PORT-$MAX_PORT 之间的端口，" +
                    "下界小于上界，且不包含控制端口 $port。"
            !isUserUsable -> "用户名不能为空，也不能含空格、斜杠或冒号。"
            password.isEmpty() -> "请先设置密码。"
            else -> null
        }

    companion object {
        const val DEFAULT_PORT = 2121
        /**
         * 非特权端口下界。
         *
         * 与 `SshConfig` / 旧 `FtpConfig` 同一口径：绑定 < 1024 需要
         * `CAP_NET_BIND_SERVICE`，Android 上普通应用拿不到。
         */
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
