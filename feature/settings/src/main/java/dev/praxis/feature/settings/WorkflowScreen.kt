package dev.praxis.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.praxis.core.workflow.StepRecord
import dev.praxis.core.workflow.WorkflowDefinition
import dev.praxis.core.workflow.WorkflowNode
import dev.praxis.core.workflow.WorkflowRunResult
import dev.praxis.ui.widgets.WorkflowBranchRow
import dev.praxis.ui.widgets.WorkflowNodeCard
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工作流页。
 *
 * ## 本轮的定位：先能跑，再能编
 *
 * 一个可视化编辑器（拖拽、连线、参数表单）是几周的工作量，而现在更缺的是
 * 「引擎到底能不能把一条流程跑对」这个验证。所以这一版只做两件事：
 *
 *  1. 列出内置示例工作流（三条件分支，覆盖 Trigger/Tool/Condition/Output）；
 *  2. 能点「运行」并看到**逐步轨迹**。
 *
 * 轨迹是本页最重要的部分：失败时它比错误消息有用——用户能看到「跑到了哪一步」。
 *
 * ## 为什么没做节点编辑器
 *
 * 编辑器要解决「节点增删 + 引用完整性（nextId 指向不存在的节点）+ 参数校验」，
 * 这三件在纯文本配置上更好做。等引擎在真机上跑稳了，再上可拖拽的画布。
 */
@Composable
fun WorkflowScreen(
    workflows: List<WorkflowDefinition>,
    result: WorkflowRunResult?,
    runningId: String?,
    onRun: (WorkflowDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SmallTitle("自动化")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "工作流由五类节点组成：开始 / 工具 / 条件 / 变换 / 结束。",
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "引擎有步数与时长上限，遇到环会停下而不是卡死。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        items(workflows, key = { it.id }) { wf ->
            // LazyColumn 的**单个 item 内多个 composable 会全部堆在 (0,0)**，
            // 不清空纵向位置。所以这一项的所有内容必须放进同一个 Column，
            // 否则节点卡片会叠在头卡上（看起来像只渲染了一张）。
            Column {
                // ── 工作流头卡：名称 + 节点数 + 运行按钮 ────────────────
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = wf.name, style = MiuixTheme.textStyles.body1)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${wf.nodes.size} 个节点",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                text = if (runningId == wf.id) "运行中…" else "运行",
                                onClick = { onRun(wf) },
                                enabled = runningId == null,
                            )
                        }
                    }
                }

                // ── 节点列表 ──────────────────────────────────────────
                //
                // 此前这里只显示「6 个节点」就结束了 —— 用户看不到流程长什么样，
                // 只能靠盲点「运行」再从执行轨迹反推结构。轨迹是**事后**证据，
                // 而节点列表是**事前**结构；排障时前者有用，理解时只有后者有用。
                //
                // 按 nodes 的声明顺序展示，不按执行顺序：声明顺序就是作者的心智模型，
                // 重排会让人对不上源码。
                wf.nodes.forEach { node ->
                    WorkflowNodeCard(
                        kindLabel = node.kindLabel(),
                        title = node.title,
                        details = node.detailRows(),
                        kindColor = node.kindColor(),
                        nextId = node.primaryNextId(),
                    )
                    // 条件节点有两个去向，单个 nextId 表达不全，补一行分支说明。
                    if (node is WorkflowNode.Condition) {
                        WorkflowBranchRow(thenId = node.thenId, elseId = node.elseId)
                    }
                }
            }
        }

        result?.let { r ->
            item {
                SmallTitle(if (r.success) "运行结果" else "运行失败")
            }

            // 跨模块的 public val 不能智能转换（编译器无法保证它不会变），
            // 必须先取到本地变量再判空。
            val errorText = r.error
            if (errorText != null) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = errorText,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (r.outputs.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = "输出", style = MiuixTheme.textStyles.body2)
                            Spacer(Modifier.height(4.dp))
                            r.outputs.forEach { out ->
                                Text(
                                    text = out,
                                    style = MiuixTheme.textStyles.body1,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            item { SmallTitle("执行轨迹") }
            items(r.steps) { step ->
                StepRow(step)
            }
        }
    }
}

