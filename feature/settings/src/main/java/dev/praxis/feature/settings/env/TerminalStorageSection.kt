package dev.praxis.feature.settings.env

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.praxis.core.linux.LinuxDiskUsage
import dev.praxis.core.model.linux.VirtualKeyboardLayoutCodec
import dev.praxis.ui.widgets.MiuixSwitchWidget
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 存储管理卡（对齐 Operit 的 `storage_management_title` 分区）。
 *
 * ## 它回答的三个问题
 *
 * 用户在这一页真正想知道的事情按顺序是：
 *
 *  1. **占了多大**？—— 容器动辄几百 MB，用户需要判断要不要清。
 *     rootfs 解压后 3 万+ inode，不显示具体数字就只能瞎猜。
 *  2. **垃圾在哪**？—— 共享 tmp、下载缓存 tarball 是两个会悄悄长的部分。
 *     分开列出，用户才知道「清了能省多少」。
 *  3. **怎么重来**？—— 装坏了要能删掉重装。旧实现里「安装」是「有则跳过」，
 *     于是装坏的环境永远修不好。
 *
 * ## 为什么三个数字分开列而不是只给一个总数
 *
 * 只给总数时，用户点完「清空临时文件」看到总数只少了几 MB 会以为按钮没生效。
 * 分开列之后，`tmp` 那一行的数字直接变成 0，因果一目了然。
 *
 * @param usage 占用快照。`null` = 还没统计过（不是 0 字节，见其 KDoc）。
 * @param calculating 正在统计：刷新按钮显示转圈并禁用。
 * @param installed 是否已安装。未安装时整卡只显示一句说明 —— 对一个不存在的
 *   环境显示「0 B」会让用户以为环境装坏了。
 * @param sharedTmp 共享 tmp 是否开启。**未开启时 tmp 行不显示**：
 *   那时容器的 `/tmp` 在 rootfs 内部，其占用已算进 rootfs 那一行，
 *   再列一个恒为 0 的 tmp 行只会让人困惑。
 * @param running 容器是否在跑。在跑时清空 tmp 会让按钮置灰（见下方说明）。
 */
