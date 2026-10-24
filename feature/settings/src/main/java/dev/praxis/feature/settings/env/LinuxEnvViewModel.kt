package dev.praxis.feature.settings.env

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.praxis.core.linux.FrpConfig
import dev.praxis.core.linux.FrpScript
import dev.praxis.core.linux.FtpConfig
import dev.praxis.core.linux.FtpServerScript
import dev.praxis.core.linux.LinuxDiskUsage
import dev.praxis.core.linux.LinuxDistro
import dev.praxis.core.linux.LinuxEnvironment
import dev.praxis.core.linux.ProotCommand
import dev.praxis.core.linux.SshConfig
import dev.praxis.core.linux.SshdScript
import dev.praxis.core.linux.UbuntuMirror
import dev.praxis.core.linux.UbuntuRelease
import dev.praxis.core.model.linux.buildPackageInstallScript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Linux 环境页的状态。
 *
 * ## `release` / `mirror` 为什么不设为可空
 *
 * 一开始想让「还没装」用 `null` 表达，但那会让 Spinner 的「当前选中项」也变成
 * 可选处理，等于在两个地方处理同一个「未决定」。现在改成：**始终有值**
 * （默认 Noble / Tsinghua），「装没装」只由 [installed] 表示。
 *
 * 这样 UI 只有一个判断依据（`installed`），不会出现「release 有值但没装」
 * 这种自相矛盾的中间态。
 */
data class LinuxEnvUiState(
    /** proot 是否可用（nativeLibraryDir 里有 libproot.so）。 */
    val prootAvailable: Boolean = false,
    /** rootfs 是否已解压。 */
    val installed: Boolean = false,
    /** 容器是否在运行（有一个活着的 proot 会话）。 */
    val running: Boolean = false,
    /** 有长任务在进行（下载 / 解压 / apt）。 */
    val busy: Boolean = false,
    /**
     * 选中的发行版。
     *
     * 与 [release] 不同：Kali 没有 LTS 版本概念（只有 rolling），
     * 所以它是**独立维度**而不是 release 的一个取值。
     */
    val distro: LinuxDistro = LinuxDistro.Ubuntu,
    val release: UbuntuRelease = UbuntuRelease.Noble,
    val mirror: UbuntuMirror = UbuntuMirror.Tsinghua,
    /** 最近一行输出（给用户一个进度感，不放整块终端）。 */
    val lastLine: String? = null,
    /** 一句话状态摘要。 */
    val summary: String = "尚未安装 Linux 环境。",
    val sshEnabled: Boolean = false,
    val sshConfig: SshConfig = SshConfig(),
    val frpEnabled: Boolean = false,
    val frpConfig: FrpConfig = FrpConfig(),
    /**
     * FTP 是否在运行。
     *
     * 与 [sshEnabled] / [frpEnabled] 平级：三者都是「容器内某服务是否在跑」，
     * 生命周期都由本 ViewModel 管（容器停 → 三者一起停）。
     */
    val ftpEnabled: Boolean = false,
    val ftpConfig: FtpConfig = FtpConfig(),
    /**
     * 共享 rootfs **最初**是从哪个发行版解出来的（`"ubuntu"` / `"kali"`）。
     *
     * ## 为什么界面必须知道这个
     *
     * 2026-10-22 起两个发行版**共用同一份 rootfs**（见
     * `LinuxEnvironment.SHARED_ROOTFS_DIR_NAME`）。于是「用户选了 Kali」
     * 与「底层真的是 Kali」不再是同一件事：
     *
     * | [distro]（选中） | [rootfsOrigin]（实际底层） | 真实含义 |
     * |---|---|---|
     * | Kali | `kali` | 真 Kali 环境，切源即可 |
     * | Kali | `ubuntu` | **Ubuntu base + Kali 源**，不是真 Kali |
     * | Ubuntu | `kali` | Kali 环境 + Ubuntu 源 |
     *
     * 不显示这一行的话，用户选 Kali、看到「已安装」、进去发现
     * `/etc/os-release` 写着 Ubuntu —— 会认定是 bug。
     * 如实说明「底层是 Ubuntu base，切 Kali 只换源不重装」才是诚实的做法。
     *
     * null 表示未安装或标记缺失（老版本装的没有这个标记）。
     */
    val rootfsOrigin: String? = null,
    /**
     * 共享 rootfs 的 `/etc/apt` 当前指向哪个发行版的仓库（`"ubuntu"` / `"kali"`）。
     *
     * ## 为什么它必须与 [rootfsOrigin] 分开存
     *
     * 两者描述不同的事实，且会**各自独立变化**：
     *
     * ```
     * 从 Ubuntu 镜像装  → origin=ubuntu, sources=ubuntu
     * 切到 Kali 源      → origin=ubuntu, sources=kali    ← 底层仍是 Ubuntu base
     * 重新装 Kali 镜像  → origin=kali,   sources=kali
     * 再从 Kali 切回 Ubuntu 源 → origin=kali, sources=ubuntu
     * ```
     *
     * 只有 origin 的话，「Kali origin + 已切回 Ubuntu 源」会算成
     * 「与选中一致」，于是「应用 Ubuntu 的软件源」按钮不出现 ——
     * 用户卡在 Kali 源上却以为自己已经切回来了。
     *
     * null 表示还没换过源（刚装完）或老版本升级上来（没有这个标记）。
     */
    val activeSources: String? = null,

    // ── 存储管理（2026-10-23 新增，对齐 Operit 的 storage_management_title）──

    /**
     * 磁盘占用快照。`null` = 还没统计过（首帧 / 统计中）。
     *
     * ## 为什么用可空而不是零值
     *
     * 「还没统计」与「统计出来是 0 字节」是**两件完全不同的事**：
     * 前者该显示「统计中…」，后者该显示「0 B」。
     * 用 0 兼表两态会让用户看到「0 B」然后以为环境是空的。
     *
     * 统计要遍历几十万个文件（rootfs 解压后有 3 万+ 个 inode），
     * 在低端机上可能要一两秒 —— 必须有这个「未就绪」态。
     */
    val usage: LinuxDiskUsage.EnvironmentUsage? = null,

    /** 是否正在统计占用（按钮要显示转圈并禁用）。 */
    val calculatingUsage: Boolean = false,

    /**
     * 共享 tmp 是否开启（对齐 Operit 的 `shared_tmp_title`）。
     *
     * ## 为什么它是 proot 启动参数的一部分，改它必须重启容器
     *
     * 见 `LinuxEnvironment.sharedTmp` 的 KDoc。UI 上表现为：
     * 容器在跑时改这个开关 → 立刻标一个「需重启生效」的提示，
     * 而不是让用户以为改完就生效了却看不到变化。
     */
    val sharedTmp: Boolean = false,
) {
    /** 是否该禁止「安装」按钮。 */
    val canInstall: Boolean get() = prootAvailable && !busy

    /**
     * 选中的发行版是否**已经是**共享 rootfs 的当前软件源。
     *
     * ## origin 与 sources 是两件事，必须分清
     *
     * | | 含义 | 会不会变 |
     * |---|---|---|
     * | [rootfsOrigin] | 底层文件系统最初从哪个镜像解出来 | **不变**（只有重装才改） |
     * | [activeSources] | `/etc/apt` 当前指向哪个发行版的仓库 | 换源就变 |
     *
     * 本属性判的是后者。用前者代替会在「Kali origin + 已切到 Kali 源」
     * 时算出正确结果，但在「Kali origin + 用户切回 Ubuntu 源」时
     * 仍然说「一致」—— 于是按钮不出现，用户无法切回去。
     *
     * 取不到 [activeSources]（刚装完还没换过源、或老版本升级上来）时
     * 退回比较 [rootfsOrigin]：装完时的源就是安装镜像对应的那套，等价。
     */
    val sourcesMatchSelection: Boolean
        get() {
            val active = activeSources
            if (active != null) return active.equals(distro.name, ignoreCase = true)
            val origin = rootfsOrigin ?: return false
            return origin.equals(distro.name, ignoreCase = true)
        }
}

