package dev.praxis.core.workflow

/**
 * 工作流节点。
 *
 * ## 为什么只有五类
 *
 * 节点类型每多一种，编辑器的 UI 与引擎的分支就要同步多一份。
 * 先只保留「能表达真实自动化」的最小集合：
 *
 *  - [Trigger]    —— 入口，声明工作流从哪里开始
 *  - [Tool]       —— 调用一个已注册工具
 *  - [Condition]  —— 二分支，根据上下文变量选路
 *  - [Transform]  —— 纯计算（模板插值），不产生副作用
 *  - [Output]     —— 终点，把结果交给用户
 *
 * 缺的那几种以及为什么不做：
 *  - **循环**：没有循环就自然没有「死循环」这个故障模式。真实的重复场景
 *    可以用「多次调用同一个工作流」表达，那种重复是有界的。
 *  - **并行**：工具层本身就是严格串行的（并发会因为「先写后读读到旧内容」
 *    产生错误结果），引擎也必须与它一致。
 *  - **子工作流**：会引入递归与栈深度问题，等真有需求再加。
 */
sealed interface WorkflowNode {

    /** 节点 id，在工作流内唯一。 */
    val id: String

    /** 友好的节点名，用于日志与编辑器展示。 */
    val title: String

    /** 入口。一个工作流有且仅有一个，且必须是第一个执行的节点。 */
    data class Trigger(
        override val id: String,
        override val title: String = "开始",
        /** 第一个要执行的节点 id；null 表示触发后直接结束。 */
        val nextId: String?,
    ) : WorkflowNode

    /**
     * 调用工具。
     *
     * [argsTemplate] 里的 `{{var}}` 会被上下文变量替换后再传给工具。
     * [outputKey] 非空时，工具输出写入上下文，供后续节点引用。
     */
    data class Tool(
        override val id: String,
        override val title: String,
        val toolName: String,
        val argsTemplate: String = "",
        val outputKey: String? = null,
        val nextId: String?,
    ) : WorkflowNode

    /**
     * 二分支条件。[thenId] / [elseId] 为 null 表示该分支直接结束。
     *
     * 条件表达式用受限 DSL 而不是完整表达式引擎，理由见 [ConditionExpression]。
     */
    data class Condition(
        override val id: String,
        override val title: String,
        val expression: String,
        val thenId: String?,
        val elseId: String?,
    ) : WorkflowNode

    /** 纯计算：把 [template] 插值后写入 [outputKey]。 */
    data class Transform(
        override val id: String,
        override val title: String,
        val template: String,
        val outputKey: String,
        val nextId: String?,
    ) : WorkflowNode

    /** 终点。多个 Output 合法（不同分支各自结束）。 */
    data class Output(
        override val id: String,
        override val title: String = "结束",
        val template: String = "",
    ) : WorkflowNode
}

/** 一个完整工作流。 */
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val nodes: List<WorkflowNode>,
)

/** 单步执行记录。 */
data class StepRecord(
    val nodeId: String,
    val nodeTitle: String,
    /** 该步的产出（工具输出 / 条件判断结果 / 插值结果）。 */
    val detail: String,
    val isError: Boolean = false,
)

/** 一次运行的结果。 */
data class WorkflowRunResult(
    val success: Boolean,
    /** Output 节点产生的文本（按执行顺序）。 */
    val outputs: List<String>,
    /** 执行轨迹。失败时它比错误消息有用得多。 */
    val steps: List<StepRecord>,
    /** 失败原因；成功时为 null。 */
    val error: String? = null,
    /** 最终上下文（调试用）。 */
    val variables: Map<String, String> = emptyMap(),
)

/**
 * 工具执行端口。
 *
 * ## 为什么用端口而不是直接依赖 core:tool
 *
 * `core:workflow` 只关心「给我一个工具名与参数，回一段文本」。
 * 直接依赖 `ToolExecutor` 会把它和权限、注册表、Android 实现一起绑死，
 * 而引擎本身应该是纯函数——这样才能用假工具跑几十个分支用例。
 *
 * 实现在 `:app`，与 Room / 终端执行器同口径。
 */
interface WorkflowToolPort {

    /**
     * 调用工具。
     *
     * @return 工具输出文本。失败时**不要抛异常**，返回一段错误说明即可——
     *   工具层已约定「调用即返回结果」，端口不该引入第二套错误传递方式。
     */
    suspend fun call(toolName: String, argsJson: String): String
}