@Composable
internal fun StorageManagementCard(
    usage: LinuxDiskUsage.EnvironmentUsage?,
    calculating: Boolean,
    installed: Boolean,
    busy: Boolean,
    running: Boolean,
    sharedTmp: Boolean,
    onRefresh: () -> Unit,
    onClearTmp: () -> Unit,
    onReset: () -> Unit,
) {
    // 「重置环境」是破坏性动作，必须二次确认。
    // 它不是「清缓存」那种可撤销的轻操作 —— 删掉的是整个 rootfs（几百 MB、
    // 用户装过的所有包）。误触的代价是重新下载安装，所以确认框不可省。
    var confirmReset by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (!installed) {
                // 未安装：明确说明，不给任何数字与按钮。
                //
                // ## 为什么连「刷新」都不给
                //
                // 没装就没有可统计的东西，按钮点了只会得到 0 B —— 那看起来
                // 像统计坏了。宁可不给入口（与项目「不给假按钮」的纪律一致）。
                Text(
                    text = "尚未安装 Linux 环境，安装后可在此查看占用并清理。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                return@Column
            }

            // ── 总体积 ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "环境占用",
                        style = MiuixTheme.textStyles.headline1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // 三态：统计中 → 未统计 → 已统计。不能把前两态都显示成 0 B。
                        text = when {
                            calculating -> "统计中…"
                            usage == null -> "点「刷新」统计"
                            else -> usage.human()
                        },
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = onRefresh,
                    enabled = !calculating && !busy,
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    if (calculating) {
                        // ## 为什么用 `size` 参数而不是 `Modifier.size(14.dp)`
                        //
                        // miuix 的 `CircularProgressIndicator` 自己有 `size` 参数，
                        // 且它内部按 size 计算描边与动画半径。只改 `modifier.size`
                        // 会导致「控件框变成 14dp，但里面画的圈仍是默认 30dp」，
                        // 直接画出边界。两种都要给时优先用组件参数。
                        CircularProgressIndicator(
                            size = 14.dp,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    // 文案随状态变：转圈时不写「刷新」而写「统计中」，
                    // 让用户知道按钮已经吃了点击、且在干活。
                    Text(if (calculating) "统计中" else "刷新")
                }
            }

            // ── 明细 ────────────────────────────────────────────────
            //
            // 只在统计完成后显示明细。统计中显示明细会出现「0 B → 500 MB」
            // 的跳变，比什么都不显示更让人不安。
            if (usage != null && !calculating) {
                Spacer(Modifier.height(12.dp))
                UsageRow(label = "文件系统", value = usage.rootfs.human(), always = true)
                // tmp 行只在共享开启时显示（见函数 KDoc）。
                if (sharedTmp) {
                    UsageRow(label = "临时文件", value = usage.tmp.human(), always = false)
                }
                // 下载缓存：只有真的有残留时才显示。
                // 正常装完它就是 0（tarball 会被删），显示「0 B」多一行噪音。
                if (usage.tarball.bytes > 0L) {
                    UsageRow(
                        label = "下载缓存",
                        value = usage.tarball.human(),
                        always = false,
                    )
                }
                // 统计不完整时必须说出来：rootfs 里有少数目录不可读
                // （权限问题），那时总数偏小，用户以为不用清理。
                if (usage.rootfs.unreadableDirs > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "有 ${usage.rootfs.unreadableDirs} 个目录无权限读取，统计值偏小。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // ── 操作 ────────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "清空临时文件",
                    onClick = onClearTmp,
                    // ## 为什么容器在跑时禁用
                    //
                    // 容器在跑时 /tmp 里有活着的进程持有的文件（socket、锁、
                    // 临时 fd）。此时删会出现「部分文件删不掉」+「容器内程序
                    // 行为异常」。端口层会返回失败数，但更该做的是不让用户
                    // 走到那一步 —— 置灰 + 摘要提示「需先停止容器」。
                    enabled = sharedTmp && !running && !busy,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "重置环境",
                    onClick = { confirmReset = true },
                    // 运行中禁用：端口层会先停会话再删，但让用户先自己停
                    // 能避免「我还在容器里干活，环境突然没了」。
                    enabled = !running && !busy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error,
                    ),
                )
            }

            // 共享 tmp 未开启时说明为什么「清空临时文件」是灰的，
            // 而不是让用户盯着一个点不动的按钮猜。
            if (!sharedTmp) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "未开启共享临时目录：容器的 /tmp 在环境内部，随「重置环境」一起删除。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    // ## 为什么这里不用 `if (confirmReset)` 包住对话框
    //
    // 第一版写的是 `if (confirmReset) { ResetConfirmDialog(...) }`。
    // 那会导致点「取消」时对话框**瞬间消失**：组合被整个移除，
    // miuix 内部那套进出场动画没有机会播完（它有 `onDismissFinished`
    // 回调，就是给退出动画收尾用的 —— 外面一移除，那个回调永远不会被等）。
    //
    // 正确做法是**始终组合**、只用 `show` 控制显隐。
    //
    // ## 常驻组合不会留空壳
    //
    // 已核实 miuix 的 `WindowDialog` 实现：它把真正的内容放在
    // `popupHost = { visible, hostContent -> if (visible) { Dialog(...) } }` 里。
    // `show = false` 时**连 `Dialog` 都不创建** —— 没有空容器、没有透明遮罩、
    // 不会拦截点击。所以「始终组合」的代价只是多一层不可见的布局节点。
    ResetConfirmDialog(
        show = confirmReset,
        onDismiss = { confirmReset = false },
        onConfirm = {
            confirmReset = false
            onReset()
        },
    )
}

/**
 * 一行「标签 —— 数值」。
 *
 * @param always 为 true 时即使数值是 0 也显示。用于「文件系统」那一行 ——
 *   它是主体，显示 0 B 说明环境是空的（异常但真实），比隐藏更有信息量。
 */
@Composable
private fun UsageRow(label: String, value: String, always: Boolean) {
    if (!always && value == LinuxDiskUsage.humanBytes(0)) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
        )
    }
}