/**
 * Linux 环境的执行端口。
 *
 * ## 与 `CommandRunner`（feature:terminal）同一套思路
 *
 * 端口在里、实现在外。`feature:settings` 是纯 UI + 状态机，不能碰 `ProcessBuilder`
 * 与 `Context`；真正怎么拉起 proot、怎么解压 tar、怎么下载由 `:app` 实现。
 *
 * 这样做的直接好处：本 ViewModel 可以**纯 JVM 单测**（给一个假端口即可），
 * 而 proot 那套只有真机才能验的东西集中在 `:app` 的一个类里。
 */
interface LinuxEnvPort {

    /** proot 二进制是否就绪。 */
    fun isProotAvailable(): Boolean

    /**
     * 当前的默认环境（rootfs 路径 + proot 路径 + 默认发行版与镜像）。
     *
     * 为什么由端口提供而不是让页面拼：`filesDir` 与 `nativeLibraryDir` 只有
     * `Context` 知道，而 `feature:settings` 拿不到也不该拿 `Context`。
     */
    /**
     * 构造指定发行版的容器环境描述。
     *
     * @param distro 目标发行版。实现方负责按它选择 rootfs 目录与默认镜像源。
     */
    fun defaultEnvironment(distro: LinuxDistro): LinuxEnvironment

    /** rootfs 是否已解压到 [env]。 */
    /**
     * 校正已有 rootfs 的权限位（幂等）。
     *
     * ## 为什么需要它，而不是让用户重装
     *
     * 旧版解压参数缺 `-p`，toybox tar 按 umask 重算权限，rootfs 里
     * 目录成了 0700、文件成了 0600。后果不是「有点不对」，而是
     * **终端里任何命令都失败**：
     *
     * ```
     * proot error: execve("/usr/bin/sh"): No such file or directory
     * ```
     *
     * 而真因是链接器读不到 `/etc/ld.so.cache`（0600）→ 找不到 libc。
     * 报错完全指不到权限，用户只会以为「环境坏了」。
     *
     * 重装能解决，但那要重新下载几百 MB。这个方法直接在原 rootfs 上
     * 走一遍 `chmod`，几秒完成，且不碰用户数据。
     *
     * @return 是否成功（rootfs 不存在也算成功 —— 没什么要修的）。
     */
    suspend fun repairPermissions(env: LinuxEnvironment): Boolean
    fun isInstalled(env: LinuxEnvironment): Boolean

    /**
     * 下载并解压 rootfs。
     *
     * @param onLine 每收到一行（或一个进度片段）回调，用于 UI 显示最近状态。
     * @return 是否成功。
     */
    suspend fun install(
        env: LinuxEnvironment,
        onLine: (String) -> Unit,
    ): Boolean

    /** 在容器内执行一条命令（非交互），返回退出码。 */
    suspend fun exec(
        env: LinuxEnvironment,
        command: String,
        /** 超时秒数。默认 30 分钟（设置页里 apt 可能跑很久）。 */
        timeoutSeconds: Long = 30L * 60L,
        onLine: (String) -> Unit,
    ): Int

