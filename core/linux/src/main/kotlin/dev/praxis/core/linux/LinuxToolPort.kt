package dev.praxis.core.linux

/**
 * 给 AI 用的 Linux 容器执行端口。
 *
 * ## 为什么单独一个端口，而不复用 `feature:settings` 的 `LinuxEnvPort`
 *
 * `LinuxEnvPort` 是**给设置页用的**：它按「用户点击」建模（安装、启动、开关 SSH），
 * 方法签名里带 `onLine` 回调用于刷新界面进度。AI 调用时没有界面、
 * 也不需要「进度行」，它要的是「执行一条命令 → 拿到合并后的输出与退出码」。
 *
 * 硬把两者合成一个接口的后果很具体：工具层会依赖 `feature:settings`
 * （违反 core 不得依赖 feature 的分层），或者得在工具里伪造一个 `onLine`。
 * 分开之后，`core` 只声明形状，由 `:app` 同时实现两者并共享同一份 proot 会话。
 *
 * ## 为什么带 `autoInstall`
 *
 * user 的要求是「内置一个 kali linux，**然后让 ai 可以调用**」。
 * 若 AI 调用时容器还没装，直接返回「未安装」等于让模型愣住 ——
 * 它没有「点界面装环境」的能力。`autoInstall = true` 会让实现方
 * 在首次调用时自动下载安装（Kali 131 MB，需要联网），
 * 这样「AI 让我跑个 nmap」才能真正跑起来。
 *
 * 自动安装是**有代价**的操作（下载 131 MB、占 500 MB 磁盘），
 * 所以工具声明里把它做成显式参数、默认 false，并在描述里写清代价。
 */
interface LinuxToolPort {

    /**
     * 当前环境的可用性。
     *
     * 工具在真正执行前先查这个：把「没装」这一事实用一句话告诉模型，
     * 比让 proot 起不来再返回一段英文错误有用得多。
     */
    fun status(): LinuxStatus

    /**
     * 在容器里执行一条 shell 命令。
     *
     * @param command 要执行的命令（交给容器内的 `/bin/sh -c`）。
     * @param timeoutSeconds 超时。安全工具（nmap 全端口扫描）可能跑很久，
     *   所以由调用方指定，默认给一个保守值。
     * @return 合并 stdout/stderr 的输出与退出码。
     */
    suspend fun run(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        autoInstall: Boolean = false,
    ): LinuxExecResult

    companion object {
        /** 默认超时：5 分钟。够跑常规扫描与 apt 安装，又不至于让一次调用挂死。 */
        const val DEFAULT_TIMEOUT_SECONDS: Long = 300L

        /** 输出上限（字符）。超出部分截断并标注，避免把整段扫描结果灌进上下文。 */
        const val MAX_OUTPUT_CHARS: Int = 16_000
    }
}

/** 容器状态快照。 */
data class LinuxStatus(
    /** 是否已选中/存在一个发行版。 */
    val distro: LinuxDistro = LinuxDistro.Kali,
    /** proot 二进制是否就绪（随 APK 打包）。 */
    val prootAvailable: Boolean = false,
    /** rootfs 是否已解压。 */
    val installed: Boolean = false,
    /** 是否有活着的容器会话。 */
    val running: Boolean = false,
) {
    /** 能否立刻执行命令。 */
    val canRun: Boolean get() = prootAvailable && installed
}

/** 一次命令执行的结果。 */
data class LinuxExecResult(
    val exitCode: Int,
    val output: String,
    /** 是否因为超时被中断。与「命令自己返回非零」是两件事，必须分开。 */
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}