/**
 * 「重置环境」二次确认。
 *
 * ## 文案为什么把后果写全
 *
 * 「确定删除？」这种问法等于没问。用户需要知道三件事才能决定：
 *  - 删的是**整个环境**（装过的包全没）；
 *  - 需要**重新下载**（几百 MB、要联网）；
 *  - 临时文件**保留**（这是唯一不会被删的东西，也是「要不要先备份」的依据）。
 *
 * 只说「不可撤销」而不说这三点，用户点确认时仍然不知道自己失去了什么。
 */
@Composable
private fun ResetConfirmDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        onDismissRequest = onDismiss,
        title = "重置 Linux 环境",
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = "将删除整个文件系统与已安装的所有包，需要重新下载安装。" +
                    "共享临时目录里的文件会保留。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "删除",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 共享临时目录开关卡。
 *
 * ## 为什么它单独一张卡而不是塞进存储管理
 *
 * 存储管理那张卡是「看数字 + 清理」，全是对**已有文件**的操作；
 * 这个开关是**改变容器启动参数**的配置项，改了要重启容器才生效。
 * 混在一起会让用户以为「打开它就会立刻腾出空间」。
 *
 * ## 为什么说明里要写「重启生效」
 *
 * 它是 proot 的 `-b host:/tmp` 参数。容器在跑时改，当前容器不会有任何变化。
 * 不写这句话，用户开了开关去容器里看 `/tmp`，发现宿主的文件不在，
 * 会认定开关是坏的。
 */
