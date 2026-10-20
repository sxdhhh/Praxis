package dev.praxis.core.workflow

/**
 * 工作流执行引擎。
 *
 * ## 四个硬约束（都是为了让「自动化不会失控」）
 *
 * 1. **必须顶住步数上限**：图可以有回边（用户手拼的可能形成环）。
 *    没有上限时一个环会让协程永久运，用户看到的是 App 卡死而不是「工作流有错」。
 * 2. **必须顶住执行时长**：工具可能卡住（网络、大文件）。步数上限拦不住「单步卡 10 分钟」。
 * 3. **工具失败立即中止**：后续节点依赖前一步的输出，继续跑只会拿空值
 *    去做危险的事（例如上一步 read_file 失败、下一步拿空路径去删文件）。
 *    注意「失败」有两种表现：端口抛异常，以及端口返回一段错误文本。
 *    前者靠 runCatching 兜，后者靠 [ERROR_MARKERS] 识别。
 * 4. **不抛异常，只返回结果**：调用方是 UI，它需要轨迹与错误消息，
 *    而不是一个栈。
 *
 * ## 为什么引擎是纯函数（除了工具端口）
 *
 * 除了 [WorkflowToolPort.call]，引擎不碰任何 IO。
 * 这样分支逻辑可以用假工具在毫秒内跑完几十个用例，
 * 而这正是最容易写错的部分。
 */
