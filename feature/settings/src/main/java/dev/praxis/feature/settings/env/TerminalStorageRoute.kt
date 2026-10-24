package dev.praxis.feature.settings.env

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.praxis.core.data.settings.SettingsRepository
import dev.praxis.core.model.linux.VirtualKeyboardLayoutCodec
import kotlinx.coroutines.launch

/**
 * 把 `LinuxEnvViewModel` 的状态接成终端设置页要的 [TerminalStorageState]。
 *
 * ## 为什么复用 `key = "linux-env"` 的那个 ViewModel
 *
 * 存储数据（占用 / 是否安装 / 容器跑没跑）只有 `LinuxEnvViewModel` 有。
 * `SettingsHost` 是纯 `when` 分发、没有 NavHost，所以整页共享同一个
 * `ViewModelStoreOwner`；用同一个 key 取到的就是 `LinuxEnvRoute` 里那一个实例。
 *
 * 换句话说：用户在环境页点「安装」、装到一半切到终端设置页，
 * 这里看到的是**同一份进行中的状态** —— 而不是一份「未安装」的假快照。
 *
 * ## 为什么端口为 null 时返回 `available = false`
 *
 * `:app` 没注入 `LinuxEnvPort` 时（单测、预览），这三块能力根本不存在。
 * 返回 `available = false` 让整块 UI 不渲染，页面回落到「只有字号/缓冲」，
 * 与加这个功能之前完全一致 —— 比渲染一堆死按钮诚实。
 *
 * ## 为什么进页面就自动统计一次
 *
 * `usage` 初始是 null（表示「还没统计」），UI 会显示「点刷新统计」。
 * 用户进这一页本来就带着「看看占多大」的意图，再让他多点一次是多余的。
 * 但**只有已安装时才统计** —— 未安装时遍历一个不存在的目录没有意义。
 *
 * 这一步的成本：rootfs 有 3 万+ inode，低端机约 1 秒。它跑在
 * `Dispatchers.IO`（端口实现里切的），并且界面显示「统计中…」，
 * 所以用户看到的是进度而不是卡顿。
 *
 * @param port Linux 环境端口。null = 未装配。
 * @param repository 设置仓库：共享 tmp 开关的唯一真源。
 */
@Composable
internal fun rememberTerminalStorageState(
    port: LinuxEnvPort?,
    repository: SettingsRepository,
): TerminalStorageState {
    // ## 为什么不在这里写 `if (port == null) return`
    //
    // 这个函数在 port 非空时会在内部调用 `viewModel(...)` / `collectAsState`。
    // 若在函数顶部提前 return，一旦 port 从 null 变成非空（`:app` 延迟注入、
    // 或 Preview 里替换了参数），**同一组合位置的 Composable 调用顺序会变**，
    // 而 Compose 要求每次重组调用序列一致 —— 表现是
    // 「`SlotTable` 不一致 / 状态被错位复用」这类极难定位的运行时错误。
    //
    // 拆成两个函数：外层只做分支（两分支都不依赖内层的状态槽），
    // 内层永远完整执行它那一套调用。
    return if (port == null) {
        TerminalStorageState()
    } else {
        rememberTerminalStorageStateOrThrow(port, repository)
    }
}

/**
 * 端口已装配时的真实实现。
 *
 * 单独一个函数是为了让 [rememberTerminalStorageState] 的分支
 * 不改变 Composable 调用序列（见那里的说明）。
 */
@Composable
private fun rememberTerminalStorageStateOrThrow(
    port: LinuxEnvPort,
    repository: SettingsRepository,
): TerminalStorageState {
    // ## 与 LinuxEnvRoute 完全相同的 key 与工厂
    //
    // 两处必须逐字一致（key + 构造参数）。不一致就会得到两个独立实例：
    // 环境页装了、设置页说未装 —— 而那是最难排查的一类 bug
    // （两边代码都对，只是拿的不是同一个对象）。
    val viewModel: LinuxEnvViewModel = viewModel(
        key = "linux-env",
    ) {
        LinuxEnvViewModel(port) { distro -> port.defaultEnvironment(distro) }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 共享 tmp 的持久层真源。单向数据流：
    //   开关 → repository.setLinuxSharedTmp → Flow → syncSharedTmp → UI
    val sharedTmp by repository.linuxSharedTmp.collectAsStateWithLifecycle(
        initialValue = state.sharedTmp,
    )
    LaunchedEffect(sharedTmp) {
        // ViewModel 只是镜像，真源在 DataStore（见 syncSharedTmp 的 KDoc）。
        viewModel.syncSharedTmp(sharedTmp)
    }

    // 进页面自动统计一次。key 用 `state.installed`：
    //  - 首次进入且已安装 → 统计；
    //  - 刚才在别处装完、回到这页 → 重新统计（否则显示的还是「未安装」时的空态）。
    LaunchedEffect(state.installed) {
        if (state.installed) viewModel.refreshUsage()
    }

    // 键帽标签：同样以 DataStore 为唯一真源。
    //
    // 用 `initialValue` 而非 `awaitFirst`：首帧就要有 14 个标签，
    // 否则卡片会先渲染一次空布局再「跳」成默认布局。
    val keyboardLabels by repository.virtualKeyboardLabels.collectAsStateWithLifecycle(
        initialValue = VirtualKeyboardLayoutCodec.DEFAULT_LABELS,
    )

    val scope = rememberCoroutineScope()

    return TerminalStorageState(
        available = true,
        usage = state.usage,
        calculatingUsage = state.calculatingUsage,
        installed = state.installed,
        busy = state.busy,
        running = state.running,
        // 用 DataStore 的值而不是 `state.sharedTmp`：后者是镜像，
        // 在 `syncSharedTmp` 执行前有一帧延迟，开关会闪一下。
        sharedTmp = sharedTmp,
        onRefreshUsage = { viewModel.refreshUsage() },
        onClearSharedTmp = { viewModel.clearSharedTmp() },
        onResetEnvironment = { viewModel.resetEnvironment() },
        onSharedTmpChange = { enabled ->
            // ## 只写 DataStore，不直接改 ViewModel
            //
            // 直接两边都写会有一帧不一致（ViewModel 先变、DataStore 后变），
            // 而 `LaunchedEffect(sharedTmp)` 又会把 DataStore 的**旧值**
            // 同步回来 —— 表现为「点了开关又弹回去」。
            // 单写 DataStore 后由 Flow 驱动，时序只有一个方向。
            scope.launch { repository.setLinuxSharedTmp(enabled) }
        },
        keyboardLabels = keyboardLabels,
        onSaveKeyboardLabels = { next ->
            // 同样只写 DataStore。这一处**不能**乐观更新本地：
            // `VirtualKeyboardEditorDialog` 已经用自己的 `draft` 做了
            // 即时反馈，再在状态层做一次乐观更新会让「保存前后」
            // 出现两条不同的数据源（Flow 回来后可能覆盖）。
            scope.launch { repository.setVirtualKeyboardLabels(next) }
        },
        onResetKeyboardLabels = {
            scope.launch { repository.resetVirtualKeyboardLabels() }
        },
    )
}