    /** 启动一个长驻会话（交互 shell / sshd / frpc）。返回句柄，用于停止。 */
    suspend fun startSession(
        env: LinuxEnvironment,
        args: List<String>,
        onLine: (String) -> Unit,
    ): SessionHandle

    // ── 存储管理（2026-10-23 新增）────────────────────────────────

    /**
     * 统计环境占用。**可能耗时一两秒**（rootfs 有 3 万+ inode）。
     *
     * ## 为什么归到端口而不是在 ViewModel 里算
     *
     * 它需要 `filesDir` / `nativeLibraryDir` 这些只有 `Context` 知道的路径，
     * 而 `feature:settings` 按项目纪律不碰 `Context`。
     * 与 [defaultEnvironment] 同一个理由。
     *
     * ## 为什么是 suspend 而不是普通函数
     *
     * 遍历 3 万+ 文件要走磁盘 IO。普通函数会让调用方（主线程）直接卡住 ——
     * 而这一页正是「用户点了刷新，期望看到转圈」的场景。
     * 声明成 suspend 强制实现方切到 IO 调度器。
     *
     * 默认实现返回 null：测试与「未装配」的场景不必实现它。
     */
    suspend fun usage(env: LinuxEnvironment): LinuxDiskUsage.EnvironmentUsage? = null

    /**
     * 清空共享 tmp 的宿主目录内容（保留目录本身）。
     *
     * @return 删除失败的文件数；0 表示全部清掉。`null` 表示未实现。
     */
    suspend fun clearSharedTmp(env: LinuxEnvironment): Int? = null

    /**
     * 彻底删除 rootfs（「重置环境」）。
     *
     * ## 为什么必须由端口做而不是 UI 直接 `deleteRecursively`
     *
     * 三件事 UI 做不到/做不全：
     *  1. 删之前要**先停掉容器**，否则 proot 进程占着文件，删一半失败；
     *  2. 删之后要**清掉来源标记**（`opt/.praxis-rootfs-origin`），
     *     否则下次装完界面会说「底层是 Ubuntu」而实际是刚装的新环境；
     *  3. 要保留共享 tmp（它是「垃圾文件」，不是环境的一部分）——
     *     用户点「重置环境」不该连带丢掉正在用的临时文件。
     *
     * @return 是否成功删除。
     */
    suspend fun resetEnvironment(env: LinuxEnvironment): Boolean

    /** 会话句柄：能查活、能停。 */
    interface SessionHandle {
        val isAlive: Boolean
        fun stop()
    }
}

/**
 * Linux 环境的状态机。
 *
 * ## 一条贯穿全类的原则：**先做准备，再改状态**
 *
 * 所有耗时动作（装、启动、换源）都是「设为 busy → 干活 → 回写结果」。
 * 中途任何一步失败都必须把 busy 复位，否则按钮永久禁用、用户只能杀 App。
 * 这里用 `try/finally` 保证这一点，而不是靠每个分支各写一次。
 */