@Composable
private fun StepRow(step: StepRecord) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = step.nodeTitle,
                style = MiuixTheme.textStyles.body2,
                color = if (step.isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
            )
            if (step.detail.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = step.detail.take(300),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  WorkflowNode → 控件参数映射
// ---------------------------------------------------------------------------

/**
 * 映射为什么放在这一层而不是控件里。
 *
 * `ui:widgets` 不依赖 `core:workflow`（与 `ToolCallCard`/`ModelConfigCard` 同一取舍）。
 * 映射放这里的好处：`core:workflow` 加一类节点时，只需要在这里补一个分支，
 * 控件不动；且这些函数可以用纯 JVM 单测直接验证（不需要 Compose 运行时）。
 */
internal fun WorkflowNode.kindLabel(): String = when (this) {
    is WorkflowNode.Trigger -> "开始"
    is WorkflowNode.Tool -> "工具"
    is WorkflowNode.Condition -> "条件"
    is WorkflowNode.Transform -> "变换"
    is WorkflowNode.Output -> "结束"
}

/**
 * 节点类型色。
 *
 * 语义分组而不是五个不同色：
 *  - 流程控制（开始/结束）用中性色 —— 它们不是「做了什么」，只是边界；
 *  - 有副作用的（工具）用 error 提示「这会动真实数据」；
 *  - 纯计算的（条件/变换）用 primary。
 *
 * 五个颜色各自不同会让图变成彩虹，反而看不出类别。
 */
@Composable
private fun WorkflowNode.kindColor(): Color = when (this) {
    is WorkflowNode.Trigger, is WorkflowNode.Output -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    is WorkflowNode.Tool -> MiuixTheme.colorScheme.error
    is WorkflowNode.Condition, is WorkflowNode.Transform -> MiuixTheme.colorScheme.primary
}

/** 单链后继。条件节点有两个分支，这里给 then 侧，else 侧由 [WorkflowBranchRow] 补。 */
internal fun WorkflowNode.primaryNextId(): String? = when (this) {
    is WorkflowNode.Trigger -> nextId
    is WorkflowNode.Tool -> nextId
    is WorkflowNode.Condition -> thenId
    is WorkflowNode.Transform -> nextId
    is WorkflowNode.Output -> null
}

/**
 * 要展示的参数行。
 *
 * 原则：**只展示会改变运行时行为的字段**。
 * 节点 id 是内部寻址用的，对用户没有意义（真正的连接关系已经在「→ x」里显示了），
 * 所以不列 —— 列了只会让每张卡片多一行噪声。
 */
internal fun WorkflowNode.detailRows(): List<Pair<String, String>> = when (this) {
    is WorkflowNode.Trigger -> emptyList()

    is WorkflowNode.Tool -> buildList {
        add("工具" to toolName)
        add("参数" to argsTemplate)
        // outputKey 为 null 表示这次调用结果不进上下文，后续节点引用不到 ——
        // 这是常见的手误，所以显式写出来（而不是省略这一行）。
        add("输出键" to (outputKey ?: "（不保存）"))
    }

    is WorkflowNode.Condition -> listOf("条件" to expression)

    is WorkflowNode.Transform -> listOf(
        "模板" to template,
        "输出键" to outputKey,
    )

    is WorkflowNode.Output -> if (template.isEmpty()) {
        emptyList()
    } else {
        listOf("模板" to template)
    }
}

/**
 * 内置示例工作流。
 *
 * 用「检查工作区里有没有 readme」这个真实可行的例子，而不是若数据：
 * 用户点下去能真的看到文件内容或「文件不存在」，才知道这套东西是活的。
 *
 * 五个节点覆盖全部类型，顺带验证条件分支两边都能走。
 */
val SampleWorkflows: List<WorkflowDefinition> = listOf(
    WorkflowDefinition(
        id = "sample-readme",
        name = "读取工作区 readme",
        nodes = listOf(
            WorkflowNode.Trigger(id = "t", nextId = "list"),
            WorkflowNode.Tool(
                id = "list",
                title = "列出工作区",
                toolName = "list_files",
                argsTemplate = "{}",
                outputKey = "listing",
                nextId = "check",
            ),
            WorkflowNode.Condition(
                id = "check",
                title = "是否包含 readme",
                expression = "{{listing}} contains readme",
                thenId = "read",
                elseId = "miss",
            ),
            WorkflowNode.Tool(
                id = "read",
                title = "读取 readme",
                toolName = "read_file",
                argsTemplate = "{\"path\":\"readme.md\"}",
                outputKey = "content",
                nextId = "out",
            ),
            WorkflowNode.Output(id = "out", title = "输出内容", template = "{{content}}"),
            WorkflowNode.Output(id = "miss", title = "没找到", template = "工作区里没有 readme.md"),
        ),
    ),
)