class WorkflowEngine(
    private val toolPort: WorkflowToolPort,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * 执行一个工作流。
     *
     * @param variables 初始上下文（例如触发时带进来的参数）
     */
    suspend fun run(
        definition: WorkflowDefinition,
        variables: Map<String, String> = emptyMap(),
    ): WorkflowRunResult {
        val byId = definition.nodes.associateBy { it.id }
        val trigger = definition.nodes.filterIsInstance<WorkflowNode.Trigger>().firstOrNull()
            ?: return fail("工作流缺少入口节点（Trigger）", emptyList(), variables)

        val steps = ArrayList<StepRecord>(8)
        val outputs = ArrayList<String>(2)
        val vars = LinkedHashMap(variables)

        // 入口指向不存在的节点也要报错，而不是静默跑完 0 个节点。
        if (trigger.nextId != null && byId[trigger.nextId] == null) {
            return fail("入口指向不存在的节点：${trigger.nextId}", emptyList(), variables)
        }

        val startedAt = nowMs()
        var current: WorkflowNode? = byId[trigger.nextId]
        var visited = 0

        // 入口本身也记一笔，否则轨迹从第一个业务节点开始，用户不知道从哪触发。
        steps += StepRecord(
            nodeId = trigger.id,
            nodeTitle = trigger.title,
            detail = if (trigger.nextId == null) "无后续节点" else "→ ${trigger.nextId}",
        )

        while (current != null) {
            if (++visited > maxSteps) {
                return fail("超过步数上限 $maxSteps，可能是节点成环", steps, vars)
            }
            if (nowMs() - startedAt > maxDurationMs) {
                return fail("超过执行时长上限 ${maxDurationMs}ms", steps, vars)
            }

            when (val node = current) {
                is WorkflowNode.Trigger -> {
                    // 第二个 Trigger：定义有错，但不崩，直接停下并报清。
                    return fail("发现多个入口节点，工作流只能有一个", steps, vars)
                }

                is WorkflowNode.Tool -> {
                    val args = renderTemplate(node.argsTemplate, vars)

                    // 两条失败路径必须分开对待：
                    //  - 抛异常 → 端口违约（按约定它不该抛），归为失败；
                    //  - 返回错误文本 → 正常契约下的失败，也必须中止。
                    val thrown = runCatching { toolPort.call(node.toolName, args) }

                    thrown.exceptionOrNull()?.let { e ->
                        steps += StepRecord(
                            nodeId = node.id,
                            nodeTitle = node.title,
                            detail = "工具异常：${e.message}",
                            isError = true,
                        )
                        return fail("节点「${node.title}」执行失败：${e.message}", steps, vars)
                    }

                    val out = thrown.getOrDefault("")

                    if (out.looksLikeToolFailure()) {
                        steps += StepRecord(node.id, node.title, out, isError = true)
                        return fail("节点「${node.title}」失败：${out.take(120)}", steps, vars)
                    }

                    // 只在成功时写上下文：失败时写入的错误文本会让下游
                    // 模板插值把错误消息当作正常数据用下去。
                    node.outputKey?.let { vars[it] = out }
                    steps += StepRecord(node.id, node.title, out)
                    if (node.nextId != null && byId[node.nextId] == null) {
                        return fail("节点「${node.title}」指向不存在的后继：${node.nextId}", steps, vars)
                    }
                    current = resolveNext(byId, node.nextId)
                }

                is WorkflowNode.Condition -> {
                    val matched = ConditionExpression.evaluate(node.expression, vars)
                    steps += StepRecord(
                        nodeId = node.id,
                        nodeTitle = node.title,
                        detail = "${node.expression} → $matched",
                    )
                    // 条件走到的分支如果指向不存在的节点，必须报错。
                    // 尤其 elseId 为 null 是合法的「到此结束」，但 elseId 写错
                    // （指向一个不存在 id）不能与它混为一谈。
                    val branchId = if (matched) node.thenId else node.elseId
                    current = resolveNext(byId, branchId)
                    if (branchId != null && current == null) {
                        return fail("条件「${node.title}」指向不存在的分支：$branchId", steps, vars)
                    }
                }

                is WorkflowNode.Transform -> {
                    val value = renderTemplate(node.template, vars)
                    vars[node.outputKey] = value
                    steps += StepRecord(node.id, node.title, value)
                    if (node.nextId != null && byId[node.nextId] == null) {
                        return fail("节点「${node.title}」指向不存在的后继：${node.nextId}", steps, vars)
                    }
                    current = resolveNext(byId, node.nextId)
                }

                is WorkflowNode.Output -> {
                    val text = renderTemplate(node.template, vars)
                    outputs += text
                    steps += StepRecord(node.id, node.title, text)
                    // 终点：不继续走。多分支各自的 Output 互不影响。
                    current = null
                }
            }
        }

        return WorkflowRunResult(
            success = true,
            outputs = outputs,
            steps = steps,
            variables = vars,
        )
    }

    /**
     * 工具输出是否表示失败。
     *
     * ## 为什么要靠标记串识别
     *
     * [WorkflowToolPort.call] 只返回字符串，不带成功标志。
     * 在端口上加 `isError` 当然更干净，但那会与 `core:tool` 的
     * `ToolResult.isError` 重复造一套形状，且需要所有实现同步改。
     *
     * 这里选择「宁可误报」：把失败当成功继续跑，可能会拿错误文本
     * 当路径去删文件；把成功当失败，用户只会看到工作流提前停了（可恢复）。
     * 风险不对称，所以标记表义宁宽。
     */
    /**
     * 解析下一个节点。
     *
     * 返回 null 有两种含义，调用方**必须区分**：
     *  - `nextId == null` → 流程在此正常结束（合法）；
     *  - `nextId != null` 但查不到 → 定义有错（必须报 fail）。
     *
     * 曾经这里直接 `current = byId[nextId]`，于是「指向不存在节点」与
     * 「正常结束」无法区分：循环因 current 为 null 而退出，最终返回
     * success = true。用户看到「运行成功」，实际流程压根没跑完。
     * 这是最糟的错误类型——静默的成功。
     */
    private fun resolveNext(
        byId: Map<String, WorkflowNode>,
        nextId: String?,
    ): WorkflowNode? = if (nextId == null) null else byId[nextId]

    private fun String.looksLikeToolFailure(): Boolean =
        ERROR_MARKERS.any { trimStart().startsWith(it) }

    private fun fail(
        message: String,
        steps: List<StepRecord>,
        vars: Map<String, String>,
    ): WorkflowRunResult = WorkflowRunResult(
        success = false,
        outputs = emptyList(),
        steps = steps,
        error = message,
        variables = vars,
    )

    companion object {
        /**
         * 步数上限。
         *
         * 50 步：正常的手写自动化不会超过二十步。
         * 取大一点是为了不误伤复杂流程，取小一点是为了环能快点暴露。
         */
        const val DEFAULT_MAX_STEPS = 50

        /** 时长上限：10 分钟。工具本身另有超时，这里是整轮兜底。 */
        const val DEFAULT_MAX_DURATION_MS = 10 * 60 * 1000L

        /**
         * 失败标记，按**前缀**匹配。
         *
         * ## 为什么必须前缀而非子串
         *
         * 子串匹配会误杀正常输出：`read_file` 读到一个内容为
         * "file not found handling" 的源文件时，整份文件内容里含 `not found`，
         * 会被当成工具失败而中止工作流 —— 这是完全正常的读取。
         *
         * 而 [WorkflowToolPort] 的实现在错误时总是返回**以固定词开头**的文本
         * （见 ToolWorkflowPort），所以前缀匹配足够且精确。
         */
        private val ERROR_MARKERS = listOf(
            "工具不存在",
            "参数错误",
            "工具调用失败",
            "工具调用异常",
            "拒绝执行",
            "ToolException",
        )
    }
}