class LinuxEnvViewModel(
    private val port: LinuxEnvPort,
    /**
     * 按发行版构造环境描述。
     *
     * 之所以是「函数」而不是「常量」：rootfs 目录、默认源都随发行版不同，
     * 而 UI 允许用户随时切换发行版。传常量就得在切换时重建 ViewModel、
     * 丢掉已下载的进度与日志。
     */
    private val envFactory: (LinuxDistro) -> LinuxEnvironment,
) : ViewModel() {

    private val _state = MutableStateFlow(LinuxEnvUiState())
    val state: StateFlow<LinuxEnvUiState> = _state.asStateFlow()

    /** 当前交互会话（容器 shell）。 */
    private var shellSession: LinuxEnvPort.SessionHandle? = null

    /** 当前 sshd 会话。 */
    private var sshdSession: LinuxEnvPort.SessionHandle? = null

    /** 当前 frpc 会话。 */
    private var frpSession: LinuxEnvPort.SessionHandle? = null

    /** 当前 vsftpd 会话。 */
    private var ftpSession: LinuxEnvPort.SessionHandle? = null

    init {
        refresh()
    }

    /**
     * 构造环境，并**统一注入 `sharedTmp`**。
     *
     * ## 为什么要有这个包装（而不是每个调用点自己 copy）
     *
     * `envFactory` 由 `:app` 注入，它不知道共享 tmp 这个开关（那是用户设置，
     * 存在这个 ViewModel 的状态里）。本类有 20+ 处 `env(...)` 调用，
     * 每处都写 `.copy(sharedTmp = _state.value.sharedTmp)` 的话：
     *
     *  - 漏一处 → 那条路径上共享 tmp 不生效，表现为「终端里能看到 /tmp、
     *    AI 工具里看不到」这类**只在特定入口复现**的怪 bug；
     *  - 加一个开关 → 要再改 20 处。
     *
     * 收在一个函数里，加开关只改这一处。
     *
     * ## 注意 `copy` 的时机
     *
     * 必须在**每次构造时**读当前状态（不能 `remember` 成一次性值）：
     * 用户可能先装好、再打开共享 tmp、再启动容器 —— 那条路径要求
     * 启动时用的是**最新**的开关值。
     */
    private fun envFor(distro: LinuxDistro): LinuxEnvironment =
        envFactory(distro).copy(sharedTmp = _state.value.sharedTmp)

    /** 重新探测环境（幂等，页面进入时调一次）。 */
    /**
     * 重新探测「能不能跑 / 装没装 / 在不在跑」，并据此生成摘要。
     *
     * @param keepSummary 为 true 时**不覆写** `summary`：
     *   用于「某个动作刚写完结论、只需要同步其余状态字段」的场景
     *   （见 `install` 的 finally）。默认 false = 纯状态探测，摘要按探测结果重算。
     */
    /**
     * 切换发行版（只改选中项，不动文件系统）。
     *
     * ## 2026-10-22 改：两个发行版现在**共用同一个 rootfs**
     *
     * 旧注释说的是「两个发行版的 rootfs 在不同目录，所以
     * 『Ubuntu 已装 / Kali 未装』是完全正常的组合」。
     * 那种组合现在**不可能出现** —— `ProotEnvPort.rootfsDirOf` 对两个
     * 发行版返回同一个 `filesDir/linux/shared`。
     *
     * 所以切发行版不再是「换一套文件系统」，而是：
     *
     * ```
     * 换选中项  →  （用户点「应用」时）换 /etc/apt 源 → apt install 差集工具
     * ```
     *
     * 只切选中项时仍要 [refresh]：`installed` 是共享的，但
     * 界面上的「当前发行版」文案与「底层来自哪个 tarball」提示要跟着变。
     *
     * ## 为什么这里不顺手换源
     *
     * 换源要真跑命令（几秒，可能失败）。把它塞进「点一下选择器」里，
     * 会变成「点一下卡两秒，还不知道成没成」。换源由
     * [applyDistroSources] 单独承接，界面给一个明确的「应用」动作。
     *
     * @return 是否真的切换了（相同发行版直接返回 false，避免无谓重组）。
     */
    fun selectDistro(distro: LinuxDistro): Boolean {
        if (_state.value.distro == distro) return false
        if (_state.value.busy) return false
        _state.update { it.copy(distro = distro, summary = "") }
        refresh()
        return true
    }

    /**
     * 切换发行版**并**把共用文件系统的 apt 源换过去。
     *
     * ## 为什么单独一个动作，而不是让 [selectDistro] 顺手做
     *
     * [selectDistro] 是「界面上选中哪个」——它必须瞬时、不能失败。
     * 而换源要在容器里真跑命令（`apt` 写文件），可能几秒。
     * 把两者合成一个的话，用户点一下选 Kali，界面会卡住，
     * 而且失败时「选中了但没换源」比「没选中」更难解释。
     *
     * ## 共用文件系统下这一步是必需的吗
     *
     * 是。两个发行版共用同一份 rootfs，而 `/etc/apt/sources.list*`
     * 只有一份。不换源的话：用户选 Kali、`apt install nmap`，
     * 实际下的是 Ubuntu 仓库的包 —— 包名碰巧一样能装上，
     * 但很多 Kali 工具（`metasploit-framework` 等）在 Ubuntu 源里根本没有，
     * 报错是 `Unable to locate package`，用户会以为「Kali 装不上工具」。
     *
     * @param onLine 进度回调
     * @return 是否成功换源（未安装环境时返回 false 且不报错）
     */
    suspend fun applyDistroSources(
        onLine: (String) -> Unit,
        timeoutSeconds: Long = 5L * 60L,
    ): Boolean {
        val current = _state.value
        if (current.busy) return false
        val env = envFor(current.distro)
        if (!port.isInstalled(env)) {
            onLine("环境尚未安装，无需换源。")
            return false
        }
        _state.update { it.copy(busy = true, summary = "正在切换到 ${current.distro.label} 的软件源…") }
        // 包一层：每条进度既写进 lastLine（界面看得到），
        // 也转给调用方的 onLine（将来接完整日志面板用）。
        // 不包的话调用方传进来的 onLine 只能拿到「函数自己的输出」，
        // 而 `publish` 那条路走不到 —— 界面上什么都不会动。
        val emit: (String) -> Unit = { line ->
            publish(line)
            onLine(line)
        }
        return try {
            val command = ProotCommand.sourceSwitchCommand(env)
            emit("$ ${command.take(160)}")
            var code = -1
            port.exec(env, command, timeoutSeconds) { line ->
                emit(line)
            }.also { code = it }
            if (code == 0) {
                // 换源成功后必须 refresh：`activeSources` 标记刚被改写，
                // 不重读的话「应用 XX 源」按钮会一直挂在那里不消失。
                refresh(keepSummary = true)
                _state.update {
                    it.copy(busy = false, summary = "已切换到 ${current.distro.label} 的软件源。")
                }
                onLine("换源完成。")
                true
            } else {
                _state.update {
                    it.copy(busy = false, summary = "换源失败（退出码 $code）。")
                }
                onLine("换源失败（退出码 $code）。")
                false
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, summary = "换源出错：${e.message}") }
            onLine("换源出错：${e.message ?: e::class.simpleName}")
            false
        }
    }

    fun refresh(keepSummary: Boolean = false) {
        val env = envFor(_state.value.distro)
        val prootOk = port.isProotAvailable()
        val installed = port.isInstalled(env)
        // 共享 rootfs 的两个独立事实，每次 refresh 都重读：
        //  - origin：底层从哪个镜像解出来（只有重装才变）；
        //  - activeSources：/etc/apt 当前指向谁（换源就变）。
        val origin = if (installed) env.rootfsOrigin() else null
        val activeSources = if (installed) env.activeSources() else null
        val derived = when {
            !prootOk -> "缺少 proot，无法运行 Linux 环境。"
            !installed -> "尚未安装 ${_state.value.distro.label} 环境。"
            shellSession?.isAlive == true -> "容器运行中。"
            else -> "已安装，未运行。"
        }
        _state.update {
            it.copy(
                prootAvailable = prootOk,
                installed = installed,
                running = shellSession?.isAlive == true,
                rootfsOrigin = origin,
                activeSources = activeSources,
                summary = if (keepSummary && it.summary.isNotBlank()) it.summary else derived,
            )
        }
    }

    /**
     * 安装用户勾选的软件包组。
     *
     * ## 与 [install] 的区别
     *
     * | | [install] | 本方法 |
     * |---|---|---|
     * | 做什么 | 解压内置 rootfs 覆盖整个环境 | 在已有环境里 `apt-get install` |
     * | 耗时 | 分钟级（解压几百 MB） | 分钟级（下载 + dpkg） |
     * | 破坏性 | **会丢掉已装的包与 /root** | 只增不减 |
     * | 前置 | proot 可用 | 已安装 且 容器未运行 |
     *
     * ## 为什么容器在跑时直接拒绝
     *
     * `apt` / `dpkg` 用 `/var/lib/dpkg/lock` 保证独占。容器里如果已经有
     * 人在跑 apt（或 sshd 正在被 apt 升级），第二条会报
     * `Could not get lock`。这个错误对用户毫无意义，而且会让人以为是权限问题。
     *
     * 更实际的原因：`exec` 走的是**另起一个 proot 进程**，它与用户
     * 交互 shell 里的 apt 是两个互不知情的进程，锁竞争的结果不可预期。
     * 所以要求先停容器 —— 这与 UI 上「运行中禁用勾选」是同一件事的两层保险。
     *
     * ## 为什么成功后要 `refreshUsage`
     *
     * 装完 Python + Java 可能多占 400 MB。不刷新的话设置页的
     * 「环境占用」还是装之前那个数字，用户会以为安装没生效。
     */
    fun installPackages(groupIds: Set<String>) {
        if (_state.value.busy) return
        if (!_state.value.installed) {
            _state.update { it.copy(summary = "环境尚未安装，无法安装软件包。") }
            return
        }
        if (_state.value.running) {
            _state.update { it.copy(summary = "容器运行中，请先停止容器再安装软件包。") }
            return
        }
        val script = buildPackageInstallScript(groupIds)
        if (script.isEmpty()) {
            // 空脚本会「成功」跑完但什么都不装 —— 必须明确拒绝，
            // 否则用户看到进度走完、状态显示成功，却什么都没变。
            _state.update { it.copy(summary = "没有选中任何软件包组。") }
            return
        }

        val env = envFor(_state.value.distro)
        _state.update { it.copy(busy = true, summary = "正在安装软件包…") }
        viewModelScope.launch {
            try {
                val code = port.exec(env, script) { line -> publish(line) }
                if (code == 0) {
                    _state.update { it.copy(summary = "软件包安装完成。") }
                    // 装完体积变了，立刻重算（见 KDoc）。
                    refreshUsage()
                } else {
                    _state.update {
                        it.copy(
                            summary = "软件包安装失败（退出码 $code）。" +
                                "常见原因：网络不通、换源后索引未更新。",
                        )
                    }
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ── 存储管理（2026-10-23 新增，对齐 Operit 的 storage_management_title）──

    /**
     * 重新统计磁盘占用。
     *
     * ## 为什么不在 `refresh()` 里顺手算
     *
     * `refresh()` 在每次页面重组/返回时都可能触发，而统计要遍历 3 万+ 文件。
     * 挂进去会让「进这一页」变慢一两秒，而绝大多数时候用户根本不看体积。
     *
     * 所以体积由**用户显式点「刷新」**或**安装完成时**触发（见 `install` 末尾）。
     * 这与 Operit 的 `refresh_size` 按钮是同一个交互。
     *
     * ## 为什么并发保护用 `calculatingUsage` 而不是 `busy`
     *
     * `busy` 是「有长任务在跑」（安装/启动），会让安装按钮禁用。
     * 统计只是个只读操作，不该把整页锁住 —— 用户完全可以边统计边看别的。
     */
    fun refreshUsage() {
        if (_state.value.calculatingUsage) return
        if (!_state.value.installed) {
            // 未安装时没有可统计的东西。给一个明确的空值而不是「0 B」。
            _state.update { it.copy(usage = null, calculatingUsage = false) }
            return
        }
        _state.update { it.copy(calculatingUsage = true) }
        viewModelScope.launch {
            val env = envFor(_state.value.distro)
            // `runCatching` 包住：统计失败（权限、IO）不该让页面崩，
            // 显示「统计失败」比整页异常好。
            val result = runCatching { port.usage(env) }.getOrNull()
            _state.update { it.copy(usage = result, calculatingUsage = false) }
        }
    }

    /**
     * 清空共享 tmp。
     *
     * ## 为什么要先停容器
     *
     * 容器在跑时 `/tmp` 里有活着的进程持有的文件（socket、锁、临时 fd）。
     * 直接删会出现「部分文件删不掉」+「容器内程序行为异常」两种结果。
     * 先停再清是唯一可靠顺序 —— 所以这里不做「静默跳过」，
     * 而是明确要求停止（UI 上按钮在运行中置灰）。
     */
    fun clearSharedTmp() {
        if (_state.value.busy) return
        viewModelScope.launch {
            val env = envFor(_state.value.distro)
            _state.update { it.copy(busy = true, summary = "正在清空临时文件…") }
            try {
                val failed = runCatching { port.clearSharedTmp(env) }.getOrNull()
                val summary = when {
                    failed == null -> "当前端口不支持清空临时文件。"
                    failed == 0 -> "临时文件已清空。"
                    // 删除失败几乎总是「容器还在跑、文件被占用」。
                    // 直接告诉用户该怎么做，而不是只说「失败」。
                    else -> "$failed 个文件删不掉，请先停止容器再试。"
                }
                _state.update { it.copy(busy = false, summary = summary) }
                // `publish` 而不是直接改 summary：它同时更新 `lastLine`，
                // 让页面顶部那一行「最近输出」也跟上 —— 用户点完按钮
                // 若只看 summary 变，会觉得「没发生什么」。
                publish(summary)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * 重置环境（删除 rootfs）。
     *
     * ## 为什么单独一个动作而不是复用安装
     *
     * 安装是「有则跳过、无则下载」，**不会删**已有的 rootfs（见 `install` 里
     * 的 `if (!port.isInstalled(env))` 判断）。用户想「重装一个干净的」
     * 必须能真正删掉旧的 —— 否则装坏了的容器永远修不好。
     */
    fun resetEnvironment() {
        if (_state.value.busy) return
        viewModelScope.launch {
            val env = envFor(_state.value.distro)
            _state.update { it.copy(busy = true, summary = "正在删除环境…") }
            try {
                // 先停容器：端口实现里也会停，但这里显式再停一次是双保险 ——
                // 若端口实现漏了，UI 侧至少把已知会话断掉。
                stop()
                val ok = runCatching { port.resetEnvironment(env) }.getOrDefault(false)
                _state.update {
                    it.copy(
                        busy = false,
                        summary = if (ok) "环境已删除，可以重新安装。" else "删除失败：容器可能仍在运行。",
                        // 删掉之后体积与安装状态都不再成立，必须清掉，
                        // 否则用户会看到「未安装」但体积还显示 500 MB。
                        usage = null,
                    )
                }
                refresh()
                publish(_state.value.summary)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * 从持久层同步共享 tmp 开关。
     *
     * ## 为什么是「同步」而不是「设置」
     *
     * `sharedTmp` 的**唯一真源是 DataStore**（`SettingsRepository.linuxSharedTmp`），
     * 不是这个 ViewModel。理由是它决定 proot 启动参数，必须跨重启稳定 ——
     * 而 ViewModel 会随进程销毁。
     *
     * 单向数据流：UI 开关 → `repository.setLinuxSharedTmp` → Flow 发出 → 本方法。
     * 若这里反过来去写 DataStore，就会形成「改 → 发 → 改」的环，
     * 表现为开关抖动或无限重组。
     *
     * ## 为什么相等要早退
     *
     * Flow 会在每次 `dataStore.data` 发射时重放当前值（参数变化、其它设置项
     * 被写都会触发整份 Preferences 发射）。不早退的话每次都会走一遍
     * `_state.update`，把 summary 反复改成同一句「需重启生效」，
     * 覆盖掉用户刚做别的动作得到的结论。
     *
     * ## 为什么改完要提示「需重启」
     *
     * 这个值进的是 proot 启动参数（`-b host:/tmp`）。容器已经在跑时改它，
     * 当前容器**不会**有任何变化。不提示的话用户会去容器里 `ls /tmp`
     * 看不到宿主的文件，认定开关坏了。
     */
    fun syncSharedTmp(enabled: Boolean) {
        if (_state.value.sharedTmp == enabled) return
        _state.update {
            it.copy(
                sharedTmp = enabled,
                summary = if (it.running) {
                    "已设为" + (if (enabled) "开启" else "关闭") + "共享临时目录，需重启容器生效。"
                } else {
                    it.summary
                },
            )
        }
    }

    // ── 安装 ────────────────────────────────────────────────────────

    /**
     * 安装（下载 + 解压 + 换源 + 写 DNS）。
     *
     * ## 顺序为什么是这几个
     *
     * 1. **换源要在 apt update 之前**：否则第一次 update 就在跑官方源，慢到超时；
     * 2. **DNS 要在换源之前**：没有 resolv.conf，apt 连域名都解析不了 ——
     *    而报错是 `Temporary failure resolving`，完全看不出是 DNS 配置问题；
     * 3. 解压完立刻校验 `/bin/sh` 存在，再谈后面的事。
     */
    fun install(release: UbuntuRelease, mirror: UbuntuMirror) {
        if (_state.value.busy) return
        val env = envFor(_state.value.distro).copy(release = release, mirror = mirror)
        _state.update {
            it.copy(busy = true, release = release, mirror = mirror, summary = "正在下载镜像…")
        }
        viewModelScope.launch {
            // ## `installedOk` 为什么要提到 try 外面
            //
            // `finally` 里要判断「这次装成功了没有」，好决定装完后要不要
            // 立刻统计一次体积。但 `try` 块内的 `val ok` 在 `finally` 里
            // **不可见**（作用域），而 `return@launch` 会直接跳到 finally，
            // 连赋值都拿不到。所以状态必须先声明在 try 外。
            var installedOk = false
            try {
                // ① 下载 + 解压
                val ok = port.install(env) { line -> publish(line) }
                if (!ok) {
                    _state.update { it.copy(summary = "安装失败：下载或解压出错。") }
                    return@launch
                }
                installedOk = true
                // ② 写 DNS（必须在换源之前，见上方 KDoc）
                port.exec(env, ProotCommand.dnsScript()) { line -> publish(line) }
                // ③ 换源 + apt update
                _state.update { it.copy(summary = "正在换源…") }
                port.exec(env, ProotCommand.switchMirrorScript(env)) { line -> publish(line) }
                _state.update {
                    it.copy(summary = "安装完成。${mirror.label} 已生效。")
                }
            } finally {
                // 无论成败都要复位 busy，否则按钮永久禁用。
                //
                // ## 为什么不在这里直接 `refresh()`（2026-10-21 修正）
                //
                // `refresh()` 会**无条件重写 summary**（「尚未安装」「已安装，未运行」…）。
                // 放在 finally 里就会把上面刚写好的结论（「安装失败：…」/「安装完成。…」）
                // 覆盖成一句废话 —— 用户看到的永远是「尚未安装 Linux 环境。」，
                // 完全不知道刚才那次点击是成功还是失败。
                //
                // 正确做法：`refresh(keepSummary = true)` —— 只刷新可用性/安装状态字段，
                // 摘要保留调用方的结论。
                _state.update { it.copy(busy = false) }
                refresh(keepSummary = true)

                // ## 装完立刻统计一次体积（2026-10-23 新增）
                //
                // 不接这一步的话，用户装完环境进设置页，看到的还是
                // 「点『刷新』统计」—— 而他刚花两分钟下载解压完，
                // 正想看看到底占了多少。此时点一次刷新是纯粹的多余动作。
                //
                // 放在 `refresh()` **之后**：`refresh` 负责更新 `installed`，
                // 而 `refreshUsage()` 开头就会在 `installed == false` 时早退。
                // 顺序反了这次统计会被直接吞掉。
                //
                // 只在成功时统计：失败时 rootfs 可能半残，统计出来的
                // 数字没有意义，还会让用户以为「装了一半占 200 MB」。
                if (installedOk) refreshUsage()
            }
        }
    }

    /** 换镜像源（已安装时可在不重装的情况下切）。 */
    fun switchMirror(mirror: UbuntuMirror) {
        if (_state.value.busy) return
        val env = envFor(_state.value.distro).copy(mirror = mirror)
        _state.update { it.copy(busy = true, mirror = mirror, summary = "正在换源…") }
        viewModelScope.launch {
            try {
                val code = port.exec(env, ProotCommand.switchMirrorScript(env)) { l -> publish(l) }
                _state.update {
                    it.copy(
                        summary = if (code == 0) {
                            "已切到 ${mirror.label}。"
                        } else {
                            "换源失败（退出码 $code），可换一个镜像再试。"
                        },
                    )
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ── 运行 ────────────────────────────────────────────────────────

    /** 启动交互容器会话。 */
    fun start() {
        if (_state.value.busy || shellSession?.isAlive == true) return
        val env = envFor(_state.value.distro)
        _state.update { it.copy(busy = true, summary = "正在启动容器…") }
        viewModelScope.launch {
            try {
                // 交互会话要传 loginArgs；真正的 IO 在 :app。
                shellSession = port.startSession(
                    env,
                    ProotCommand.loginArgs(env),
                ) { line -> publish(line) }
                _state.update { it.copy(running = true, summary = "容器运行中。") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** 停止容器会话（同时把 sshd / frpc 一起停 —— 容器没了它们也没意义）。 */
    fun stop() {
        stopSsh()
        stopFrp()
        // FTP 必须一起停：vsftpd 跑在 proot 容器里，容器没了它也没有意义。
        // 而且它的端口会一直占着 —— 用户下次启动容器再开 FTP 会遇到
        // "bind: Address already in use"。
        stopFtp()
        shellSession?.stop()
        shellSession = null
        _state.update { it.copy(running = false, summary = "容器已停止。") }
    }

    // ── SSH ─────────────────────────────────────────────────────────

    /** 改 SSH 配置（只改本地状态，点「应用」才落盘，见 [applySsh]）。 */
    fun updateSshConfig(cfg: SshConfig) {
        _state.update { it.copy(sshConfig = cfg) }
    }

    /**
     * 开关 SSH。
     *
     * 打开 = 装 openssh-server（首次）+ 起 sshd；关闭 = 停 sshd。
     *
     * ⚠️ 端口不合法时**不允许打开**：让它打开再启动失败，用户只会看到
     * 「已启用」但连不上，排查成本高得多。
     */
    fun toggleSsh(enabled: Boolean) {
        if (!enabled) {
            stopSsh()
            return
        }
        val cfg = _state.value.sshConfig
        if (!cfg.isPortUsable) {
            // 不打开开关，只把原因写进 lastLine（UI 里端口下方本来也有红字）。
            publish(cfg.portProblem ?: "端口不可用。")
            return
        }
        if (_state.value.busy) return
        val env = envFor(_state.value.distro)
        _state.update { it.copy(busy = true, summary = "正在准备 SSH…") }
        viewModelScope.launch {
            try {
                // ① 安装 + 写配置（幂等）
                port.exec(env, SshdScript.installScript(cfg)) { l -> publish(l) }
                // ② 起 sshd
                sshdSession = port.startSession(env, SshdScript.runArgs(cfg)) { l -> publish(l) }
                _state.update {
                    it.copy(
                        sshEnabled = true,
                        summary = "SSH 已启动，端口 ${cfg.port}。",
                    )
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** 设置 SSH 密码（不改开关状态）。 */
    fun setSshPassword(password: String) {
        if (password.isEmpty()) return
        val env = envFor(_state.value.distro)
        val user = _state.value.sshConfig.user
        viewModelScope.launch {
            port.exec(env, SshdScript.setPasswordScript(user, password)) { l -> publish(l) }
        }
    }

    /** 应用 SSH 配置（端口/用户名改了之后重新起一次 sshd）。 */
    fun applySsh() {
        if (!_state.value.sshEnabled) return
        stopSsh()
        toggleSsh(true)
    }

    private fun stopSsh() {
        sshdSession?.stop()
        sshdSession = null
        _state.update { it.copy(sshEnabled = false) }
    }

    // ── FRP ─────────────────────────────────────────────────────────

    fun updateFrpConfig(cfg: FrpConfig) {
        _state.update { it.copy(frpConfig = cfg) }
    }

    /**
     * 开关 FRP。
     *
     * 打开前会检查 [FrpConfig.isReady]：缺服务器地址就起不了，
     * 与其让它「已启用」但立刻退出，不如不开并说明缺什么。
     */
    fun toggleFrp(enabled: Boolean) {
        if (!enabled) {
            stopFrp()
            return
        }
        val cfg = _state.value.frpConfig
        if (!cfg.isReady) {
            publish("还缺服务器地址，填完才能启动 FRP。")
            return
        }
        if (_state.value.busy) return
        val env = envFor(_state.value.distro)
        _state.update { it.copy(busy = true, summary = "正在准备 FRP…") }
        viewModelScope.launch {
            try {
                // ① 装 frpc + 写 toml（幂等）
                port.exec(env, FrpScript.installScript(cfg)) { l -> publish(l) }
                // ② 起 frpc
                frpSession = port.startSession(env, FrpScript.runArgs()) { l -> publish(l) }
                _state.update {
                    it.copy(
                        frpEnabled = true,
                        summary = "FRP 已启动：公网端口 ${cfg.remotePort} → 本地 ${cfg.localPort}。",
                    )
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun stopFrp() {
        frpSession?.stop()
        frpSession = null
        _state.update { it.copy(frpEnabled = false) }
    }

    // ── FTP ─────────────────────────────────────────────────────────

    fun updateFtpConfig(cfg: FtpConfig) {
        _state.update { it.copy(ftpConfig = cfg) }
    }

    /**
     * 开关 FTP。
     *
     * ## 与 [toggleSsh] 的差别：多一道被动端口段校验
     *
     * sshd 只需要一个端口；vsftpd 的数据通道要一整段。段写错时
     * vsftpd **能启动**，但客户端连上后 `LIST` 会卡住 ——
     * 症状是「登录成功但看不到任何文件」，非常难自己排查出来。
     * 所以在打开前就拦住，而不是等用户看到空目录。
     */
    fun toggleFtp(enabled: Boolean) {
        if (!enabled) {
            stopFtp()
            return
        }
        val cfg = _state.value.ftpConfig
        if (!cfg.isPortUsable) {
            publish(cfg.portProblem ?: "端口不可用。")
            return
        }
        if (!cfg.isPassiveRangeValid) {
            publish(cfg.passiveRangeProblem ?: "被动端口段不可用。")
            return
        }
        if (!cfg.isUserUsable) {
            publish("用户名不能为空，也不能含空格、斜杠或冒号。")
            return
        }
        if (_state.value.busy) return
        val env = envFor(_state.value.distro)
        _state.update { it.copy(busy = true, summary = "正在准备 FTP…") }
        viewModelScope.launch {
            try {
                // ① 装 vsftpd + 建用户 + 写配置（幂等）
                port.exec(env, FtpServerScript.installScript(cfg)) { l -> publish(l) }
                // ② 起 vsftpd（前台，生命周期 == 会话生命周期）
                ftpSession = port.startSession(env, FtpServerScript.runArgs(cfg)) { l ->
                    publish(l)
                }
                val (lo, hi) = FtpServerScript.passiveRange(cfg)
                _state.update {
                    it.copy(
                        ftpEnabled = true,
                        summary = "FTP 已启动：端口 ${cfg.port}，被动段 $lo-$hi，根目录 ${cfg.homeDir}。",
                    )
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** 设置 FTP 密码（不改开关状态，与 [setSshPassword] 同构）。 */
    fun setFtpPassword(password: String) {
        if (password.isEmpty()) return
        val env = envFor(_state.value.distro)
        val user = _state.value.ftpConfig.user
        viewModelScope.launch {
            port.exec(env, FtpServerScript.setPasswordScript(user, password)) { l -> publish(l) }
        }
    }

    /**
     * 应用 FTP 配置（端口/用户名/被动段改了之后重启一次）。
     *
     * 与 [applySsh] 同一做法：停止再启动。vsftpd 不支持 reload，
     * 而且改端口必须重新 bind，重启是唯一正确的路径。
     */
    fun applyFtp() {
        if (!_state.value.ftpEnabled) return
        stopFtp()
        toggleFtp(true)
    }

    private fun stopFtp() {
        ftpSession?.stop()
        ftpSession = null
        _state.update { it.copy(ftpEnabled = false) }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /**
     * 把一行输出同时写进 lastLine 与日志。
     *
     * 只保留**最后一行**：装环境时 apt 会打几千行，全留在状态里会让 UI 重组爆掉，
     * 而且用户真正想看的是「现在在做什么」。
     */
    private fun publish(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        _state.update { it.copy(lastLine = trimmed.take(200)) }
    }

    override fun onCleared() {
        // 页面销毁时把会话停掉，避免留下孤儿 sshd 占端口。
        stop()
        super.onCleared()
    }
}