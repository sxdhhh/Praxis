package dev.praxis.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.praxis.core.ai.AiError
import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatMessage
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.ai.ChatRole
import dev.praxis.core.tool.descriptorsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 聊天页状态机。
 *
 * ## 三个必须遵守的点
 *
 * 1. **流式增量直接改同一个气泡**：不给每个 delta 新建对象，否则 LazyColumn 会
 *    每帧重新布局整条消息，长回复时肉眼可见卡顿。
 * 2. **失败不丢上下文**：出错时保留已收到的部分文本，错误作为独立气泡追加，
 *    用户能看到「说到哪断的」。
 * 3. **发送中的协程要能取消**：切页面或重新发送时必须 cancel，否则旧流会继续
 *    往新状态里写，出现「回复串台」。
 */
class ChatViewModel(
    private val provider: AiProvider,
    private val model: String,
    private val toolRegistry: dev.praxis.core.tool.ToolRegistry? = null,
    private val toolExecutor: dev.praxis.core.tool.ToolExecutor? = null,
    private val toolContext: dev.praxis.core.tool.ToolContext? = null,
    /**
     * 每轮发送时**现取**权限快照，而不是构造时缓存。
     *
     * 缓存会导致「设置页改了档位，但聊天还在用旧档位」—— 用户会以为设置没生效。
     */
    private val permissionSnapshotProvider: (() -> dev.praxis.core.tool.ToolPermissionSnapshot)? = null,
    /**
     * 持久化仓储。null 表示不落盘（单测与纯 UI 预览用）。
     *
     * 用可空而不是强制注入：ViewModel 的单测不应该被迫起一个 Room 实例。
     */
    private val chatRepository: ChatPersistence? = null,
    private val sessionId: String = "default",
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var nextId = 0L
    private var streamingJob: Job? = null

    /** 对话历史（送给模型的原文，不含 UI 的 Error 气泡）。 */
    private val history = mutableListOf<ChatMessage>()

    /** UI 气泡 id → 数据库行 id（流式增量要按行更新）。 */
    private val assistantRowIds = mutableMapOf<Long, Long>()

    /** 落盘用协程作用域（与 viewModelScope 分开，可单独取消）。 */
    private val persistScope = kotlinx.coroutines.CoroutineScope(
        viewModelScope.coroutineContext + kotlinx.coroutines.Dispatchers.IO,
    )

    /** 快捷落盘：repo 为空时直接跳过，保证「不落盘也能用」。 */
    private fun launchPersist(block: suspend ChatPersistence.() -> Unit) {
        val repo = chatRepository ?: return
        persistScope.launch { repo.block() }
    }

    private var restored = false

    /**
     * 从数据库恢复历史（每个 ViewModel 只跑一次）。
     *
     * 为什么必须做：不做的话「杀进程 → 重开」聊天页是空的，
     * 用户会认为消息全丢了 —— 这是最伤信任的一类缺陷。
     *
     * 恢复时**重建 history**（送给模型的原文），否则模型不知道之前聊过什么，
     * 表现为「AI 失忆」。
     */
    fun restoreIfNeeded() {
        if (restored) return
        restored = true
        val repo = chatRepository ?: return

        persistScope.launch {
            val rows = runCatching { repo.loadMessages(sessionId) }.getOrNull() ?: return@launch
            if (rows.isEmpty()) return@launch

            val bubbles = rows.mapNotNull { row ->
                val bubbleId = nextId++
                // 记住行 id，后续流式增量才能定位到正确的那一行。
                assistantRowIds[bubbleId] = row.id
                val role = when (row.role) {
                    "user" -> BubbleRole.User
                    "assistant" -> BubbleRole.Assistant
                    "tool" -> BubbleRole.Tool
                    else -> BubbleRole.Error
                }
                // 同时重建送给模型的历史，跳过 error 气泡（那不是模型说过的话）。
                if (role != BubbleRole.Error) {
                    history += ChatMessage(
                        role = when (role) {
                            BubbleRole.User -> ChatRole.User
                            BubbleRole.Tool -> ChatRole.Tool
                            else -> ChatRole.Assistant
                        },
                        text = row.content,
                    )
                }
                ChatBubble(id = bubbleId, role = role, text = row.content)
            }

            _state.update { it.copy(bubbles = bubbles) }
        }
    }

    /**
     * 流式正文节流器：把「每个 delta 写一次库」压到约 1/10。
     *
     * 不节流的后果是真实的：3000 字回复会产生数百次 SQLite UPDATE，
     * 回复越长越卡。终态内容靠 [flushContent] 强制写入，不会丢尾。
     */
    private val contentWriter = ThrottledContentWriter { rowId, content ->
        launchPersist { updateMessageContent(rowId, content) }
    }

    /** 流式期间提交正文（可能被节流丢弃，留待 flush）。 */
    private fun persistContent(bubbleId: Long, content: String) {
        val rowId = assistantRowIds[bubbleId] ?: return
        contentWriter.submit(rowId, content)
    }

    /** 终态强制写入：流结束 / 失败 / 取消时调用，保证不丢最后一段。 */
    private fun flushContent(bubbleId: Long, content: String) {
        val rowId = assistantRowIds[bubbleId] ?: return
        // 先把最新内容放进暂存区，再 flush —— 提交可能因节流窗口被丢弃。
        contentWriter.submit(rowId, content)
        contentWriter.flush(rowId)
        contentWriter.forget(rowId)
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.isStreaming) return

        // 用户气泡 + 空的助手气泡（先建好，之后往里灌增量）
        val userBubble = ChatBubble(id = nextId++, role = BubbleRole.User, text = text)
        val assistantId = nextId++
        val assistantBubble = ChatBubble(
            id = assistantId,
            role = BubbleRole.Assistant,
            text = "",
            streaming = true,
        )

        history += ChatMessage(role = ChatRole.User, text = text)

        _state.update {
            it.copy(
                bubbles = it.bubbles + userBubble + assistantBubble,
                input = "",
                isStreaming = true,
                error = null,
            )
        }

        // 用户消息先落盘，再做助手占位落盘，顺序与 UI 一致。
        launchPersist {
            ensureSession(sessionId, text.take(24))
            appendMessage(sessionId, role = "user", content = text)
            val assistantRowId = appendMessage(
                sessionId,
                role = "assistant",
                content = "",
            )
            assistantRowIds[assistantId] = assistantRowId
        }

        startStream(assistantId)
    }

    /** 重试：把最后一条用户消息重发（不重复添加用户气泡）。 */
    /**
     * 重试上一轮失败的回答。
     *
     * 语义：**不重发用户消息**（它已经在 history 与库里），只重新生成助手回复。
     * 失败气泡留在原处不动 —— 它是事实记录，重试成功也不该抹掉。
     */
    fun retry() {
        if (_state.value.isStreaming) return
        val assistantId = nextId++
        _state.update {
            it.copy(
                bubbles = it.bubbles + ChatBubble(
                    id = assistantId,
                    role = BubbleRole.Assistant,
                    text = "",
                    streaming = true,
                ),
                isStreaming = true,
                error = null,
            )
        }
        // 重试也要落盘：否则重启后这一轮回复凭空消失，与 UI 不一致。
        launchPersist {
            val rowId = appendMessage(sessionId, role = "assistant", content = "")
            assistantRowIds[assistantId] = rowId
        }
        startStream(assistantId)
    }

    private fun startStream(assistantId: Long) {
        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            val buffer = StringBuilder()
            try {
                provider
                    .stream(
                        ChatRequest(
                            model = model,
                            messages = history.toList(),
                            tools = buildToolDescriptors(),
                        ),
                    )
                    .collect { event ->
                        when (event) {
                            is AiStreamEvent.Delta -> {
                                buffer.append(event.text)
                                // 只改文本，bubble 的 id/role 不变，LazyColumn 不会重建。
                                updateBubble(assistantId) { it.copy(text = buffer.toString()) }
                                persistContent(assistantId, buffer.toString())
                            }

                            is AiStreamEvent.ToolCalls -> {
                                updateBubble(assistantId) { it.copy(streaming = false) }
                                // 工具调用必须先写进 history 再执行 —— 模型的下一轮
                                // 需要看到「我发起了什么」，否则它会重复发起同一个调用。
                                history += ChatMessage(
                                    role = ChatRole.Assistant,
                                    text = buffer.toString(),
                                    toolCalls = event.calls,
                                )
                                runToolCalls(event.calls, assistantId)
                            }

                            is AiStreamEvent.Done -> {
                                updateBubble(assistantId) { it.copy(streaming = false) }
                                // 收尾强制写入：流可能没有触发最后一段 Delta，节流也可能暂存着。
                                flushContent(assistantId, buffer.toString())
                                history += ChatMessage(
                                    role = ChatRole.Assistant,
                                    text = buffer.toString(),
                                )
                            }
                        }
                    }
                _state.update { it.copy(isStreaming = false) }
            } catch (e: AiException) {
                onStreamFailure(assistantId, buffer.toString(), e.error)
            } catch (e: Exception) {
                onStreamFailure(
                    assistantId,
                    buffer.toString(),
                    AiError(
                        kind = AiErrorKind.UNKNOWN,
                        message = e.message ?: e::class.java.simpleName,
                        cause = e,
                    ),
                )
            }
        }
    }

    private fun onStreamFailure(assistantId: Long, partial: String, error: AiError) {
        val errorBubble = ChatBubble(
            id = nextId++,
            role = BubbleRole.Error,
            text = error.displayText(),
        )
        // 保留已收到的部分文本（UI）
        updateBubble(assistantId) { it.copy(text = partial, streaming = false) }
        // 同时落盘：失败时用户看到的那半截，重启后也应该还在。
        flushContent(assistantId, partial)
        _state.update {
            it.copy(
                bubbles = it.bubbles + errorBubble,
                isStreaming = false,
                error = error,
            )
        }
    }

    /**
     * 执行一批工具调用，并把结果写回 history 与 UI，然后**自动续一轮**。
     *
     * 为什么必须自动续：模型给 tool_calls 时这一轮 assistant 消息是「空的」，
     * 真正对用户有用的回答在工具执行完之后。不续轮用户就只能看到一张工具卡片。
     */
    private suspend fun runToolCalls(
        calls: List<dev.praxis.core.ai.ToolCall>,
        assistantId: Long,
    ) {
        val executor = toolExecutor
        val context = toolContext
        if (executor == null || context == null) {
            // 未装配工具层：如实告知，不假装执行过。
            updateBubble(assistantId) {
                it.copy(text = it.text + "\n\n（工具执行层未装配，无法执行：${calls.joinToString(", ") { c -> c.name }}）")
            }
            _state.update { it.copy(isStreaming = false) }
            return
        }

        val invocations = calls.map {
            dev.praxis.core.tool.ToolInvocation(
                callId = it.id,
                name = it.name,
                argumentsJson = it.argumentsJson,
            )
        }

        val executions = executor.executeAll(
            invocations = invocations,
            context = context,
            askHandler = null, // 弹框征询在本轮不做，ASK 会降级为拒绝。
        )

        // 结果按调用顺序逐条落库 + 渲染
        executions.forEach { exec ->
            history += ChatMessage(
                role = ChatRole.Tool,
                toolResult = dev.praxis.core.ai.ToolResult(
                    callId = exec.invocation.callId,
                    content = exec.result.content,
                    isError = exec.result.isError,
                ),
            )
            _state.update { current ->
                current.copy(
                    bubbles = current.bubbles + ChatBubble(
                        id = nextId++,
                        role = BubbleRole.Tool,
                        text = buildToolSummary(exec),
                    ),
                )
            }
            // 工具执行结果同样要落盘，否则重启后模型看不到自己做过什么。
            launchPersist {
                appendMessage(
                    sessionId = sessionId,
                    role = "tool",
                    content = buildToolSummary(exec),
                    toolName = exec.invocation.name,
                    toolArgs = exec.invocation.argumentsJson,
                    toolCallId = exec.invocation.callId,
                    isError = exec.result.isError,
                )
            }
        }

        // 续一轮：让模型基于工具结果给出最终回答。
        val followUpId = nextId++
        _state.update {
            it.copy(
                bubbles = it.bubbles + ChatBubble(
                    id = followUpId,
                    role = BubbleRole.Assistant,
                    text = "",
                    streaming = true,
                ),
                isStreaming = true,
            )
        }
        startStream(followUpId)
    }

    /** 只把当前档位能执行的工具发给模型（发不能用的工具会让它反复白试）。 */
    private fun buildToolDescriptors(): List<dev.praxis.core.ai.ToolDescriptor> {
        val registry = toolRegistry ?: return emptyList()

        // 有快照就用快照（含用户禁用列表）；没有则退回只看档位。
        val snapshot = permissionSnapshotProvider?.invoke()
        val specs = if (snapshot != null) {
            registry.descriptorsFor(snapshot)
        } else {
            val context = toolContext ?: return emptyList()
            registry.descriptorsFor(context.tier)
        }

        return specs.map { spec ->
            dev.praxis.core.ai.ToolDescriptor(
                name = spec.name,
                description = spec.description,
                parametersJson = spec.parametersJson,
            )
        }
    }

    private fun buildToolSummary(exec: dev.praxis.core.tool.ToolExecution): String {
        val status = if (exec.result.isError) "失败" else "完成"
        val body = exec.result.content.take(600)
        return "[工具 ${exec.invocation.name} $status]\n$body"
    }

    private fun updateBubble(id: Long, transform: (ChatBubble) -> ChatBubble) {
        _state.update { current ->
            current.copy(
                bubbles = current.bubbles.map { if (it.id == id) transform(it) else it },
            )
        }
    }
}

/** 错误文案（按 kind 给固定句子，不把上游原始报错直接甩给用户）。 */
private fun AiError.displayText(): String = when (kind) {
    AiErrorKind.NETWORK -> "网络连接失败，请检查网络后重试。"
    AiErrorKind.RATE_LIMIT -> "请求过于频繁，请稍后再试。"
    AiErrorKind.SERVER -> "服务端暂时不可用，请稍后重试。"
    AiErrorKind.STREAM_INTERRUPTED -> "回复中断了，可以重试继续。"
    AiErrorKind.AUTH -> "密钥无效或权限不足，请到设置里检查。"
    AiErrorKind.INVALID_REQUEST -> "请求参数有误，请检查模型配置。"
    AiErrorKind.CANCELLED -> "已取消。"
    AiErrorKind.UNKNOWN -> "出现未知错误：$message"
}
