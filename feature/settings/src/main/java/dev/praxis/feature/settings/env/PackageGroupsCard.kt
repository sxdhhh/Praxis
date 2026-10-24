package dev.praxis.feature.settings.env

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.linux.BuiltInPackageGroups
import dev.praxis.core.model.linux.LinuxPackageGroup
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 软件包组卡（对齐 Operit `SetupScreen` 的分类卡）。
 *
 * ## 交互形态
 *
 * 每类一行（`ArrowPreference`，可展开）：
 *  - 收起时：标题 + 摘要 + 是否已选（`已选` / 未选），点一下展开；
 *  - 展开后：`CheckboxPreference` 勾选 + 实际包名列表。
 *
 * ## 为什么默认全部收起
 *
 * 六组全展开时整页长达三屏，而用户通常只勾一组。收起状态下一屏能看到全部六组，
 * 「有哪些可装」一眼可见 —— 那是这一块的主要信息。展开是为了看包名细节，
 * 属于次要信息，按需点开。
 *
 * ## 为什么展开状态用 `rememberSaveable` 而不是 remember
 *
 * 用户在装包过程中切到别的页面（比如去终端看输出）再回来，
 * 展开的那组应该还开着 —— 否则他得重新找回自己刚才在看的组。
 * 这与「输入框内容」不同：后者是数据，前者是纯 UI 状态。
 *
 * ## 为什么勾选状态存在 `rememberSaveable` 里而不是 DataStore
 *
 * 勾选是**一次性动作的中间状态**：勾完点「安装选中项」就结束了，
 * 没有「下次打开还记着我上次勾了什么」的语义（用户可能这次装 Python、
 * 下次装 Node，保留旧勾选反而容易误装）。
 *
 * 与「共享 tmp」「键帽标签」那种**配置**不同 —— 那两个是跨越会话的设定。
 *
 * @param installed 是否已安装环境。未安装时整块不显示（没有容器就装不了包）。
 * @param running 容器是否在跑。在跑时禁用 —— apt 需要独占 dpkg 锁。
 * @param busy 有长任务在进行。
 * @param onInstallGroups 点「安装选中项」时回调，参数是选中的组 id。
 */
@Composable
internal fun PackageGroupsCard(
    installed: Boolean,
    running: Boolean,
    busy: Boolean,
    onInstallGroups: (Set<String>) -> Unit,
) {
    if (!installed) {
        // 未安装时**不渲染**：装不了包的时候给一堆勾选框，
        // 用户勾完点安装只会得到「环境尚未安装」的错误。
        return
    }

    // 勾选集合：`rememberSaveable` 存不了 Set，用 List 再转。
    // 用 List 而不是 HashSet：13 个以内的小集合，list 的 contains
    // 比 hash 计算更快，且 saveable 支持 List 的原生序列化。
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            BuiltInPackageGroups.forEach { group ->
                PackageGroupRow(
                    group = group,
                    selected = group.id in selectedIds,
                    expanded = expandedId == group.id,
                    // 容器在跑时不允许勾选：点了也装不了（dpkg 锁），
                    // 不如从源头避免「勾了半天发现按钮是灰的」。
                    enabled = !busy && !running,
                    onToggleExpand = {
                        // 手风琴式：点开另一个时把上一个收起。
                        // 允许多个同时展开会让页面变得很长，而且用户的注意力
                        // 本来就只在某一组上。
                        expandedId = if (expandedId == group.id) null else group.id
                    },
                    onToggleSelect = {
                        selectedIds = if (group.id in selectedIds) {
                            selectedIds - group.id
                        } else {
                            selectedIds + group.id
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 执行 ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Button(
                    onClick = { onInstallGroups(selectedIds.toSet()) },
                    enabled = selectedIds.isNotEmpty() && !busy && !running,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        if (selectedIds.isEmpty()) {
                            "请先选择要安装的组"
                        } else {
                            "安装选中的 ${selectedIds.size} 组"
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                // ## 这句必须写清楚代价
                //
                // apt 安装要联网（几百 MB）+ 几分钟。不写的话用户会在
                // 装到一半时以为卡死然后强杀 App —— 而那时 dpkg 可能
                // 正处于「已解压未配置」的中间态，下次 apt 会报
                // 「dpkg was interrupted」。
                text = when {
                    running -> "容器运行中，请先停止容器再安装（apt 需要独占包管理器锁）。"
                    else -> "安装需要联网下载（几十到几百 MB），耗时数分钟。" +
                        "过程中请勿退出，中断可能导致包管理器处于半配置状态。"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = if (running) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * 单个包组的一行。
 *
 * ## 为什么用 `ArrowPreference` 而不是自己拼 Row + 箭头图标
 *
 * `ArrowPreference` 是 miuix 专为此设计的：它自带箭头、按压反馈、
 * 正确的无障碍语义（`Role.Button` + 展开状态）。自己拼会漏掉后者，
 * 读屏用户听到的是一个没有状态的普通文本行。
 *
 * ## 为什么勾选框放在展开区而不是行首
 *
 * 行首放勾选框会让「点行 = 勾选」，而这一行的主要动作是**展开看细节**。
 * 两个动作抢同一个点击目标时，用户想展开却勾上了 —— 比多点一次糟得多。
 * 所以：点行 = 展开/收起，展开后点勾选框 = 选中。
 */
@Composable
private fun PackageGroupRow(
    group: LinuxPackageGroup,
    selected: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onToggleExpand: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    ArrowPreference(
        title = group.title,
        summary = if (selected) "已选 · ${group.summary}" else group.summary,
        enabled = true, // 展开/收起始终可用：看包名不需要装得动
        onClick = onToggleExpand,
        endActions = {
            Text(
                text = if (selected) "已选" else "",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        },
    )

    if (expanded) {
        Column(Modifier.padding(start = 8.dp)) {
            CheckboxPreference(
                title = "安装这一组",
                summary = null,
                checked = selected,
                // 禁用时传 null：miuix 的 `CheckboxPreference` 用
                // `onCheckedChange == null` 表示「只读展示」。
                // 传一个空 lambda 会让它看起来可点但点了没反应。
                onCheckedChange = if (enabled) {
                    { onToggleSelect() }
                } else {
                    null
                },
                enabled = enabled,
            )
            // 展开后给出**实际的包名**。这是「如实告知」：用户可能
            // 只想要 python3 而不想要 build-essential（200MB+）。
            Text(
                text = group.installArgs,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 2.dp),
            )
        }
    }
}