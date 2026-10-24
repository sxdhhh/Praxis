package dev.praxis.feature.settings.env

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.praxis.core.linux.FrpConfig
import dev.praxis.core.linux.FtpConfig
import dev.praxis.core.linux.SshConfig
import dev.praxis.core.linux.LinuxDistro
import dev.praxis.core.linux.UbuntuMirror
import dev.praxis.core.linux.UbuntuRelease
import dev.praxis.ui.widgets.MiuixBadge
import dev.praxis.ui.widgets.MiuixSwitchWidget
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Linux 环境页（Ubuntu 容器 + SSH + FRP）。
 *
 * ## 界面是自写的，但参照了 apixiaoyuan 的三处形态
 *
 * | 参照 | 出处 | 用在哪 |
 * |---|---|---|
 * | 会话 chip 条（横滚、选中高亮、圆角） | `feature/terminal/TerminalScreen.kt` 的 `SessionChip` | 顶部的「环境状态条」 |
 * | 紧凑动作按钮（32dp 高、圆角 10dp） | 同文件的 `ActionButton` | 装环境 / 启动 / 停止 |
 * | 状态点 + 语义色全取莫奈 | 同文件的 `colorFor` / 工具栏 | 运行状态、错误提示 |
 *
 * 其余布局（分组、表单、开关）按 Praxis 自己的设置页范式（`Card` + `SmallTitle`）
 * 写，因为这一页是**配置页**而不是终端页 —— 把终端那套 chip 铺满整页会让
 * 「装环境」这种一次性动作和「天天看的输出」混在一起。
 *
 * ## 这一页为什么不放终端输出
 *
 * 装环境（下 30MB、apt update、apt install）会产生大量输出。把它塞进这个配置页
 * 会让页面变成一个半吊子终端；真正的终端在 `feature:terminal`。
 * 这里只给**阶段 + 一行最近输出**，完整输出引导用户去终端看
 * （`docker`/`proot` 的日志也会落盘，见 `ProotRunner`）。
 *
 * ## 一个必须如实说明的前提
 *
 * 这一页能把流程跑起来的**前提**是 proot 二进制已随 APK 打包（`libproot.so`）。
 * 若 `nativeLibraryDir` 里没有它，页面会明确显示「缺少 proot」并给出说明，
 * 而不是让用户点「安装」后看到一个含义不明的失败。
 */
