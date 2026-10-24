package dev.praxis.feature.settings.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.feature.settings.SettingsEntry
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.ui.navigation.LocalNavigator
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 设置首页：把 [SettingsEntry] 渲染成可点条目。
 *
 * ## 版式 1:1 照 InstallerX `MiuixPreferredPage.kt:236-270`
 *
 * ```
 * item { Spacer(Modifier.size(12.dp)) }
 * item { SmallTitle("个性化") }
 * item {
 *     Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
 *         MiuixNavigationItemWidget(title = ..., description = ..., onClick = ...)
 *         MiuixNavigationItemWidget(...)
 *     }
 * }
 * ```
 *
 * 三条必须照抄的细节，缺一条观感就不对：
 *
 *  1. **`Card` 里直排多行、行间不画分割线** —— miuix 的 `Card` 自身有行间距感，
 *     再补 `HorizontalDivider` 会显得脏（早期 Praxis 就补过，真机看着像表格）；
 *  2. **用 `MiuixNavigationItemWidget`（内部是 `ArrowPreference`）**，不要自己
 *     拼 `Row { Text; Icon }`：那一版**整行不可点**（没有 `clickable`），
 *     用户点标题没反应、只有点中文字才偶尔跳动 —— 这是本轮修掉的真缺陷；
 *  3. **`Card` 的 padding 写在外层 `Modifier` 上**，不是构造参数。
 *
 * ## 为什么这份清单住在 feature:settings，而不是 core:model
 *
 * 旧实现在 `core:model/FeatureBlock.kt` 里定义 `PraxisDomain.SETTINGS -> listOf(...)`，
 * 也就是**模型层持有 UI 入口清单**。三个后果：
 *
 * 1. 那份清单只能写 `id` 字符串（模型层不能引用 `PraxisRoute`，否则依赖倒置），
 *    于是必须靠 `byKey` 二次查表，于是有了「清单有、枚举没有」的整类 bug；
 * 2. 设置页要加一项，得改 `core:model` —— 改模型层只是为了加一个 UI 入口；
 * 3. 同一份条目在 `FeatureBlock` 与 `SettingsEntry` 各写一遍，靠人工同步。
 *
 * 现在入口清单就是 [SettingsEntry] 本身，每个条目自带 `route`（具名 Route）
 * 与展示文案。新增设置项只改这一个枚举，**编译期就能检查目的地存在**。
 *
 * ## 导航怎么发生的
 *
 * 通过 `LocalNavigator` 就地 `push(entry.route)`，不需要 `:app` 传
 * `onSettingsEntry` 回调。旧链路上这一个动作要穿过
 * `PraxisApp → FeatureHost → SettingsHost` 三层签名（第 12 项为加一个入口
 * 就改了四处）。现在加多少个设置页，签名都不动。
 */
@Composable
fun SettingsHomeScreen(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current

    SettingsList(modifier = modifier.fillMaxSize()) {
        item { Spacer(Modifier.size(12.dp)) }

        // ── 分组一：AI 行为 ────────────────────────────────────────
        item { SmallTitle("AI") }
        item {
            SettingsCard(
                SettingsEntry.Model,
                SettingsEntry.FunctionModel,
                SettingsEntry.SystemPrompt,
            ) { navigator.push(it.route) }
        }

        // ── 分组二：观感 ──────────────────────────────────────────
        item { SmallTitle("观感") }
        item {
            SettingsCard(SettingsEntry.Appearance) { navigator.push(it.route) }
        }

        // ── 分组三：能力 ──────────────────────────────────────────
        item { SmallTitle("能力") }
        item {
            SettingsCard(
                SettingsEntry.Tools,
                SettingsEntry.Permission,
                SettingsEntry.Memory,
                SettingsEntry.Workflow,
                SettingsEntry.Speech,
            ) { navigator.push(it.route) }
        }

        // ── 分组四：环境与信息 ────────────────────────────────────
        item { SmallTitle("环境与信息") }
        item {
            SettingsCard(
                SettingsEntry.Linux,
                SettingsEntry.TerminalPrefs,
                SettingsEntry.Packages,
                SettingsEntry.Log,
                SettingsEntry.About,
            ) { navigator.push(it.route) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 一组设置条目。
 *
 * ## 为什么用 `vararg` 而不是 `List<SettingsEntry>`
 *
 * 调用点现在是 `SettingsCard(A, B, C)` —— 分组边界一眼可见。
 * 传 List 要写 `listOf(...)`，多一层括号，且容易在第 3 项处漏逗号。
 *
 * ## 为什么 `onOpen` 是 `(SettingsEntry) -> Unit` 而不是每条各传一个 lambda
 *
 * 五组共 14 个条目，各传 lambda 就是 14 处重复的
 * `{ navigator.push(SettingsEntry.X.route) }`。收成一个，改导航方式只改一处。
 */
@Composable
private fun SettingsCard(
    vararg entries: SettingsEntry,
    onOpen: (SettingsEntry) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        entries.forEach { entry ->
            MiuixNavigationItemWidget(
                title = entry.title,
                description = entry.summary,
                onClick = { onOpen(entry) },
            )
        }
    }
}