package dev.praxis.feature.settings.env

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Linux 环境路由：把纯逻辑的 [LinuxEnvPort] 接到 ViewModel + 页面。
 *
 * ## 为什么 ViewModel 用固定 key
 *
 * `viewModel(key = "linux-env")`：在设置页与别的页面之间来回切时不重建，
 * 否则「正在装环境」会被打断一次 —— proot 进程还在跑，但状态没了，
 * 用户看到「未安装」，再装一次会撞上已存在的 rootfs。
 *
 * ## 为什么 envFactory 由端口给
 *
 * `LinuxEnvPort` 自己决定 rootfs / proot 的真实路径（`:app` 知道
 * `filesDir` 与 `nativeLibraryDir`），本层不碰任何 Android API。
 * 这样 `feature:settings` 能在纯 JVM 单测里构造假端口。
 */
@Composable
fun LinuxEnvRoute(port: LinuxEnvPort, modifier: Modifier) {
    val viewModel: LinuxEnvViewModel = viewModel(
        key = "linux-env",
    ) {
        LinuxEnvViewModel(port) { distro -> port.defaultEnvironment(distro) }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 换源是 suspend 且要真跑命令，需要自己的 scope。
    // 与 ViewModel 内部的 busy 门配合：这里只负责「发起」，
    // 状态与并发保护都在 VM 里，页面组合销毁时协程被取消也无副作用
    // （sourceSwitchCommand 是幂等的文件写入）。
    val scope = rememberCoroutineScope()

    LinuxEnvScreen(
        state = state,
        // 切发行版：只改选中项。**两个发行版共用同一个 rootfs**，
        // 所以这里不需要重探「另一个发行版装没装」—— 只有一个环境。
        onSelectDistro = { viewModel.selectDistro(it) },
        onApplyDistroSources = {
            scope.launch {
                // onLine 传空实现：ViewModel 内部已经把每行进度写进
                // `lastLine`（见它自己的 publish）。这里再传一个回调
                // 只会重复写同一个字段。保留参数是为了将来需要
                // 「完整日志」时能接上，不必改 VM 签名。
                viewModel.applyDistroSources(onLine = {})
            }
        },
        onInstall = { release, mirror -> viewModel.install(release, mirror) },
        onStart = { viewModel.start() },
        onStop = { viewModel.stop() },
        onSwitchMirror = { viewModel.switchMirror(it) },
        onSshToggle = { viewModel.toggleSsh(it) },
        onSshConfigChange = { viewModel.updateSshConfig(it) },
        onSshPasswordChange = { viewModel.setSshPassword(it) },
        onFrpToggle = { viewModel.toggleFrp(it) },
        onFrpConfigChange = { viewModel.updateFrpConfig(it) },
        onFtpToggle = { viewModel.toggleFtp(it) },
        onFtpConfigChange = { viewModel.updateFtpConfig(it) },
        onFtpPasswordChange = { viewModel.setFtpPassword(it) },
        // 软件包组：装到共用 rootfs 里（只增不减，与「重新安装」不同）。
        onInstallPackages = { ids -> viewModel.installPackages(ids) },
        modifier = modifier,
    )
}