@Composable
fun LinuxEnvScreen(
    state: LinuxEnvUiState,
    /** 切换发行版（Ubuntu / Kali）。**两个发行版共用同一份 rootfs**，见下方说明。 */
    onSelectDistro: (LinuxDistro) -> Unit,
    /**
     * 把当前选中发行版的 apt 源应用到共用 rootfs 上。
     *
     * ## 为什么它必须是单独一个动作
     *
     * [onSelectDistro] 只改「选中项」，瞬时完成。换源要真在容器里跑命令
     * （写 `/etc/apt/sources.list*`、删另一个发行版的残留源），可能几秒、可能失败。
     * 两者合成一个会让「点一下选择器」变成「卡两秒且不知道成没成」。
     */
    onApplyDistroSources: () -> Unit,
    onInstall: (UbuntuRelease, UbuntuMirror) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSwitchMirror: (UbuntuMirror) -> Unit,
    onSshToggle: (Boolean) -> Unit,
    onSshConfigChange: (SshConfig) -> Unit,
    onSshPasswordChange: (String) -> Unit,
    onFrpToggle: (Boolean) -> Unit,
    onFrpConfigChange: (FrpConfig) -> Unit,
    /**
     * FTP 开关与配置。
     *
     * 与 SSH/FRP 平级、同样必填 —— 三者都是「容器内服务」，
     * 少一个回调就会编译失败，这比给默认空实现更安全：
     * 空实现会让「点了开关没反应」这种静默失效有机会溜进去。
     */
    onFtpToggle: (Boolean) -> Unit,
    onFtpConfigChange: (FtpConfig) -> Unit,
    onFtpPasswordChange: (String) -> Unit,
    /**
     * 安装勾选的软件包组（对齐 Operit `SetupScreen`）。
     *
     * 默认空实现：`LinuxEnvRoute` 之外的调用点（测试、Preview）不必改。
     * 有默认值还有一个现实原因 —— 这一页的参数已经有 12 个，
     * 再加必填参数会让所有既有调用点同时编译失败。
     */
    onInstallPackages: (Set<String>) -> Unit = {},
modifier: Modifier = Modifier,
) {
    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.height(12.dp)) }

        // ── 状态卡（照 apixiaoyuan 的状态条语义：一眼看到能不能用）──
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.running) "● 容器运行中" else "○ 未运行",
                            style = MiuixTheme.textStyles.subtitle,
                            color = if (state.running) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        // 徽标显示**发行版**：用户切到 Kali 后，
                        // 这里若还写「24.04 LTS」会让人以为切换没生效。
                        MiuixBadge(text = state.distro.label)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    // 最近一行输出：装环境时要能看到进度，但又不能把整页变成终端。
                    state.lastLine?.let { line ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = line,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                    // 缺 proot 时**必须显眼**：这是整条链路的前提，缺了就什么都做不了。
                    if (!state.prootAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "缺少 proot 可执行文件（libproot.so）。" +
                                "当前 APK 没有把它打包进来，Linux 环境无法启动。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ── 安装 ────────────────────────────────────────────────────
        item { SmallTitle("安装环境") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    SpinnerRow(
                        title = "发行版",
                        current = state.distro,
                        options = LinuxDistro.entries.toList(),
                        labelOf = { it.label },
                        // 允许随时切换。**注意语义已变（2026-10-22）**：
                        // 两个发行版现在共用同一个 rootfs 目录，
                        // 所以切换不再是「换一套已装好的系统」，而是
                        // 「换接下来装什么 / 换哪套 apt 源」。
                        onSelect = onSelectDistro,
                        enabled = !state.busy,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = state.distro.summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )

                    // ── 共用文件系统的实情 ──────────────────────────
                    //
                    // 这一行是本页最重要的「如实告知」。不写的话：
                    // 用户选了 Kali、看到「已安装」、进容器 `cat /etc/os-release`
                    // 发现写着 Ubuntu → 直接判定是 bug。
                    if (state.installed) {
                        Spacer(Modifier.height(8.dp))
                        SharedRootfsNotice(
                            selected = state.distro,
                            origin = state.rootfsOrigin,
                        )
                    }

                    SpinnerRow(
                        title = "镜像源",
                        current = state.mirror,
                        options = UbuntuMirror.entries.toList(),
                        labelOf = { it.label },
                        onSelect = { onSwitchMirror(it) },
                        enabled = !state.busy,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            state.installed -> "已安装。换镜像源会立即生效（不需要重装）。"
                            // rootfs 现在**内置在 APK 里**，不再下载。文案必须改，
                            // 否则用户会去找「下载进度」而它根本不会出现。
                            state.distro == LinuxDistro.Kali ->
                                "镜像已内置在应用内（约 131 MB 压缩 / 约 500 MB 解压），" +
                                    "安装不需要联网。"
                            else ->
                                "镜像已内置在应用内（约 30 MB 压缩），安装不需要联网。"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )

                    // ── 「应用」：把选中发行版的源写进共用 rootfs ──
                    //
                    // 只在「已安装 且 底层与选中不一致」时出现。
                    // 一致时不显示 —— 一个点了不会改变任何东西的按钮
                    // 只会让人怀疑自己没点对。
                    if (state.installed && !state.sourcesMatchSelection) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onApplyDistroSources,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("应用 ${state.distro.label} 的软件源")
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "只换软件源与包索引，不会重装系统。" +
                                "已装的工具与 /root 下的文件都会保留。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onInstall(state.release, state.mirror) },
                        enabled = !state.busy && state.prootAvailable,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            if (state.installed) {
                                "重新安装 " + state.distro.label
                            } else {
                                "安装 " + state.distro.label
                            },
                        )
                    }
                    if (state.installed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "重新安装会用内置镜像**覆盖**整个共用文件系统：" +
                                "已装的软件与 /root 下的文件都会丢失。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ── 运行 ────────────────────────────────────────────────────
        item { SmallTitle("运行") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStart,
                            enabled = state.installed && !state.running && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("启动")
                        }
                        Button(
                            onClick = onStop,
                            enabled = state.running,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("停止")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // 容器没有常驻守护进程：这里的「启动」是起一个交互 shell 会话。
                        // 用户在终端页里操作那个会话；关掉就停。
                        text = "启动后会打开一个 proot 会话；" +
                            "日常命令建议在「终端」域里敲，那边有丝滑滚动与历史。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ── 软件包组 ────────────────────────────────────────────────
        //
        // 位置在「运行」之后、「SSH」之前。理由：
        //  - 在「安装环境 / 运行」之后 —— 它是装完环境才做的事，
        //    顺序与用户的实际操作顺序一致（装 → 跑 → 装软件）；
        //  - 在「SSH / FRP」之前 —— 那两个是**远程访问**配置，
        //    属于「把环境暴露出去」，是装完东西之后才考虑的下一步。
        //
        // 这一块自己判 `installed`（未安装时整块不渲染），
        // 所以这里不需要再套 if。
        item { SmallTitle("软件包") }
        item {
            PackageGroupsCard(
                installed = state.installed,
                running = state.running,
                busy = state.busy,
                onInstallGroups = onInstallPackages,
            )
        }

        // ── SSH ─────────────────────────────────────────────────────
        item { SmallTitle("SSH 服务器") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column {
                    MiuixSwitchWidget(
                        title = "启用 SSH",
                        description = "在容器内运行 openssh-server",
                        checked = state.sshEnabled,
                        onCheckedChange = onSshToggle,
                    )
                    if (state.sshEnabled) {
                        val cfg = state.sshConfig
                        Column(Modifier.padding(16.dp)) {
                            TextField(
                                value = cfg.port.toString(),
                                onValueChange = { text ->
                                    // 只接受数字：非数字时保持原值，避免用户输入
                                    // 「22a」后得到一个 0 端口。
                                    text.toIntOrNull()?.let {
                                        onSshConfigChange(cfg.copy(port = it))
                                    }
                                },
                                label = "端口",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next,
                                ),
                            )
                            // 端口不合法时必须当场说清原因，而不是等启动失败。
                            cfg.portProblem?.let { problem ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = problem,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = cfg.user,
                                onValueChange = { onSshConfigChange(cfg.copy(user = it)) },
                                label = "用户名",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(10.dp))
                            var password by remember { mutableStateOf("") }
                            TextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    onSshPasswordChange(it)
                                },
                                label = "密码",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                ),
                            )
                            Spacer(Modifier.height(8.dp))
                            // 连法提示：用户最想知道「我该在电脑上敲什么」。
                            Text(
                                text = "连接方式：ssh -p ${cfg.port} ${cfg.user}@<本机 IP>\n" +
                                    "本机 IP 在「容器运行中」时显示（同一 Wi-Fi 下才能连）。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        // ── FTP ─────────────────────────────────────────────────────
        //
        // 位置：紧随 SSH 之后、FRP 之前。三者是同一类东西
        // （「让外部访问容器」），按「直连 → 文件传输 → 穿透」排列。
        item { SmallTitle("FTP 文件服务器") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column {
                    MiuixSwitchWidget(
                        title = "启用 FTP",
                        description = "在容器内运行 vsftpd，供电脑/手机客户端传文件",
                        checked = state.ftpEnabled,
                        onCheckedChange = onFtpToggle,
                    )
                    if (state.ftpEnabled || state.ftpConfig != FtpConfig()) {
                        val ftp = state.ftpConfig
                        Column(Modifier.padding(16.dp)) {
                            Row {
                                TextField(
                                    value = ftp.port.toString(),
                                    onValueChange = { text ->
                                        text.toIntOrNull()?.let {
                                            onFtpConfigChange(ftp.copy(port = it))
                                        }
                                    },
                                    label = "控制端口",
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next,
                                    ),
                                )
                                Spacer(Modifier.width(10.dp))
                                TextField(
                                    value = ftp.passivePorts,
                                    onValueChange = { onFtpConfigChange(ftp.copy(passivePorts = it)) },
                                    label = "被动端口段",
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Next,
                                    ),
                                )
                            }
                            // 两个端口问题都当场说清：被动段写错时 vsftpd 能启动，
                            // 但客户端连上后看不到任何文件 —— 那种失败极难自己定位。
                            ftp.portProblem?.let { problem ->
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = problem,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.error,
                                )
                            }
                            if (ftp.portProblem == null) {
                                ftp.passiveRangeProblem?.let { problem ->
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = problem,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.error,
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = ftp.user,
                                onValueChange = { onFtpConfigChange(ftp.copy(user = it)) },
                                label = "用户名",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(10.dp))
                            var ftpPassword by remember { mutableStateOf("") }
                            TextField(
                                value = ftpPassword,
                                onValueChange = {
                                    ftpPassword = it
                                    onFtpPasswordChange(it)
                                },
                                label = "密码",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                ),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "连接方式：FTP 客户端连 <本机 IP>，端口 ${ftp.port}，" +
                                    "用户名 ${ftp.user}，登录后只看到自己的家目录。\n" +
                                    "被动模式要放行 ${ftp.passivePorts}（路由器/防火墙）。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        // ── FRP ─────────────────────────────────────────────────────
        item { SmallTitle("内网穿透（FRP）") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column {
                    MiuixSwitchWidget(
                        title = "启用 FRP",
                        description = "把本地 SSH 端口映射到公网服务器",
                        checked = state.frpEnabled,
                        onCheckedChange = onFrpToggle,
                    )
                    if (state.frpEnabled) {
                        val frp = state.frpConfig
                        Column(Modifier.padding(16.dp)) {
                            TextField(
                                value = frp.serverAddr,
                                onValueChange = {
                                    onFrpConfigChange(frp.copy(serverAddr = it))
                                },
                                label = "frps 服务器地址",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                ),
                            )
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = frp.serverPort.toString(),
                                onValueChange = { text ->
                                    text.toIntOrNull()?.let {
                                        onFrpConfigChange(frp.copy(serverPort = it))
                                    }
                                },
                                label = "frps 端口（默认 7000）",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                ),
                            )
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = frp.token,
                                onValueChange = {
                                    onFrpConfigChange(frp.copy(token = it))
                                },
                                label = "token（与服务端一致，可空）",
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextField(
                                    value = frp.localPort.toString(),
                                    onValueChange = { text ->
                                        text.toIntOrNull()?.let {
                                            onFrpConfigChange(frp.copy(localPort = it))
                                        }
                                    },
                                    label = "本地端口",
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                    ),
                                )
                                TextField(
                                    value = frp.remotePort.toString(),
                                    onValueChange = { text ->
                                        text.toIntOrNull()?.let {
                                            onFrpConfigChange(frp.copy(remotePort = it))
                                        }
                                    },
                                    label = "公网端口",
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                    ),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "需要一台有公网 IP 的服务器跑 frps。" +
                                    "配置好后在外部用 公网端口 连接。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            if (!frp.isReady) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "还缺服务器地址，填完才能启动。",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 说明 ────────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "关于这套环境的边界",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "· 跑在 proot 里，不是虚拟机，也不要求 root。\n" +
                            "· 端口必须 ≥ 1024：普通应用没有绑定低端口的权限。\n" +
                            "· /sdcard 默认不挂进容器；需要传文件时在终端里显式挂载。\n" +
                            "· 容器与 App 同生共死：App 被系统杀掉，里面的进程也停。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/**
 * 共用文件系统的实情说明。
 *
 * ## 为什么这一段必须存在
 *
 * 2026-10-22 起两个发行版**共用同一个 rootfs**。于是会出现一种
 * 用户一定会踩、且一定会误判为 bug 的情况：
 *
 * ```
 * 用户从 Ubuntu 镜像装了环境 → 切到 Kali → 看到「已安装」
 * → 进容器 → cat /etc/os-release 写着 Ubuntu 22.04
 * → 「明明选了 Kali，怎么还是 Ubuntu？」
 * ```
 *
 * 这不是 bug，是共用文件系统的必然结果。唯一正确的做法是**在界面上说清楚**：
 * 底层是什么、当前源是什么、切发行版到底改变了什么。
 *
 * ## 三种组合都要能读明白
 *
 * | origin | 选中 | 这句话 |
 * |---|---|---|
 * | ubuntu | Ubuntu | 底层 Ubuntu，源 Ubuntu —— 完全一致 |
 * | ubuntu | Kali | 底层 Ubuntu base + Kali 源 —— 不是真 Kali，但工具能用 |
 * | kali | Ubuntu | 底层 Kali + Ubuntu 源 —— 同上，方向相反 |
 */
@Composable
private fun SharedRootfsNotice(
    selected: LinuxDistro,
    origin: String?,
) {
    val originDistro = origin?.let { raw ->
        LinuxDistro.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }

    // 没有标记：老版本装的环境（那时候两个发行版还各占一个目录）。
    // 如实说「不知道「而不是猜一个 —— 猜错比不猜更糟。
    if (originDistro == null) {
        Text(
            text = "共用文件系统：这份 rootfs 是旧版本装的，没有来源标记。" +
                "切换发行版只会换软件源，不会重装系统。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        return
    }

    val same = originDistro == selected
    val text = if (same) {
        "共用文件系统：底层 ${originDistro.label}，软件源也是 ${selected.label}。"
    } else {
        "共用文件系统：底层是 ${originDistro.label}，不是 ${selected.label}。" +
            "切换发行版只换软件源与包索引，不会重装系统；" +
            "已装的工具与 /root 下的文件都会保留。" +
            "想要真正的 ${selected.label} 底层，请点下面的「重新安装」。"
    }

    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (same) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantActions
        },
    )
}

/**
 * 一行「标题 + 当前值」的选择器（与外观页同一个写法）。
 *
 * `enabled = false` 时不响应点击 —— 当前只用于「有长任务在跑」时锁住选择器。
 * 注意**不再**用来表达「已安装后不能再换发行版」：
 * 共存式共用文件系统后，随时切换是正常且安全的行为。
 */
@Composable
private fun <T> SpinnerRow(
    title: String,
    current: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    val items = remember(options) { options.map { DropdownItem(title = labelOf(it)) } }
    val selectedIndex = remember(current, options) {
        options.indexOf(current).coerceAtLeast(0)
    }
    OverlaySpinnerPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        enabled = enabled,
        renderInRootScaffold = true,
        onSelectedIndexChange = { index ->
            if (enabled) {
                options.getOrNull(index)?.takeIf { it != current }?.let(onSelect)
            }
        },
    )
}