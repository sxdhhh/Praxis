package dev.praxis.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.praxis.core.workflow.WorkflowDefinition
import dev.praxis.core.workflow.WorkflowEngine
import dev.praxis.core.workflow.WorkflowRunResult
import kotlinx.coroutines.launch

/**
 * 工作流页的接线层。
 *
 * ## 为什么运行失败也要显示
 *
 * 工作流跑失败时最常见的疑问是「为什么停在这里」——
 * 所以无论成功失败都把 [WorkflowRunResult] 交给 UI，
 * 让轨迹（steps）自己说话，而不是只弹一句错误。
 *
 * ## 为什么 engine 从上层传入
 *
 * 与其他 feature 页一致（[ModelRoute]/[MemoryRoute]）：
 * feature 不依赖 DI 框架，单测与 @Preview 可以传假实现。
 */
@Composable
fun WorkflowRoute(
    engine: WorkflowEngine,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<WorkflowRunResult?>(null) }
    var runningId by remember { mutableStateOf<String?>(null) }

    WorkflowScreen(
        workflows = SampleWorkflows,
        result = result,
        runningId = runningId,
        onRun = { wf: WorkflowDefinition ->
            // 已有任务在跑就不叠加。
            //
            // 这里不能用 return@WorkflowScreen：那是**函数调用名**，
            // 而 onRun 这个 lambda 的隐式标签是 onRun，写错了编译不过。
            // 用 if 包住整段更直白，也避免多写一个标签。
            if (runningId == null) {
                runningId = wf.id
                result = null
                scope.launch {
                    // 引擎自己不会抛（它把异常收敛成 fail 结果），
                    // 但这里仍兜一层：协程内的意外不应该让页面卡在「运行中」。
                    result = runCatching { engine.run(wf) }
                        .getOrElse {
                            WorkflowRunResult(
                                success = false,
                                outputs = emptyList(),
                                steps = emptyList(),
                                error = "运行异常：${it.message}",
                            )
                        }
                    runningId = null
                }
            }
        },
        modifier = modifier,
    )
}
