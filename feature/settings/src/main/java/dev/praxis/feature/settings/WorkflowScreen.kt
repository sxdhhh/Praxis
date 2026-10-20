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
import androidx.compose.ui.unit.dp
import dev.praxis.core.workflow.StepRecord
import dev.praxis.core.workflow.WorkflowDefinition
import dev.praxis.core.workflow.WorkflowNode
import dev.praxis.core.workflow.WorkflowRunResult
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
