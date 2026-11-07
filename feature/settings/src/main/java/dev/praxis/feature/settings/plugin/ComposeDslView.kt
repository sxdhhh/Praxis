package dev.praxis.feature.settings.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.plugin.ComposeDslComponents
import dev.praxis.core.plugin.ComposeDslNode
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 把插件给出的渲染树画成 Compose 界面。
 *
 * ## 白名单在 core:plugin 里
 *
 * 哪些组件能画由 [ComposeDslComponents.RENDERABLE] 决定，不在这里散落判断 ——
 * 这样「能不能画」是纯逻辑、可单测，而这个文件只负责「怎么画」。
 *
 * ## 不支持的组件必须画出来
 *
 * 静默跳过会让用户以为自己的布局写错了。所以三种情况分得很清楚：
 *
 * | 情况 | 显示 |
 * |---|---|
 * | 能画 | 正常渲染 |
 * | Operit 有、Praxis 还没实现 | 「不支持的组件：X（尚未实现）」 |
 * | 根本不是组件 | 「未知组件：X」 |
 *
 * 前两种的措辞不同是有意的：一个是我们的进度问题，一个是脚本写错了。
 *
 * ## 深度上限
 *
 * 脚本可以构造出自引用的树（children 指向自己）。递归渲染会直接栈溢出崩溃，
 * 所以在 [MAX_DEPTH] 处停下并显示提示 —— 崩溃比一句提示难查得多。
 */
@Composable
fun ComposeDslView(
    node: ComposeDslNode,
    modifier: Modifier = Modifier,
    depth: Int = 0,
    onAction: (String) -> Unit = {},
) {
    if (depth > MAX_DEPTH) {
        UnsupportedCard(
            text = "嵌套层级超过 " + MAX_DEPTH + " 层，已停止渲染",
            modifier = modifier,
        )
        return
    }

    val base = if (node.fillMaxWidth) modifier.fillMaxWidth() else modifier
    val padded = node.paddingDp?.let { base.padding(it.dp) } ?: base

    when (node.type) {
        "Column" -> Column(
            modifier = padded,
            verticalArrangement = node.spacingDp
                ?.let { Arrangement.spacedBy(it.dp) }
                ?: Arrangement.Top,
        ) {
            node.children.forEach { child ->
                ComposeDslView(child, depth = depth + 1, onAction = onAction)
            }
        }

        "Row" -> Row(
            modifier = padded,
            horizontalArrangement = node.spacingDp
                ?.let { Arrangement.spacedBy(it.dp) }
                ?: Arrangement.Start,
        ) {
            node.children.forEach { child ->
                ComposeDslView(child, depth = depth + 1, onAction = onAction)
            }
        }

        "Box" -> Box(modifier = padded) {
            node.children.forEach { child ->
                ComposeDslView(child, depth = depth + 1, onAction = onAction)
            }
        }

        "Spacer" -> Spacer(
            Modifier.height((node.prop("height")?.trim()?.toIntOrNull() ?: 8).dp),
        )

        "Text" -> Text(
            text = node.prop("text").orEmpty(),
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = padded,
        )

        "Card" -> Card(modifier = padded) {
            Column(modifier = Modifier.padding(16.dp)) {
                node.children.forEach { child ->
                    ComposeDslView(child, depth = depth + 1, onAction = onAction)
                }
            }
        }

        "TextButton" -> TextButton(
            onClick = { node.actionId?.let(onAction) },
            enabled = node.prop("enabled")?.equals("false", ignoreCase = true) != true,
            modifier = padded,
        ) {
            Text(text = node.prop("text").orEmpty())
        }

        "Button" -> Button(
            onClick = { node.actionId?.let(onAction) },
            enabled = node.prop("enabled")?.equals("false", ignoreCase = true) != true,
            modifier = padded,
        ) {
            Text(text = node.prop("text").orEmpty())
        }

        "LazyColumn" -> LazyColumn(modifier = padded) {
            items(node.children) { child ->
                ComposeDslView(child, depth = depth + 1, onAction = onAction)
            }
        }

        "Divider", "HorizontalDivider" -> HorizontalDivider(
            color = MiuixTheme.colorScheme.dividerLine,
            modifier = padded,
        )

        else -> UnsupportedCard(
            text = unsupportedText(node.type),
            modifier = padded,
        )
    }
}

/** 这一层最多画多少层。超过就停，避免自引用树把栈打爆。 */
private const val MAX_DEPTH = 48

private fun unsupportedText(type: String): String =
    if (ComposeDslComponents.isKnown(type)) {
        "不支持的组件：" + type + "（Praxis 尚未实现）"
    } else {
        "未知组件：" + type
    }

@Composable
private fun UnsupportedCard(text: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(12.dp),
        )
    }
}