@Composable
internal fun SharedTmpCard(
    enabled: Boolean,
    running: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column {
            MiuixSwitchWidget(
                title = "共享临时目录",
                description = "把容器的 /tmp 映射到 App 私有目录，便于清理与取出文件",
                checked = enabled,
                onCheckedChange = onCheckedChange,
            )
            if (running) {
                Text(
                    text = "容器正在运行，改动需重启容器生效。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * 虚拟键盘定制入口卡。
 *
 * ## 为什么这里自己持有弹窗状态
 *
 * 「打开编辑器」是**纯 UI 状态**（一个布尔），没有跨会话语义。
 * 放到 ViewModel 或 DataStore 里会让 `TerminalStorageState` 多两个字段，
 * 而那两个字段的唯一用途就是「这一页的弹窗开着没」——
 * 典型的把 UI 状态泄漏到状态层。
 *
 * ## 为什么显示「已自定义」
 *
 * 用户改过标签之后，键盘看起来与默认不同。若这一行还写「与 Operit 一致」，
 * 他会以为自己的改动没保存。所以按 `isDefault` 分两句文案。
 *
 * @param labels 当前生效的标签（14 个拍平）。
 * @param onSave 保存自定义标签。
 * @param onReset 恢复默认。
 */
@Composable
internal fun VirtualKeyboardCard(
    labels: List<String>,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit,
) {
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    val isDefault = VirtualKeyboardLayoutCodec.isDefault(labels)

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "虚拟键盘",
                style = MiuixTheme.textStyles.subtitle,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "2 行 × 7 列，补的是输入法打不出的键（方向、跳转、历史、修饰符）。" +
                    if (isDefault) {
                        "当前为默认布局。"
                    } else {
                        "已自定义键帽文字（功能不变）。"
                    },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = if (isDefault) "编辑键帽文字" else "编辑键帽文字（已修改）",
                onClick = { editorVisible = true },
            )
        }
    }

    VirtualKeyboardEditorDialog(
        labels = labels,
        show = editorVisible,
        onDismiss = { editorVisible = false },
        onSave = { next ->
            onSave(next)
            // 保存后关窗：用户点「保存」的意图就是「就这样」，
            // 留着弹窗会让人不确定是否已经生效。
            editorVisible = false
        },
        onReset = onReset,
    )
}

/**
 * 虚拟键盘定制弹窗（对齐 Operit 的 `VirtualKeyboardCustomizationDialog`）。
 *
 * ## 它只能改**标签**，不能改行为
 *
 * 14 个格子每个都对应一个固定语义（第 4 格永远是「光标到行首」）。
 * 用户能改的是键帽上显示什么。这是**有意**的限制，理由在
 * `terminalKeyLayoutOf` 的 KDoc 里：按位置绑行为，改标签不影响功能。
 *
 * 允许用户把「↑」改成「↓」而不变行为会让人困惑，所以这里在说明里写清
 * 「只改显示文字」。真正想换布局的诉求，本质是想要不同的键 —— 那是另一件事
 * （要加键就得改 `TerminalKeyId`，是功能开发而不是设置项）。
 *
 * ## 为什么用 `remember` 暂存草稿而不是每次编辑都写盘
 *
 * 用户改到一半点「取消」，DataStore 里不该留下半成品。所以：
 *  - 打开时把当前标签拷进 `draft`；
 *  - 点「保存」才写盘；
 *  - 点「取消」直接丢弃草稿。
 *
 * 若边输边写盘，就会出现「点到一半退出、键盘变了几个键」这种不可撤销的结果。
 *
 * @param labels 当前生效的标签（14 个）。
 * @param show 是否显示。
 * @param onDismiss 取消 / 关闭。
 * @param onSave 保存新标签（14 个）。
 * @param onReset 恢复默认。
 */
@Composable
internal fun VirtualKeyboardEditorDialog(
    labels: List<String>,
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit,
) {
    // 只在「打开的那一刻」拷贝一次草稿。
    //
    // key 用 `show`：每次从隐藏变成显示都重新拷，
    // 这样「上次没保存的草稿」不会跨会话残留。
    //
    // 不能用 `key = labels` —— 那样保存后 labels 一变就会把草稿重置掉，
    // 而保存完我们正要关窗，重置是多余的（且会让「保存」看起来闪一下）。
    var draft by remember(show) { mutableStateOf(labels) }

    WindowDialog(
        show = show,
        onDismissRequest = onDismiss,
        title = "虚拟键盘",
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = "只改键帽显示的文字，按键功能不变（位置决定功能）。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))

            // 两行 × 七列，与键盘实际布局一一对应。
            //
            // 用 `chunked(7)` 而不是写两次 `draft.take/drop`：
            // 行列数来自 `VirtualKeyboardLayoutCodec`，写死 7 会在
            // 将来改列数时静默错位。
            draft.chunked(VirtualKeyboardLayoutCodec.COLUMN_COUNT)
                .forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { colIndex, label ->
                            val flatIndex =
                                rowIndex * VirtualKeyboardLayoutCodec.COLUMN_COUNT + colIndex
                            // ## 每格一个 TextField
                            //
                            // 用 miuix `TextField`（`TextFieldValue` 重载）
                            // 而不是 `BasicTextField`：这是弹窗里的表单格子，
                            // 需要圆角容器与内边距保持一致。键盘本身用
                            // `BasicTextField` 是因为那条输入行要与输出区同宽无容器，
                            // 两者场景不同。
                            TextField(
                                value = TextFieldValue(label),
                                onValueChange = { next ->
                                    // 逐格替换：`toMutableList()` 后 set 再回写，
                                    // 比 `mapIndexed` 少一次整表分配。
                                    val mutable = draft.toMutableList()
                                    if (flatIndex in mutable.indices) {
                                        // 不截断、不过滤 —— 交给
                                        // `VirtualKeyboardLayoutCodec.sanitize`
                                        // 在保存时统一处理（那里有超长/空白的规则）。
                                        // 这一层若也截断，用户打字时会被「吃掉字符」，
                                        // 看起来像输入法坏了。
                                        mutable[flatIndex] = next.text
                                        draft = mutable
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                label = "",
                                singleLine = true,
                            )
                        }
                    }
                }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = "恢复默认",
                    onClick = {
                        // 本地也同步改草稿：用户点完能立刻看到默认标签，
                        // 而不是等下一帧 Flow 回来（那个延迟会让点击像没生效）。
                        draft = VirtualKeyboardLayoutCodec.DEFAULT_LABELS
                        onReset()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "保存",
                    onClick = {
                        // 保存前也 sanitize 一次（虽然 repository 会再做）：
                        // 这样 `onSave` 的参数与「最终会生效的东西」一致，
                        // 调用方不必猜「我传的脏数据会不会被改」。
                        onSave(VirtualKeyboardLayoutCodec.sanitize(draft))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
