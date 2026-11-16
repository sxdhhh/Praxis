# 03 · core:ai / core:model / core:data / core:plugin 通读审计

> 执行者：**agent-4**（Praxis「通读 + 审计」子智能体），只读角色。
> 生成时间：2026-11-16（Asia/Shanghai）。
> 范围：四个 core 模块的 `src/**`，**133 个文件全部逐文件读完**（含 47 个测试文件）。
> 交叉引用（**不在本次范围**，只为了定位「谁消费/谁生产」而读的外部文件）：
> `app/src/main/java/dev/praxis/data/ai/SettingsBackedProvider.kt`、`app/src/main/java/dev/praxis/data/db/PraxisDatabase.kt`、
> `app/src/main/java/dev/praxis/di/AppModule.kt`、`app/src/main/java/dev/praxis/ui/PraxisApp.kt`（节选）、
> `app/src/main/java/dev/praxis/data/ai/OpenAiCompatProvider.kt`（grep 定位）、`feature/chat/.../ChatViewModel.kt`（grep 定位）、
> `app/src/main/java/dev/praxis/data/chat/ChatImagePreparer.kt`（节选）、`app/src/main/java/dev/praxis/ProvisionGate.kt`（节选）。
> 这些外部文件**没有**被逐行通读，因此凡引用它们的地方只作「有/无消费者」的定性结论。

---

## 0. 结论速览

1. **纯逻辑层质量高，风险在接线与测试债**。core:ai 24 个实现文件对应 23 个单测文件，几乎每条「反直觉的决策」都有注释说明历史故障；失败行为大多是显式抛错（`AiException`）而不是静默降级。真正的洞在**消费方缺失**（§5、§8）与**历史测试夹具过时**（§7）。
2. **请求构造链完整且位置正确**（§4.1）：脱敏在最前（两条路共用）→ 网关门禁 → 功能位解析（CHAT 覆盖生效）→ 节流（并发先于限流）→ `ChatEndpoint` → SSE → 三个 assembler。唯一实质缺口是脱敏只改写 `text`，**不改写工具结果/参数/图片**（G3）。
3. **上下文链完整但配置面缺失**（§4.2）：`CompactionApplier`（按摘要位置裁剪）→ `ContextAssembler`（压缩 + 丢弃）→ `AiContextBudget.fit`；压缩用双阈值（token 或条数）。但 `CompactionSettings` 在 ChatViewModel 里是**硬编码实例**，`messageCountThreshold / globalRules / sectionOverrides` 无任何设置键与 UI（G4）。
4. **设置键账本**：60 个静态键 + 3 个动态键族全部列出（§5）。其中 5 处**没有消费者**：`use_miuix_engine`（写方 API 零调用点、无读 Flow）、`linux_env_mirror`、`linux_env_release`（读写两侧都零调用点）、`permission_level` 的**读侧**（只有写）、`memory_spaces` 的**删除侧**（`removeMemorySpace` 零调用点）。这与 GOAL「设置项必须有消费方」直接冲突（G1/G2）。
5. **core:plugin 9 个历史失败用例全部定位**（§7）：6 条 `PluginRepositoryTest`（4 条测试夹具缺 `main` 文件触发「致命→强制停用」新规则 + 2 条测试自己没把安装源喂给假存储）、2 条 `PluginLoadOrderTest`（1 条**产品缺陷**：链式依赖失效不传播；1 条断言过时）、1 条 `ExtensionSnapshotTest` **未确认**（静态推演 9 条断言全满足，需 CI 单条日志）。其中 1 条命中的是真实产品 bug（G5）。

---

## 1. 范围与文件清单（数量核对）

| 模块 | 目录 | 我读了 | 目录里实际 | 核对 |
|---|---|---|---|---|
| core:ai | `core/ai/src/**` | 47 | 47（main 24 + test 23） | ✅ 一致 |
| core:model | `core/model/src/**` | 23 | 23（main 15 + test 8） | ✅ 一致 |
| core:data | `core/data/src/**` | 27 | 27（26 `.kt` + `src/main/AndroidManifest.xml`） | ✅ 一致 |
| core:plugin | `core/plugin/src/**` | 36 | 36（main 24 + test 12） | ✅ 一致 |
| **合计** | | **133** | **133** | ✅ |

统计口径：`walkDir` 递归 + 过滤 `.kt`（core:data 额外含 1 个 manifest），排除 `build/ .gradle/ .git/`。
仓库内 `.kt` 总数 653（全库 1251 个文件，其余为资源/二进制/文档）。

**路径缩写约定**（下文表格用短路径，展开规则如下）：

| 缩写 | 展开 |
|---|---|
| `ai/<file>` | `core/ai/src/main/kotlin/dev/praxis/core/ai/<file>` |
| `ai-test/<file>` | `core/ai/src/test/kotlin/dev/praxis/core/ai/<file>` |
| `model/<file>` | `core/model/src/main/kotlin/dev/praxis/core/model/<file>` |
| `model-test/<file>` | `core/model/src/test/kotlin/dev/praxis/core/model/<file>` |
| `data/<file>` | `core/data/src/main/kotlin/dev/praxis/core/data/<file>`（`permission/` 下为 `src/main/java`） |
| `data-test/<file>` | `core/data/src/test/kotlin/dev/praxis/core/data/<file>` |
| `plugin/<file>` | `core/plugin/src/main/kotlin/dev/praxis/core/plugin/<file>` |
| `plugin-test/<file>` | `core/plugin/src/test/kotlin/dev/praxis/core/plugin/<file>` |

---

## 2. 模块职责（一段话各）

**core:ai（纯 JVM，无 Android/HTTP 依赖）**：定义「一次 AI 请求长什么样、回来的流长什么样」的统一契约（`AiContract`：`ChatMessage/ChatRequest/AiStreamEvent/AiProvider`）与统一错误模型（`AiError`，12 种 kind 带 retryable 判定）。在此之上提供三组**纯函数/纯策略**：① 出站编码（`AnthropicRequestBuilder` / `ResponsesRequestBuilder` / `ChatEndpoint`）与出站改写（`OutboundSanitizer`）；② 入站解析（`SseParser` + 三个 stream assembler + `OpenAiUsage`）；③ 上下文治理（`TokenEstimator/TokenCache`、`CompactionPolicy`、`AiContextBudget`、`ContextAssembler`、`ContextCompactor`（含 Operit 原文摘要提示词）、`SummaryCondenser`、`SystemPromptBuilder`+`SystemPromptTemplates`、`MemoryPressurePolicy`）；外加两个基建件（`RequestThrottle` 滑动窗口+并发闸门、`ModelCatalog` 模型清单 URL/解析）。它不持有任何状态机、不碰网络，所有 I/O 留给 `:app`。

**core:model（纯 Kotlin 数据层）**：全项目的**领域常量与不可变模型**。包括：模型相关（`ModelSettings`（逗号模型池 + `modelIndex` + `activeModel`）、`ModelProfile/ModelProfileKind`（多配置 + 网关形态）、`ModelAdvancedSettings`（自定义头 + 队列，自研 `\u001F` 序列化）、`FunctionModelSettings`（6+1 个功能位覆盖与 `resolve()` 回落）、`ApiProvider` 14 家预设、`ThinkingEffort`）；外观（`ThemeSettings` 五组枚举）；终端（`TerminalSettings` 带 `sanitized()`）；角色卡（`CharacterCard` / `TavernCard`（酒馆 V1/V2 + `extensions.operit` 往返保真）/ `CharacterPromptComposer` / `CharacterToolFilter`）；Linux（`LinuxPackageGroups` 六组 + 安装脚本生成、`VirtualKeyboardLayoutCodec`）；权限（`PraxisCapability` 8 项 + `CapabilitySnapshot` + `PraxisPermissionLevel` 五级推导）；以及 `MessageTimestampAllocator`（严格单调时间戳）。

**core:data（唯一允许 `android.*` 的 core 模块）**：**持久化与系统能力的实现层**。四块：① `SettingsRepository`（DataStore Preferences，1743 行，60 个静态键 + `p:<profileId>:*` 前缀族 + `pending_queue_<sid>`；含多配置 CRUD、密钥 AES/GCM 加密存取、功能位覆盖表 JSON 编解码）；② Room 实体与 DAO（角色卡/提示词标签 2 张表，网关 5 张表——账号、自定义家、结构化模型、请求记录、日聚合）与 `CharacterRepository`（跨表事务、酒馆导入导出、标签引用清理）；③ Keystore 加密（`KeystoreCipher`，失败降级不崩）；④ 系统能力与日志：`CapabilityDetector`（8 项真实检测，每条检测路径都不同）/ `CapabilityRequester`（三条完全不同的授予路径）、`PraxisLogger`（有界队列 + 单写线程 + 分片 + flush 屏障）/ `LogLine`+`LogParser`（写读同源格式）/ `PraxisTimberTree`（Timber 落盘桥）。

**core:plugin（纯 JVM）**：**扩展生态的领域层**——把「工具包 / 技能 / MCP / 沙盒包」四类扩展统一成一个视图（`ExtensionEntry`/`ExtensionSnapshot`），并提供：清单解析（自研 HJSON/JSON 解析器 `ManifestParser` + 严格映射 `ManifestMapper`）、包归档（`PluginArchive` 含 zip-slip 路径归一化、`PluginArchiveReader` 找清单/读脚本/建文本表）、安装与状态（`PluginRepository` + `PluginStorage` 端口）、依赖拓扑（`PluginLoadOrder`：不动点剔除 + Kahn + 环检测）、运行时装载（`PluginActivation` 编排 + `PluginRuntimeHost` 记账 + `PluginScriptRuntime` 端口 + `PluginRuntimeBootstrapScript` 注入的 CommonJS 引导脚本）、子包 METADATA 工具声明（`ScriptMetadata` + 工具名清洗）、compose_dsl 渲染树解析（`ComposeDslTree`）、在线市场（`MarketApi/MarketEndpoints/MarketJsonParser/MarketOriginCodec` XOR 混淆来源标记）、技能（`Skill/SkillParser/SkillRegistry`，frontmatter 两个调用开关）、插件入口（`PluginEntryPoint`：清单声明与运行时捕获两条来源，整体优先级）。

---

## 3. 逐文件一表

> 133 个文件**逐个列出**（含全部 47 个测试文件）。表格列固定为「路径 | 职责 | 对外关键 API/类型 | 疑点」。

### 3.1 core:ai（47）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| `ai/AiContract.kt` | 统一契约：消息/请求/工具/流事件/provider 接口 | `ChatRole`、`ChatMessage(text, toolCalls, toolResult, images)`、`ChatImage`、`ChatRequest`、`AiStreamEvent`（Delta/ToolCallDelta/ToolCalls/ReasoningDelta/Done/Usage）、`AiProvider.stream()`、`AiException`、`typealias ThinkingEffort` | `Delta` 的 KDoc 被贴在 `ToolCallDelta` 之上（注释错位，不影响编译）；`ChatRequest.maxTokens=null` 与各 builder 的 4096 默认值需调用方记住 |
| `ai/AiError.kt` | 错误归类表（HTTP→kind）+ retryable | `AiErrorKind`（12 值）、`AiError.fromHttpCode` | `QUOTA_LIMITED/TOKEN_EXPIRED/CONTENT_BLOCKED` 三种 kind 自己声明「判定点尚未实现」（除 CONTENT_BLOCKED 已由 provider 接） |
| `ai/AiContextBudget.kt` | 只做「整条丢弃」的字符预算裁剪 | `fit(messages,maxChars)`、`overBudget`、`costOf`、`DEFAULT_MAX_CHARS=192_000` | 预算按**字符**而非 token（自述取舍）；`fit` 全量 `sumOf` 每次调用重算，长历史下有 O(n) 重复（未被 TokenCache 覆盖） |
| `ai/AnthropicRequestBuilder.kt` | Anthropic Messages 出站编码（纯函数） | `build(request, thinking, toolTypeCustom)`、`API_VERSION` | `thinking.budget_tokens = max_tokens/2` 可能与 max_tokens 冲突（上游要求 budget < max_tokens，此处恰好相等的一半，安全）；坏 schema 静默替换为空 schema（有注释，属有意） |
| `ai/AnthropicStreamAssembler.kt` | Anthropic SSE→事件，用量攒到 finish | `feed/finish/stopReason/errorMessage` | `errorMessage` 只记录不上报，是否成错由 provider 判（provider 侧未见使用 → 上游 error 事件可能被当正常结束）；`callIndex` 用 Anthropic 的 `index` |
| `ai/ChatEndpoint.kt` | baseUrl→真正端点（四种输入都对） | `resolve`、`endsWithVersionSegment`、`CHAT_COMPLETIONS_PATH` | 拼错的子路径不静默修好（有意，会 404） |
| `ai/ChatImageCompression.kt` | 图片压缩策略（开关/长边/质量/阈值）+ 决策纯函数 | `ChatImageCompression`、`ChatImageCompressionDecision`、`ChatImageCompressionRules.decide` | **`decide()` 全仓零调用点**；`ChatImagePreparer` 只读 `enabled/maxEdge/quality`，**`thresholdKb`（小图不碰）存了但不生效**（G6） |
| `ai/CompactionPolicy.kt` | 逐条压缩策略（现为恒等） | `compactText/compactToolResult/compactArguments`、`TEXT_HEAD/TOOL_HEAD…` | 三个函数全部原样返回；`compact(head,tail)` 私有实现保留但 `@Suppress("unused")`；常量 `TEXT_HEAD…` 已成死值 |
| `ai/ContextAssembler.kt` | 压缩+丢弃的唯一接线点 | `assemble(messages,maxChars,compact=false)` | 默认 `compact=false`；因 `CompactionPolicy` 恒等，此参数当前无行为差异（两处 KDoc 已说明） |
| `ai/ContextCompactor.kt`（39 KB，最大） | 摘要提示词（中/英原文）+ 小节覆盖 + 双阈值判定 + `CompactedContext` + `CompactionApplier` + `CompactionSettings` | `buildRequest`、`shouldCompact`、`compactCount`、`detectUseEnglish`、`renderMessages`、`CompactedContext.savedTokens`、`CompactionApplier.apply/isSummaryMessage/summaryMessage`、`SummarySectionOverride` | `SummarySectionOverride`/小节覆盖无任何设置键与 UI（只有内部默认）；`CompactionSettings` 在 ChatViewModel 是硬编码实例（G4）；摘要消息用 `text.startsWith("# 早前对话摘要")` 识别（跨进程约定，脆弱但有两处常量共用） |
| `ai/MemoryPressure.kt` | 内存快照 + 四档判定（含滞回/死区） | `MemorySnapshot`、`MemoryPressure`、`MemoryPressurePolicy.evaluate/rawLevel/shouldTrimCaches/shouldCompact`、`CompactionTrigger` | `trimLevelToPressure` 对 20/40/60/80 一律 Elevated（有意）；`holdsAtLeast` 的系统项只对 Elevated 生效（有意） |
| `ai/ModelCatalog.kt` | 模型清单 URL 派生 + 三格式解析 + 端口 | `AiModelOption`、`modelsUrl`、`parse`、`Failure`（5 种带中文 description）、`ModelCatalogFetcher`、`ProviderKind` | `parse` 用结构判定（data/models/result）；解析出 0 条当失败（有意） |
| `ai/OpenAiStreamAssembler.kt` | OpenAI Chat 流组装 + 拦截判定 | `feed/finish/accumulatedText/finishReasonOrNull/blockedWithoutOutput`、`ContentFilterReason` | 兜底 id `call_{index}_{name}` 防撞（已修）；`finish` 不发 Usage（Chat 兼容流不解析 usage → 走 `OpenAiUsage` 的是非流式） |
| `ai/OpenAiUsage.kt` | usage 三形态解析 | `parse(JsonObject)/parseJson(String)` | 三个字段全 0 视为没给（有意）；只被 provider 用于非流式 |
| `ai/OutboundSanitizer.kt` | 出站正则脱敏 + codec | `SanitizeRule`、`compile`（坏正则单独挑出）、`apply`、`SanitizeRuleCodec` | 只改 text（G3）；`SanitizeRuleCodec` 无非测试调用点（被 `SanitizeSettingsCodec` 取代，属死代码） |
| `ai/RequestThrottle.kt` | 滑动窗口 RPM + 并发信号量 + 排队上限 | `SlidingWindowRateLimiter`、`RequestThrottle.acquire/tryAcquire/isNoOp`、`Permit(AutoCloseable)` | 并发先于限流（正确）；`tryAcquire` 在 `maxWaitMs<=0` 时退化为无限等（文档已述） |
| `ai/ResponsesRequestBuilder.kt` | Responses 出站编码 | `build` | 工具条目 `function_call` 独立成条（正确）；未发 `instructions` 之外的 `reasoning` 参数 |
| `ai/ResponsesStreamAssembler.kt` | Responses SSE→事件 | `feed/finish/stopReason/errorMessage` | `callIndex = tools.keys.indexOf(itemId)` 是 O(n) 且依赖插入序（可接受）；同样未把 `errorMessage` 上报成错 |
| `ai/SanitizeSettings.kt` | 脱敏 = 开关 + 规则（两种 codec + 文本互转） | `SanitizeSettings.effectiveRules`、`SanitizeSettingsCodec`、`SanitizeRuleLines` | 与 `SanitizeRuleCodec` 重复定义规则编解码（两套并存） |
| `ai/SseParser.kt` | 字节级 SSE 增量解析 + 1MB 超长保护 | `feed/finish/didDropOversize`、`MAX_PENDING_CHARS` | 超长时先抢救完整事件再清缓冲（正确）；`indexOfEventBoundary` 每次从头扫（O(n²) 但 n≤1MB） |
| `ai/SummaryCondenser.kt` | 摘要输入瘦身（1:1 移植 Operit condense*）+ 窄正则集 | `condenseUser/condenseAssistant/splitSegments/headTail/pruneUserLargeBlocks/stripThinking`、`MarkupPatterns`(internal) | 与 `feature:chat` 的 `ChatMarkupRegex` 刻意双份（有漂移风险，已在注释登记）；`Segment` 为 public 供测试 |
| `ai/SystemPromptBuilder.kt` | 五层优先级 → **一条** system + 模板填充 + 无偏离约束 | `SystemPromptSettings(custom, persona, template)`、`buildLayers`、`flatten`、`ADHERENCE_INSTRUCTION`、`MEMORY_WORKFLOW_INSTRUCTION`、`BUILTIN_PERSONA`、`PromptPriority` | `PromptPriority` 枚举**无人读取**（文件自身 KDoc 已注明，顺序靠单测维系）；`buildLayers` 恒返单条，`flatten` 现为恒等 |
| `ai/SystemPromptTemplates.kt` | 占位符骨架 + fill（清空未用占位符） | `DEFAULT`、`ALL`、`fill(template, sections)` | 用户模板若漏写 `USER_RULES` 占位符，用户规则会被静默丢弃（`fill` 只替换模板里出现的占位符；单测只覆盖「只留人设」的合法用法）→ G7 |
| `ai/TokenEstimator.kt` | token 估算（汉字×1.5）+ 增量缓存 | `TokenEstimator.estimate/format`、`TokenCache.count/reset` | 系数照抄上游；`TokenCache` 不把 cached 当服务端命中（有意） |
| `ai-test/AiContextBudgetTest.kt` | 预算 cost/fit/pin 规则 + assembler 不截断（16 例） | — | 通过；其中 3 条断言「压缩恒等」是现行契约 |
| `ai-test/AiErrorTest.kt` | HTTP→kind 映射与 retryable 表（3 例） | — | 通过 |
| `ai-test/AnthropicRequestBuilderTest.kt` | system 顶层/content 数组/tool_result 搬进 user/tool_use/按名去重/图片/image 块（14 例） | — | 通过 |
| `ai-test/AnthropicStreamAssemblerTest.kt` | 正文/思考/用量攒到 finish/工具分片累积/stop_reason/error（13 例） | — | 通过 |
| `ai-test/ChatEndpointTest.kt` | 四种 baseUrl 形态 + 智谱 v4/百炼 compatible-mode/本地端口（17 例） | — | 通过 |
| `ai-test/ChatImageCompressionTest.kt` | 开关/小图不碰/大图长边/尺寸未知/归一化（9 例） | — | **测的是无调用点的 decide()**（绿但无产品意义，见 G6） |
| `ai-test/CompactionBenefitTest.kt` | 净收益可为负/缺 coveredTokens/只改发送范围不删原文（4 例） | — | 通过 |
| `ai-test/CompactionPolicyTest.kt` | 长文本/工具返回/参数/代理对一律原样（4 例） | — | 通过（现行契约就是恒等） |
| `ai-test/ContextCompactorTest.kt` | 四节提示词/中英选择/旧摘要进 system/小节开关/双阈值/TokenEstimator/TokenCache（33 例） | — | 通过；TokenCache 截断用例的断言已按实测改过（注释登记） |
| `ai-test/MemoryPressurePolicyTest.kt` | 四档阈值边界/信号不互相遮蔽/滞回死区/动作门槛（24 例） | — | 通过 |
| `ai-test/ModelCatalogTest.kt` | URL 派生/三格式解析/失败文案（16 例） | — | 通过 |
| `ai-test/OpenAiStreamAssemblerTest.kt` | 分片累积/三种思考字段/拦截判定/进度事件（18 例） | — | 通过 |
| `ai-test/OpenAiToolCallProtocolTest.kt` | index 累积/无 id 兜底/空 arguments 原样/非法 JSON（7 例） | — | 通过 |
| `ai-test/OpenAiUsageTest.kt` | 三形态 usage/空壳当没给/非对象（7 例） | — | 通过 |
| `ai-test/OutboundSanitizerTest.kt` | 命中替换/坏正则隔离/大小写敏感/顺序/codec（9 例） | — | 通过 |
| `ai-test/RequestThrottleQueueTest.kt` | 排队上限 0/超时返回 null/释放即得（5 例） | — | 用 runBlocking + 真实 delay（慢但稳） |
| `ai-test/RequestThrottleTest.kt` | 滑动窗口/并发闸门/Permit 幂等（8 例） | — | 通过 |
| `ai-test/ResponsesRequestBuilderTest.kt` | instructions/input 条目/function_call(_output)/去重/图片（12 例） | — | 通过 |
| `ai-test/ResponsesStreamAssemblerTest.kt` | 按 item_id 累积/用量一次给全/失败事件（11 例） | — | 通过 |
| `ai-test/SanitizeSettingsTest.kt` | 开关与规则分离/编解码/缺键默认/文本互转（8 例） | — | 通过 |
| `ai-test/SseParserTest.kt` | 跨 chunk/DONE/CRLF/多行 data/超长保护（12 例） | — | 通过 |
| `ai-test/SummaryCondenserTest.kt` | headTail 边界/附件占位/思考剥离/分块/标签归空格（18 例） | — | 通过；分块断言的期望值已按「5 段」修正（注释登记） |
| `ai-test/SystemPromptBuilderTest.kt` | 单条 system/层顺序/约束只追加一次/模板替换（11 例） | — | 通过；**未覆盖「模板漏 USER_RULES 占位符」**（G7） |

### 3.2 core:model（23）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| `model/MessageTimestampAllocator.kt` | 严格单调时间戳（CAS 循环）+ observe 前推 | `next()/observe()/watermark()` | 由 Koin 提供 single；测试需各自 new（已说明） |
| `model/character/CharacterCard.kt` | 角色卡领域模型 + 绑定模式 + 工具白名单（含 `normalized()`） | `CharacterCard`、`DEFAULT_ID="default_character"`、`CharacterToolAccessConfig`、`PromptTag/PromptTagType`、`PromptFunctionType` | `enabled=false`=不限制（最易搞反，已重点注释） |
| `model/character/CharacterPromptComposer.kt` | 提示词四段拼接 + 工具过滤 | `CharacterPromptComposer.compose/composeWithoutTags`、`CharacterToolFilter.allowedTools/isAllowed` | **`CharacterToolFilter` 全仓零调用点** → 角色卡工具白名单从未生效（G8） |
| `model/character/TavernCard.kt`（24 KB） | 酒馆 V1/V2 解析 + `extensions.operit` 往返 + 世界书→单标签 + 导出 + 「粘卡当提示词」 | `TavernCardImporter.parse/toCharacterCard/promptFromCardJson`、`TavernCardExporter.toJson`、`OperitTavernExtension.accepts` | 导出把 `description/personality/scenario` 写成空（只靠 operit 扩展回读）→ 导到 Operit 之外的工具有损（有注释，属有意互操作取舍） |
| `model/linux/LinuxPackageGroups.kt` | 六组软件包 + 容器安装脚本 | `BuiltInPackageGroups`、`buildPackageInstallScript` | `installArgs` 无调用点（脚本用 `flatMap`）；只被 `feature:settings` 用 |
| `model/linux/VirtualKeyboardLayoutCodec.kt` | 键帽标签规范化（严格校验整体回退） | `sanitize/isDefault`、`DEFAULT_LABELS`、`MAX_LABEL_LENGTH=8` | 与 `feature:terminal` 的 `DefaultTerminalKeyLayout` 双份字面量（已登记） |
| `model/permission/PraxisCapability.kt` | 8 项能力 + 状态/通道/快照 + 五级权限推导 | `PraxisCapability`、`CapabilityStatus`、`GrantChannel`、`CapabilitySnapshot.deriveFrom/Unknown` | 五级推导对「用户选择的档位」无感知（与 `permission_level` 无读侧呼应，G2） |
| `model/settings/ApiProvider.kt` | 14 家预设（地址/模型/是否需 key） | `ApiProvider`、`fromNameOrDefault`、`hasFullDefaults` | 只收 OpenAI 兼容家（有意收窄）；文档/PORTING 里提到的 21 家未在此 |
| `model/settings/FunctionModelSettings.kt` | 7 个功能位覆盖 + `resolve()`（逐字段回落） | `FunctionSlot`(7)、`FunctionModelOverride`、`FunctionModelSettings.resolve`、`ResolvedModel` | `IMAGE` 槽位被功能模型页 filter 掉（写在 KDoc）；`resolve` 不含 maxTokens/baseUrl 之外的传输参数 |
| `model/settings/ModelAdvancedSettings.kt` | 自定义请求头 + 队列两项 + `\u001F` 编解码 | `CustomHeader`、`serializeHeaders/parseHeaders`、`configuredHeaderCount`、`hasQueueLimits` | 后两个属性仅测试使用（KDoc 已承认） |
| `model/settings/ModelProfile.kt` | 多配置实体 + 集合视图 | `ModelProfile`、`ModelProfileKind`(Single/Gateway)、`ModelProfileSet.active` | `isConfigured` 透传 `ModelSettings`；Gateway 形态下恒 false（地址/模型由账号池给）→ UI 判「未配置」需另判 kind |
| `model/settings/ModelSettings.kt` | 单套配置（模型池/索引/温度/上下文/压缩阈值/思考） | `activeModel`、`modelList`、`isConfigured`、`parseModelList/joinModelList` | 逗号池 + `modelIndex`；`contextLimit=0` 表示未知（压缩则只靠条数阈值） |
| `model/settings/TerminalSettings.kt` | 终端三项 + `sanitized()` | `fontSizeSp/scrollbackLines/showBanner` | 与 `TerminalViewModel` 的消费侧取值域需保持一致（读盘已夹） |
| `model/settings/ThemeSettings.kt` | 主题/取色/调色板/规范/预测性返回 | `ThemeMode/ColorSource/PaletteStyle/ColorSpecVersion/PredictiveBackAnimation/PredictiveBackExitDirection`、`ThemeSettings`、`DEFAULT_SEED_ARGB` | `ColorSource.Custom` 与 `seedColorArgb` 的关系由 UI 保证（模型层无约束） |
| `model/settings/ThinkingEffort.kt` | 四档思考强度（被 `core:ai` typealias） | `ThinkingEffort` | 无 |
| `model-test/MessageTimestampAllocatorTest.kt` | 严格递增/时钟回拨/observe 前推不回退（4 例） | — | 通过 |
| `model-test/character/TavernCardPasteTest.kt` | Operit 卡粘进提示词框/V1/普通文本不当卡/缺名/空串（5 例） | — | 通过 |
| `model-test/linux/LinuxPackageGroupsTest.kt` | 六组唯一/脚本含 noninteractive/update 容错/clean/去重（11 例） | — | 通过 |
| `model-test/linux/VirtualKeyboardLayoutCodecTest.kt` | 数量校验整体回退/空标签/超长/换行制表/边界（13 例） | — | 通过 |
| `model-test/settings/ApiProviderPresetsTest.kt` | 端点合法性/云端有全默认/本地不需 key/Custom/按名回落（6 例） | — | 通过 |
| `model-test/settings/FunctionModelSettingsTest.kt` | 默认跟随/只换模型/换服务商连地址/清空覆盖/空全局（6 例） | — | 通过 |
| `model-test/settings/ModelAdvancedSettingsTest.kt` | 头往返/丢弃空键/奇偶不齐/保留逗号竖线/trim 规则（10 例） | — | 通过 |
| `model-test/settings/ModelSettingsTest.kt` | 拆池/取 activeModel/越界回落/join 去重保序（15 例） | — | 通过 |

### 3.3 core:data（27）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| `core/data/src/main/AndroidManifest.xml` | 仅 `uses-sdk tools:overrideLibrary`（miuix 7 个包） | — | 该模块本身不声明任何权限（权限在 `:app`） |
| `data/permission/CapabilityDetector.kt` | 8 项能力真实检测（每条路径不同） | `refresh()`、`snapshot: StateFlow` | 存储用 `isExternalStorageManager`（正确）；Shizuku 用反射；`ACCESSIBILITY` 无服务声明 → 恒 UNSUPPORTED（有意） |
| `data/permission/CapabilityRequester.kt` | 「去授予」三条路径 + 兜底设置页 | `request(capability, launcher): Boolean`、`PermissionLaunchBridge`、`openAppSettings` | 返回 Boolean 让 UI 知道是否真弹了（好）；`requestBatteryExemption` 用 Play 受限 action（注释已说明不上架） |
| `data/character/CharacterDao.kt` | 角色卡/标签 DAO + 跨表事务说明 | `CharacterCardDao`、`PromptTagDao.findCardsReferencingTag` | 无 `@Transaction`（正确地把事务放 Repository） |
| `data/character/CharacterDatabase.kt` | 最小 DB 接口（2 DAO + 事务） | `CharacterDatabase.withCharacterTransaction` | 无 |
| `data/character/CharacterEntities.kt` | 2 张表实体 + `\u001F/\u001E` 列表编码 + 工具白名单紧凑编码 | `CharacterCardEntity`、`PromptTagEntity`、`SplitCodec`、`ToolAccessCodec`（internal） | `isDefault` 在 SQL 里为 INTEGER；DOA 用 `isDefault DESC` 排序 |
| `data/character/CharacterRepository.kt` | 卡/标签 CRUD + 默认卡 + 活跃卡（DataStore）+ 酒馆导入导出 + 删标签事务 | `cards/tags/activeCard/activePrompt`、`ensureDefaultCard`、`save/delete/setActive`、`createOrReuseTag`、`deleteTag`、`importFromTavernJson/exportCardAsTavernJson` | `activePrompt` 吞异常返回空串（有意）；导入时 `applyTagRemap` 丢弃映射不到的旧 id（有意） |
| `data/gateway/GatewayAccountEntity.kt` | 账号表（凭证密文列） | `GatewayAccountEntity` | 无外键到 provider（自定义家被删后账号悬空，靠选路过滤） |
| `data/gateway/GatewayAccountMapper.kt` | 实体→`AccountView`（三态能力位保留 null） | `toAccountView` | 无 |
| `data/gateway/GatewayAccountStore.kt` | 导入落库（先校验+加密再写、保留用户选择）、手动加号、凭证读写、行模型 | `applyImport/addAccount/credentialOf/routingViews/accountRows/nativeModels/addProvider/updateProviderModels`、`StoredImport` | `addProvider` 覆盖写时**不保留** `extraHeadersJson/urlSuffix/modelsFetchedAt`（走默认值）→ 用户改地址会丢 quirks 头（G10，低危）；`updateProviderModels` 写的是 JSON 数组而 `toEntity` 写换行串（两种格式并存，靠 `decodeLines`/`nativeModels` 各自解析）→ G11 |
| `data/gateway/GatewayDao.kt` | 账号/自定义家 DAO（ORDER BY priority） | `GatewayAccountDao`、`GatewayProviderDao` | 无 |
| `data/gateway/GatewayModelDao.kt` | 结构化模型条目 DAO（`enabled()` 单列查询） | `GatewayModelDao` | 无 |
| `data/gateway/GatewayModelEntity.kt` | `gateway_model` 表（主键 provider+id，三态布尔列） | `GatewayModelEntity` | 枚举/列表存文本（有意） |
| `data/gateway/GatewayModelMapper.kt` | 实体⇄`ModelEntry`（换行列表编解码，未知枚举抛错） | `toEntity/toModelEntry` | 抛错策略与 `gateway_model` 迁移同源（有意） |
| `data/gateway/GatewayProviderEntity.kt` | 自定义家表（含 quirks：extraHeadersJson/urlSuffix/modelsFetchedAt） | `GatewayProviderEntity` | 三列都在 v15/v16 迁移里补的 |
| `data/gateway/GatewayUsageEntities.kt` | `gateway_request` / `gateway_daily` + 报表聚合 DAO | `GatewayUsageDao.summarySince/byModelSince/tokensByAccountSince/recentRequests`、`GatewayUsageSummaryRow` 等 | 日聚合表**没有写入方**（DAO 有 upsert，但报表直接扫明细，注释已说明）→ 表在但空 |
| `data/log/LogLine.kt` | 日志行协议（写读同源）+ 解析器 | `LogLine.format`、`LogParser.parse`、`LogEntry` | 正则 tag 段 `[^:]*`，tag 里不能有冒号（写端把 null 渲染成字面 `null`，读端归一，有测试） |
| `data/log/PraxisLogger.kt` | 异步有界队列落盘（重要级别预留 128 容量）+ 轮转 + 3 天保留 + 分片 + flush 屏障 | `v/d/i/w/e/log/logChunked/flush/clearLogs/allLogFiles/logsDir` | 需先 `init(context)`，否则只用 logcat；`clearLogs` 顺序正确（先关句柄） |
| `data/log/PraxisTimberTree.kt` | Timber→PraxisLogger，白名单栈解析 caller tag | `PraxisTimberTree(resolveCallerTag)` | release 下 tag=null（有意） |
| `data/security/KeystoreCipher.kt` | AES/GCM（Keystore 内密钥）+ `looksEncrypted` | `encrypt/decrypt/looksEncrypted`、`DEFAULT_ALIAS` | 失败返回 null（调用方降级明文，与网关凭证的策略相反 —— 后者失败即中止）；判据弱（含冒号 + 两段可 Base64 即视为密文）→ 恰好形如 `abc:def` 的明文 key 会被当密文解失败 → 用户 key「消失」（G12，低概率） |
| `data/settings/SettingsRepository.kt`（1743 行） | 全部设置读写（60 静态键 + 3 动态族）+ 多配置 + 密钥 + 功能位表 | 见 §5 账本；工厂 `createSettingsRepository/createAppPreferencesStore/createCharacterRepository` | 见 §5 与 G1/G2/G13（apiKey 与 profile 的迁移缺口） |
| `data-test/gateway/GatewayAccountStoreTest.kt` | 导入加密/全有或全无/保留用户选择/无地址家 | — | 用假 DAO（不起 Room） |
| `data-test/gateway/GatewayAccountMapperTest.kt` | 三态能力位保留 null | — | 无 |
| `data-test/gateway/GatewayModelMapperTest.kt` | 往返相等 + 未知枚举抛错 | — | 无 |
| `data-test/log/LogParserTest.kt` | 写→读 round-trip（格式锁死） | — | 无 |
| `data-test/security/KeystoreCipherTest.kt` | 只测 `looksEncrypted` | — | encrypt/decrypt 无纯 JVM 可测路径（已说明） |
| `data-test/settings/ModelProfilesTest.kt` | 多配置前缀键契约（15 个用例 + 15s 超时闸门） | — | **不含 apiKey 的 profile 化与 `ensureProfiles` 后的 key 迁移** → G13 无测试守卫；`rapid creation` 用例被移除（注释登记） |

### 3.4 core:plugin（36）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| `plugin/ComposeDslTree.kt` | compose_dsl 渲染树解析 + 组件白名单（11 可渲染 / 79 已知） | `ComposeDslNode`、`ComposeDslRenderResult`、`ComposeDslParser.parse`、`ComposeDslComponents.RENDERABLE/KNOWN` | 只保留标量 props + 一层 modifier（有意）；渲染器不在本模块 |
| `plugin/ExtensionEntry.kt` | 四类扩展统一视图 + 图标缓存 | `ExtensionEntry`、`Kind`(4)、`ExtensionIcons`、`InstalledPlugin.toExtensionEntry` | `toolCount=-1` 表示不适用 |
| `plugin/ExtensionSnapshot.kt` | 四源合并快照 + 排序 + 沙盒「第二视角」 | `ExtensionSnapshot`、`ExtensionSources`、`buildExtensionSnapshot`、`installedVersionOf`、`grouped` | 同一包可出现两次（toolpkg + sandbox，有意）；排序键 `isUsable→kind→title` |
| `plugin/InstalledPlugin.kt` | 安装态（清单 + 启停 + 子包 + 来源 + 问题） | `InstalledPlugin`、`isActive/failureText/warningsText/isSubpackageEnabled/activeSubpackages`、`PluginOrigin` | 无 |
| `plugin/LocalizedText.kt` | 多语言文本 + 三种声明形态 codec + `ManifestValue` ADT | `LocalizedText`、`LocalizedTextCodec.parse`、`ManifestValue` | `Number` 存原文（避免 `1.0`→`1`） |
| `plugin/ManifestMapper.kt`（18 KB） | 清单映射（致命 vs 警告分级）+ 入口解析 + action 两种写法 | `ManifestMapper.map`、`ManifestMapping`、`ManifestProblem.Fatal/Warning` | `main` 不存在=致命，子包入口不存在=警告（分级有意）；缺 `toolpkg_id`/非法 `api_version` 亦致命 |
| `plugin/ManifestParser.kt`（15 KB） | 自研 HJSON/JSON 递归下降（注释/裸键/裸值/可省逗号） | `ManifestParser.parse`、`ManifestSyntaxException(line,column)` | 裸值遇到「非空白前的 //」才断（修过 URL 坑）；不支持多行字符串 |
| `plugin/MarketApi.kt` | 市场接口 + 摘要/详情模型 + `disabled()` 空实现 | `MarketApi`、`MarketEntrySummary/Detail/Version/Asset`、`MarketSort`、`MarketPage` | 摘要带 assets（因详情接口需 session，已说明） |
| `plugin/MarketEndpoints.kt` | 市场 URL 构造 + `MarketOriginCodec`（XOR 0x5a 混淆） | `allListUrl/typeListUrl/entryUrl/assetDownloadUrl/encodeSegment`、`MarketOriginCodec.encode/decode/validate/toPluginOrigin` | `trackDownloadUrl` 与下载同地址（会重复计数，KDoc 已警告） |
| `plugin/MarketJsonParser.kt` | 宽容解析列表/详情（字段改类型不崩） | `parseList/parseDetail`、`DEFAULT_PAGE_SIZE` | 无 total 时用「满页即还有下一页」启发式 |
| `plugin/McpAndSandbox.kt` | MCP 配置/传输/工具模型 + 两个 `toExtensionEntry` + `providesUi()` | `McpServerConfig.usability`、`McpTransport`、`Skill.toExtensionEntry`、`PluginManifest.providesUi`、`SandboxPackageInfo` | `stdio` 恒不可用（有意）；MCP 启停=连不连，无文件动作 |
| `plugin/PluginActivation.kt` | 启动装载编排（逐包隔离失败）+ 可观察报告 | `PluginActivation.activateAll`、`ActivationReport`、`PluginSource` | 清单致命项也计入 failed（修过「只认异常」的 bug） |
| `plugin/PluginArchive.kt` | 归档端口 + zip-slip 归一化 + `InMemoryPluginArchive` + Reader（找清单/读脚本/文本表） | `PluginArchive`、`normalizePath`、`PluginArchiveContent`、`PluginArchiveReader.read` | 清单只认根或根下一层；多候选按字典序取一（不报错，注释说明） |
| `plugin/PluginEntryPoint.kt` | 入口模型（4 种 kind）+ `renderTargetId` + 聚合排序 + 双来源优先级 | `PluginEntryPoint`、`PluginActionHook`、`EntrySurfaces`、`EntryOrigin`、`InstalledEntryPoint`、`collectEntryPoints(captured)` | 运行时捕获**整体**覆盖清单声明（有意）；`renderTargetId` 依赖 route 里的 `:ui:` 标记 |
| `plugin/PluginLoadOrder.kt` | 依赖拓扑（不动点剔除 + Kahn + TreeSet 稳定序 + DFS 找环） | `PluginLoadOrder.resolve`、`Ordering(ordered/skipped/cycles)` | **链式失效不传播**：A 依赖被跳过的 B 时 A 仍在 ordered（G5，见 §7.2） |
| `plugin/PluginManifest.kt` | 清单领域模型 + SemVer + API 版本兼容（集合内判定） | `PluginManifest`、`PluginRequirement.validate`、`SemVer.parseOrNull`、`PluginApiCompatibility.check` | `wasmModules` 只解析不执行（有意）；API 版本「精确在集合内」而非常规比较（有意） |
| `plugin/PluginRegistrationCodec.kt` | 运行时注册结果 JSON→入口 | `PluginRegistrationCodec.parse`、`SCREEN_FROM_SCRIPT` | 与引导脚本的键名一致（`has_screen/action_function/action_source`，已核对 `PluginRuntimeBootstrapScript`） |
| `plugin/PluginRepository.kt` | 扫描/安装（预检后落盘）/卸载/启停/更新检查 + `externalArchiveFactory` 静态注入点 | `scanAll/loadOne/install/inspect/uninstall/setEnabled/findUpdate`、`PluginStorage`、`PluginSource` | 静态可变工厂（全局状态，测试必须清）；有致命问题时**强制 enabled=false**（PluginRepository.kt:186）；安装要求 id 不含路径分隔符 |
| `plugin/PluginRuntimeBootstrapScript.kt` | 注入 JS：`ToolPkg.*` 注册收集 + CommonJS `require` + `__praxis_render/invoke/run_registration` | `PluginRuntimeBootstrapScript.JS` | `__praxis_screens` 只存函数句柄（正确）；`readResource/wasm.call` 在注册期抛错（照文档） |
| `plugin/PluginRuntimeHost.kt` | 装载→注册工具/入口→记账→卸载；`PluginBackedTool`（STANDARD/Dangerous） | `load/unload/toolsOf/entriesOf/capturedEntries/allPluginToolNames/loadedCount`、`PluginToolDeclaration`、`PluginScriptRuntime`、`PluginLoadOutcome` | 工具来自子包 METADATA（`ScriptMetadataParser`）而非 `registerTool`（已纠正）；插件工具固定 STANDARD 档位（有意） |
| `plugin/ScriptMetadata.kt` | 子包 METADATA 解析 + 工具声明（名字清洗/`_` 分隔/撞名加序号）+ JSON Schema 生成 | `ScriptMetadata`、`ScriptMetadataParser.parse/toToolDeclarations/sanitizeToolName/buildParametersSchema`、`METADATA_PATTERN` | `advice` 工具不进注册（有意）；工具名 64 上限 |
| `plugin/Skill.kt` | 技能模型（两个调用开关四象限）+ 提示词片段 + 扫描结果 | `Skill`、`modelInvocable`、`toPromptSection`、`SkillScanResult/SkillSkip` | 无 |
| `plugin/SkillParser.kt` | SKILL.md frontmatter 解析（连字符键，驼峰拒收） | `SkillParser.parse` | 缺 frontmatter/name/description 不失败只警告（有意） |
| `plugin/SkillRegistry.kt` | 技能扫描/去重（按目录）/注入片段/可见性过滤 | `SkillSource`、`SkillRegistry.reload/all/skipped/composePrompt/modelVisible/userInvocable/bodyForUserInvocation` | 按目录名去重（有意）；`reload` 吞 `listSkillFiles` 异常→空表（静默，G14 低危） |
| `plugin-test/InMemoryPluginStorage.kt` | 单测用内存存储（含 `seed/putRaw/seedSource`） | — | `installFrom` 只认自己的私有 `seeds`（`seedSource` 写入）→ 与 `PluginRepositoryTest` 的 `externalSources` 不是一份数据（§7.1 根因之一） |
| `plugin-test/ComposeDslParserTest.kt` | 树/插槽/modifier/action/未知组件 | — | 通过（静态核对） |
| `plugin-test/ManifestParserTest.kt` | HJSON 语法 + 映射分级 + 版本约束 | — | 通过 |
| `plugin-test/MarketAndSnapshotTest.kt` | 3 个类：市场解析（16）+ 扩展快照（9）+ 归档路径（8） | — | 快照类 1 条失败**未确认**（§7.3） |
| `plugin-test/PluginActivationTest.kt` | 装载编排 7 条 | — | 通过 |
| `plugin-test/PluginEntryPointTest.kt` | 入口解析/别名/action/聚合/渲染目标 17 条 | — | 通过 |
| `plugin-test/PluginLoadOrderTest.kt` | 加载顺序 10 条 + 市场端点 + 来源 codec | — | **2 条失败**（§7.2） |
| `plugin-test/PluginRegistrationCodecTest.kt` | 注册结果解析 7 条 | — | 通过 |
| `plugin-test/PluginRepositoryTest.kt` | 扫描/安装/卸载/启停/更新 21 条 | — | **6 条失败**（§7.1） |
| `plugin-test/PluginRuntimeHostTest.kt` | 工具生命周期 20 条（含 METADATA 工具） | — | 通过 |
| `plugin-test/ScriptMetadataParserTest.kt` | METADATA 解析 + schema + 名字清洗 10 条 | — | 通过 |
| `plugin-test/SkillTest.kt` | 2 个类：解析 8 + 注册表 13（含两个开关） | — | 通过 |

---

## 4. 关键机制深挖

### 4.1 请求构造链（重点回答 1）

**装配点**：`app/src/main/java/dev/praxis/ui/PraxisApp.kt:1095`构造 `SettingsBackedProvider`，传 `queueWaitSeconds={gatewaySystem.queueWaitSeconds}`、`sanitizeRules={gatewaySanitize.effectiveRules}`、`gatewayProvider=GatewayProvider(...)`。
**注意**：同一文件 `:1341`（标题生成器）再造了一个 `SettingsBackedProvider`，但那里 `modelRules = { GatewayModelRules() }`（空），而 `:1107` 的对话链传的是 `{ gatewayModelRules }` —— **标题生成不走用户的模型映射**（G15）。

数据流（每一步都是真实代码位置）：

```
ChatViewModel.buildPreparedMessages()
  └─ CompactionApplier.apply(full, compacted)        core:ai/ContextCompactor.kt（按摘要位置裁剪）
  └─ ContextAssembler.assemble(withSummary, maxChars) core:ai/ContextAssembler.kt:29
       ├─ (compact) CompactionPolicy.compact*          ← 现在是恒等
       └─ AiContextBudget.fit(messages, maxChars)      ← 只整条丢弃（system/tool/assistant-toolcall/最后一条 user 被 pin）
  ↓ ChatRequest(model=chatOverride.model, temperature=chatOverride.temperature)
SettingsBackedProvider.stream()                      app/data/ai/SettingsBackedProvider.kt:58
  ① OutboundSanitizer.apply(每条 message.text)        ← 全链最前，两条路共用（:64-72）
  ② GatewayGate.decide(profileKind, gatewayEnabled)   ← 网关档案/总开关
       GatewayDisabled → 抛 INVALID_REQUEST（不回落单配置）
       Gateway         → gatewayProvider.stream(outbound) 后 return
  ③ settings = modelSettings.first()                  ← 当前 profile（无 profile 读旧扁平 key）
  ④ chatOverride = functionModelSettings.resolve(CHAT, settings)  ← CHAT 覆盖生效
  ⑤ apiKey()、modelAdvancedSettings.first()           ← 自定义头 / RPM / 并发
  ⑥ 空 baseUrl/空 model → 抛 INVALID_REQUEST（不再发注定 401 的请求）
  ⑦ throttleFor(rpm, concurrency, waitSeconds).tryAcquire()  ← 并发位先于限流；超时抛 RATE_LIMIT
  ⑧ OpenAiCompatProvider(baseUrl=chatOverride.baseUrl, apiKey, customHeaders).stream(
        outbound.copy(model=chatOverride.model, temperature=chatOverride.temperature))
     └─ ChatEndpoint.resolve(baseUrl) + urlSuffix      ← core:ai/ChatEndpoint.kt
     └─ SseParser.feed(chunk) → OpenAiStreamAssembler.feed/finish
     └─ blockedWithoutOutput() → 抛 CONTENT_BLOCKED
  finally: permit.close()
```

**各部分的位置与失败行为（逐项）**

| 环节 | 位置 | 触发条件 | 失败行为 | 评价 |
|---|---|---|---|---|
| `OutboundSanitizer` | `SettingsBackedProvider:64` | 用户开了脱敏且规则非空 | 坏正则**单条跳过**（`compile().broken`），不阻断请求；替换默认 `***` | 位置正确但**覆盖面不足**（G3：只改 `text`） |
| `GatewayGate` | `SettingsBackedProvider:88` 附近 | 档案 kind=Gateway 且总开关关 | 抛 `INVALID_REQUEST` 并给中文可操作文案；**不回落**单配置（有意） | 正确（防「悄悄用别的账号发出去」） |
| 功能位解析 | `SettingsBackedProvider:120` 附近 | CHAT 有覆盖 | 逐字段回落（`resolve`） | 正确（2026-10-29 修的「配了不用」） |
| 配置完整性 | 同上 | baseUrl/model 任一为空 | 抛 `INVALID_REQUEST`（中文提示去设置页） | 正确 |
| `RequestThrottle` | `SettingsBackedProvider:170-186` | RPM/并发任一 >0 | 超 `maxWaitMs` → 抛 `RATE_LIMIT`「排队超时」；取消时归还并发位 | 正确；实例按 `(rpm,concurrency,wait)` 缓存且只留一份（配置一变即重置窗口） |
| `ChatEndpoint` | `OpenAiCompatProvider:237` | baseUrl 形态异常 | 不猜、不静默修好；拼错就让上游 404 | 正确（有 17 条测试） |
| `SseParser` | `OpenAiCompatProvider:188-206` | 跨 chunk 切断 | 字节级残留缓冲，`finish()` 兜末尾无空行 | 正确；超 1MB 无边界 → 抢救完整事件后丢弃并置 `didDropOversize`（provider 未读该标志 → 丢字节这件事只体现在「这段流没有产出」，G16 低危） |
| `OpenAiStreamAssembler` | `OpenAiCompatProvider:206-225` | 分片 tool_calls | 按 index 累积，`finish` 才发 `ToolCalls`；`ToolCallDelta` 只报计数 | 正确 |
| 拦截判定 | `OpenAiCompatProvider:216` | 200 + `finish_reason=content_filter` 且无正文/无工具 | 抛 `CONTENT_BLOCKED`（不罚账号的语义由上层用） | 正确（已吐内容时不判，避免「答一半重说」） |
| 上下文预算 | `ChatViewModel:2015-2017` | 超 `DEFAULT_MAX_CHARS` | 从最旧可丢消息整条丢弃；**绝不**截断 argumentsJson/system（有 3 条测试锁死） | 正确；预算按字符，不感知模型真实上下文 |
| 压缩 | `ChatViewModel:2122-2186`（自动）、`:3903-4052`（手动） | token 占比≥阈值 **或** 自上次摘要 user 条数≥`messageCountThreshold` | 失败时 `getOrNull()` 静默返回（不压），不阻塞本轮回答 | 正确；但阈值/规则不可配（G4） |

### 4.2 上下文治理三层（预算 / 压缩 / 摘要）+ token 计量

- **计量**：`TokenEstimator.estimate`（汉字 ×1.5 + 其他 ×0.25 + 每条 +4）是唯一口径；`TokenCache` 做公共前缀增量（消息相等比较）。`ChatViewModel` 在每轮 `buildPreparedMessages` 后调 `count`，压缩生效后必须 `reset`（代码里做了）。
- **压缩判定**：`ContextCompactor.shouldCompact` 双阈值。关键点：`maxTokens=0`（用户没填上下文上限）时 token 分支恒不成立，**只靠条数阈值**兜住 —— 这是测试里明确锁死的一条（「上限未知时靠条数阈值也能压缩」）。
- **折叠范围**：`compactCount(total) = total - KEEP_RECENT_MESSAGES(8)`，少于 2 条不值得压。Praxis 不自动续写（与 Operit 的差异写在 KDoc）。
- **摘要输入瘦身**：`ContextCompactor.renderMessages` 按角色走 `SummaryCondenser` 的策略（用户 240/96、助手 120/48、**最后一段** 280/120、工具 140/56、超 25 段留头 12 尾 10），并给每条加 `#序号`。
- **摘要落地**：摘要被构造成一条 **Assistant 消息**（`CompactionApplier.summaryMessage`）插入 history，之后 `apply` 按「最后一条摘要的位置」裁剪 —— 这是 R8 之后的设计（旧 `coveredCount` 游标仅作兼容路径）。**摘要跨进程靠文本头 `# 早前对话摘要` 识别**，两处共用同一常量，但改文案就等于让旧摘要失效（G17 设计风险，非 bug）。
- **可观测**：`CompactedContext.coveredTokens/savedTokens` 允许负收益如实显示（有测试）。

### 4.3 SettingsRepository 的多配置 key 前缀机制（最容易出「改了不生效」的地方）

存储形状（全部在同一个 `praxis_settings` 文件里）：

```
model_profile_ids    String  "p<ts>-<seq>
p<ts>-<seq>"（换行分隔，保序）
model_profile_active String  当前 profile id
model_profile_names  String  "id\u0001name" 逐行
model_profile_kinds  String  "id\u0001Single|Gateway" 逐行（没有该行 = Single）
model_profile_seeded Boolean 只播种一次
p:<id>:model_base_url / model_id / model_index / model_temperature /
p:<id>:model_provider / context_limit / compact_threshold /
p:<id>:thinking_effort / model_api_key
```

- **读写对称性**：`readModelSettings(prefs, profileId)` 用 `profileKey(profileId, "model_id")`；所有 setter 走唯一的 `putModelPref*`（SettingsRepository.kt:548-583），内部再算一次「当前 active id」。**profileId 为 null → 原样用旧扁平 key**，这是老用户零迁移的全部秘密。
- **id 生成**：`nextProfileId() = "p" + now + "-" + seq`，并在 `edit{}` 内与已有 id 比对去重（修过「同毫秒撞 id」）。
- **风险点（不是 bug，但值得记住）**：`deleteProfile` 不删带前缀的 key 本体（有意：没有批量删前缀 API），所以「删了配置又新建」时旧键成为死数据。
- **apiKey 与 profile 的错位**：见 4.4 / G13。

### 4.4 密钥加密与 apiKey 的 profile 化（发现一处真实缺口）

- 存储：`setApiKey`（SettingsRepository.kt:926）按 `profileScopedApiKeyName()`（:955）算名字 → `p:<activeId>:model_api_key`；有 cipher 时存密文，加密失败**降级存明文**。
- 读取：`apiKey()`（:968）同上算名字；若值不像密文（`looksEncrypted`）则原样当明文（v1 兼容）。
- 迁移：`migrateApiKeyIfNeeded`（:988）**只处理旧扁平键 `model_api_key`**。
- **缺口**：`ensureProfiles`（:394）给老用户播种「默认配置」时，只搬 `readModelSettings(prefs, null) → writeModelSettings(prefs, defaultId, …)`，**没有把 `model_api_key` 抄到 `p:<defaultId>:model_api_key`**。播种之后 `apiKey()` 只读新名字 → 老用户升级后第一次启动，密钥读出来是空串（表现为「模型未配置 / 401」），而 `migrateApiKeyIfNeeded` 修的仍是那个已经没人读的旧键。
  证据链：`ensureProfiles` 体内无 api_key 相关语句（SettingsRepository.kt:394-450）；`profileScopedApiKeyName` 只认 profile 名；`ModelProfilesTest` 没有 apiKey 用例。→ **G13（严重）**。

### 4.5 角色卡 → 系统提示词 → 工具白名单

```
CharacterRepository.activePrompt()                 data/character/CharacterRepository.kt
  ├─ active_character_card_id (DataStore)
  ├─ cardDao.findById(id) → CharacterCard
  ├─ tagDao.findAll() → id→PromptTag 索引
  └─ CharacterPromptComposer.compose(card, tagLookup)   ← 四段顺序：设定→其他内容→标签→高级
        （空段整段跳过 + trim；标签按 id 查、查不到静默跳过）
↑ 该字符串作为 systemPromptProvider 的一层，交给 core:ai 的 SystemPromptBuilder 拼成**一条** system
```

- 角色卡工具白名单本应在这里生效：`CharacterToolFilter.allowedTools(config, allToolNames)`（core:model）。**全仓零调用点** → 用户给角色卡配的白名单永远不影响发给模型的工具集（G8）。同类还有 `CharacterToolFilter.isAllowed`。
- `SystemPromptBuilder.buildLayers` 现在只产出**一条** system（照 Operit），顺序由 `SystemPromptTemplates.DEFAULT` 的占位符排列决定；`PromptPriority` 枚举无人读取（顺序只靠 `SystemPromptBuilderTest` 的 indexOf 断言守）。
- 用户在设置里粘贴整张 Operit/酒馆卡 JSON 时，`TavernCardImporter.promptFromCardJson` 会把它解释成卡再组装提示词（有 5 条测试）；不是卡（不以 `{` 开头/解析失败/无 name）就返回 null，走原文。

### 4.6 插件装载链（scan → 校验 → 落盘 → 装载 → 工具/入口）

```
PluginRepository.scanAll()                          plugin/PluginRepository.kt
  ├─ storage.listInstalledIds() → loadOne(id)
  │    ├─ readManifestText → ManifestParser.parse（语法错 → 占位包 + Fatal，仍列出）
  │    ├─ ManifestMapper.map(entryExists = archive.entryNames())
  │    ├─ 清单 id ≠ 目录名 → 加 Warning，**以目录名为准**
  │    └─ enabled = storage.isEnabled(id) && 无 Fatal     ← :186 强制停用（修过「开关开着但加载不出来」）
  └─ 排序：enabled desc → title → id

PluginActivation.activateAll()                      plugin/PluginActivation.kt
  for plugin（按 id 排序，稳定）:
    !isActive → skipped
    openArchive 为 null → failed（读不到安装目录）
    PluginArchiveReader.read(archive) 失败 → failed（读完 finally close，防 fd 泄漏）
    content.problems 有 Fatal → failed（**修过**：只认异常会把它当装载成功）
    host.load(plugin, content)

PluginRuntimeHost.load(plugin, content)             plugin/PluginRuntimeHost.kt
  ① unload(plugin.id)  ← 更新时先卸旧，否则删掉的工具会残留
  ② !enabled / 清单 Fatal / mainScript 为空 → 返回 errors（不注册）
  ③ 只取启用中的子包（用**安装态** manifest 而非 content 的）
  ④ runtime.load(...)：执行 main + 子包，收 registerUiRoute/NavigationEntry 等注册
  ⑤ 子包脚本的 METADATA → ScriptMetadataParser → toToolDeclarations(subId, md)
       （名字 = sanitize(subId + "_" + tool)，撞名加 _2；advice 工具跳过）
  ⑥ entryPoints 先记账；allTools 非空才 registerOrReplaceAll + 记账
  ⑦ 工具或入口任一非空 → loadedPackageIds（决定 unload 是否调 runtime）

入口展示：list<InstalledPlugin>.collectEntryPoints(runtimeHost.capturedEntries())
  → 每包：有捕获则**整体**用捕获（EntryOrigin.Runtime），否则用清单 entries（EntryOrigin.Manifest）
```

失败隔离：任一环节失败只进 `ActivationReport.failed`（带逐条原因），不影响其它包；报告是 `StateFlow`，界面能观察到「装完了」。

### 4.7 插件加载顺序（一个真实缺陷所在）

- 步骤：① 按自身可用性筛（Fatal → skipped；未启用 → 静默不加载）→ ② **不动点循环**剔除依赖不满足者 → ③ Kahn 拓扑（`TreeSet` 保证同层按 id 字典序）→ ④ 剩余节点 DFS 找环（返回闭合路径）。
- **缺陷**：`firstDependencyFailure` 里「依赖目标已被跳过」的判定写成了

  `requirement.id !in eligibleIds && target.problems.any { it is ManifestProblem.Fatal }`（PluginLoadOrder.kt:161）

  也就是只有「目标自身有致命清单问题」才判失败；**目标因别的原因被跳过（依赖缺失/版本不满足/环）时，依赖它的包仍被判合格**。后果：A 依赖 B 因 C 坏掉而被跳过 → A 仍在 `ordered` → 装载 A 时 `require` 拿到 undefined（正是 KDoc 里描述的那种「与真正原因隔三层」的错误）。这条被 `PluginLoadOrderTest.链式失效会传递` 抓到（§7.2）。

### 4.8 日志「写读同源」

`LogLine.format` 是唯一写格式（时间戳 + 单字符级别 + `/Praxis ` + caller tag + `: ` + 正文），`LogParser.parse` 是唯一读格式（一条正则 + 续行折叠 + 孤立行保留 + `trimEnd('\n')` 防末尾空行并进最后一条）。`LogParserTest` 的 round-trip 用例同时锁死字面格式（改格式必须同改测试），这条是本模块最值得复制的做法。写端 `PraxisLogger` 的三条纪律：有界队列 + 重要级别可越过预留线、E/W/A 立即 flush、`clearLogs` 先关句柄再删文件（否则继续写进不可见 inode）。

---

## 5. 重点回答 2：SettingsRepository 全部 key 账本

> 位置：`core/data/src/main/kotlin/dev/praxis/core/data/settings/SettingsRepository.kt`（1743 行）。
> 「读」= 本仓库真实存在的消费点（Flow / 挂起读方法被谁调用），不是「仓库内部用到」；「写」= setter 的调用文件。测试文件不计入消费方。
> 键名后带 ⚠ 表示**没有消费者**（违反 GOAL「设置项必须有消费方」）。␁ = U+0001，␟ = U+001F。

### 5.1 多配置结构（5 个）

| 键 | 类型 | 默认 | 读 | 写 |
|---|---|---|---|---|
| `model_profile_ids` | String（换行分隔、保序） | 空 | `modelSettings`(:89) / `modelProfiles` 内部 → 全 UI；`setActiveProfile`/deleteProfile 校验 | `createProfile / ensureProfiles / deleteProfile` |
| `model_profile_active` | String | 缺键 = null（回落旧扁平 key） | 同上 + `profileScopedApiKeyName`(:955) | `createProfile`（首个）/ `ensureProfiles` / `setActiveProfile` / `deleteProfile`（自动切） |
| `model_profile_names` | String（id␁name 逐行） | 空 | `modelProfiles`（配置下拉） | `createProfile / ensureProfiles / renameProfile / deleteProfile` |
| `model_profile_kinds` | String（id␁Single 或 id␁Gateway） | 没有该行 = Single | `modelProfiles` → `SettingsBackedProvider:80` 判「是否走网关」 | `createProfile / ensureProfiles` |
| `model_profile_seeded` | Boolean | false | `ensureProfiles`（幂等闸门） | `ensureProfiles` |

### 5.2 外观（8 个）

| 键 | 类型 | 默认 | 读 | 写 |
|---|---|---|---|---|
| `theme_mode` | String（枚举名） | System | `themeSettings` → PraxisApp / MainActivity / AppearanceRoute | `setThemeMode`（AppearanceRoute） |
| `color_source` | String | Wallpaper | 同上 | `setColorSource` |
| `seed_color_argb` | Long | 0xFF1B6EF3 | 同上 | `setSeedColor`（AppearanceRoute / SeedSwatch / SeedColorGrid） |
| `palette_style` | String | TonalSpot | 同上 | `setPaletteStyle` |
| `color_spec` | String | Spec2021 | 同上 | `setColorSpec` |
| `predictive_back_animation` | String | Aosp（经 fromNameOrDefault） | 同上 | `setPredictiveBackAnimation` |
| `predictive_back_exit_direction` | String | FollowGesture | 同上 | `setPredictiveBackExitDirection` |
| `use_miuix_engine` ⚠ | Boolean | **没有读点（无对应 Flow）** | **无** | `setUseMiuixEngine`(:1017) **零调用点** |

### 5.3 模型配置（扁平 key；有 profile 时自动加 p:id: 前缀，13 个）

| 键 | 类型 | 默认 | 读 | 写 |
|---|---|---|---|---|
| `model_base_url` | String | 空 | `readModelSettings`(:300) → `modelSettings`：SettingsBackedProvider / PraxisApp / ChatTab / ChatScreen / FunctionModelScreen / ModelRoute | `setBaseUrl`（ModelRoute） |
| `model_id` | String（逗号池） | 空 | 同上（取 `activeModel`） | `setModel`（ModelRoute） |
| `model_index` | Int | 0 | 同上 | `setModelIndex`（ModelRoute / ChatTab） |
| `model_temperature` | Double | 0.7 | 同上 → `ChatRequest.temperature` | `setTemperature`（ModelRoute） |
| `model_provider` | String（枚举名） | Custom | 同上 | `setModelProvider`（ModelRoute） |
| `model_api_key` | String（AES/GCM 密文；加密失败降级明文） | 空 | `apiKey()`(:968) → SettingsBackedProvider / GenerateImageTool / ChatTab / FunctionModelScreen / ModelRoute | `setApiKey`(:926)（ModelRoute / ModelScreen）；`migrateApiKeyIfNeeded`（MainActivity，**只迁旧键**，见 G13） |
| `function_models` | String（JSON 扁平表） | 空表 | `functionModelSettings` → SettingsBackedProvider / SettingsBackedTitleGenerator / PraxisApp / ChatTab / FunctionModelScreen | `setFunctionModel / clearFunctionModels`（FunctionModelScreen） |
| `context_limit` | Int | 0（未知） | `readModelSettings` → ChatViewModel `shouldCompact(maxTokens)` | `setContextLimit`（ModelRoute） |
| `compact_threshold` | Float | 0.75（读时夹 0.1–0.95） | 同上 → `shouldCompact(tokenUsageThreshold)` | `setCompactThreshold`（ModelRoute） |
| `thinking_effort` | String | None | `readModelSettings` → `ChatRequest.thinkingEffort` | `setThinkingEffort`（ModelRoute / ChatTab / ChatScreen） |
| `model_custom_headers` | String（密文，␟ 分隔） | 空 | `modelAdvancedSettings` → SettingsBackedProvider（传 provider）/ ModelRoute / ModelScreen | `setCustomHeaders`（ModelRoute） |
| `model_request_limit_rpm` | Int | 0 = 不限 | 同上 → `throttleFor(rpm, …)` | `setRequestLimitPerMinute`（ModelRoute） |
| `model_max_concurrent` | Int | 0 = 不限 | 同上 → `throttleFor(…, concurrency, …)` | `setMaxConcurrentRequests`（ModelRoute） |

### 5.4 提示词（2 个）

| 键 | 类型 | 默认 | 读 | 写 |
|---|---|---|---|---|
| `system_prompt` | String（原文，不 trim） | 空 | `systemPrompt` → PraxisApplication / PraxisApp / ChatViewModel / SystemPromptRoute | `setSystemPrompt`（SystemPromptRoute） |
| `persona_prompt` | String | 空（回落 BUILTIN_PERSONA） | `personaPrompt` → PraxisApp / SystemPromptRoute | `setPersonaPrompt`（SystemPromptRoute） |

### 5.5 网关与队列（11 个 + 1 个动态键族）

| 键 | 类型 | 默认 | 读 | 写 |
|---|---|---|---|---|
| `gateway_model_rules` | String?（JSON 原文） | null | `gatewayModelRulesJson` → PraxisApp:1107 → GatewayProvider.modelRules（**标题生成那条链传空**，G15） | `setGatewayModelRulesJson`（PraxisApp 导入保存） |
| `gateway_enabled` | Boolean | **true** | `gatewayEnabled` → SettingsBackedProvider / GatewayGate / GatewayRoute / SettingsHost | `setGatewayEnabled`（PraxisApp） |
| `gateway_routing_policy` | String? | null（默认顺序 + 粘性） | `gatewayRoutingPolicyJson` → PraxisApp | `setGatewayRoutingPolicyJson`（PraxisApp） |
| `gateway_route_groups` | String?（JSON 数组） | null | `gatewayRouteGroupsJson` → PraxisApp:1121 `groups` | `setGatewayRouteGroupsJson`（PraxisApp） |
| `gateway_system` | String?（超时 / 换号上限 / debug / queueWaitSeconds / autoContinue / imageCompress） | null | `gatewaySystemSettingsJson` → PraxisApp:375-398 → 超时、预算、排队上限、图片压缩 | `setGatewaySystemSettingsJson`（PraxisApp；UI 在 feature:gateway 的 GatewaySystemScreen） |
| `gateway_proxy` | String?（代理池 JSON） | null | `gatewayProxyJson` → PraxisApp | `setGatewayProxyJson`（PraxisApp） |
| `gateway_sanitize` | String?（enabled + rules[]） | null（= 关） | `gatewaySanitizeJson` → PraxisApp:401-407 → `effectiveRules` → `SettingsBackedProvider.sanitizeRules` → `OutboundSanitizer` | `setGatewaySanitizeJson`（PraxisApp） |
| `gateway_request_retention_days` | String（整数文本） | null | `gatewayRequestRetentionDays` → PraxisApp 清理任务 / SettingsHost 显示 | `setGatewayRequestRetentionDays`（PraxisApp） |
| `gateway_builtin_models` | String?（providerId → [model]） | null → 空表 | `gatewayBuiltinModelsJson` → PraxisApp:745-761（内置家行/模型数）、:1122 与 :1368（`builtinModels` 注入候选链）、:1872（merge 基底） | `setGatewayBuiltinModelsJson`（PraxisApp:1874，三重条件见 §6.3） |
| `pending_queue_<sessionId>`（动态） | String（待发队列编码） | 缺键 = null | `pendingQueue(sid)` → `SettingsPendingQueueStore`（ChatViewModel） | `setPendingQueue` → `SettingsPendingQueueStore` |
| `terminal_snapshot_v1`（同文件、非本仓设置） | String | — | `DataStoreTerminalSnapshot`（app） | 同上 |

### 5.6 生图（4 个，全部 JSON 原文，编解码在 core:imagegen）

| 键 | 类型 | 默认 | 读 | 写 |
|---|---|---|---|---|
| `imagegen_target` | String? | null | `imageGenTargetJson` → AppModule（ImageGenTarget）/ PraxisApp | `setImageGenTargetJson`（AppModule / PraxisApp） |
| `imagegen_download` | String? | null | `imageDownloadSettingsJson` → AppModule / PraxisApp | `setImageDownloadSettingsJson` |
| `imagegen_defaults` | String? | null | `imageGenDefaultsJson` → AppModule / PraxisApp（generate_image 默认参数） | `setImageGenDefaultsJson` |
| `imagegen_output` | String? | null | `imageOutputSettingsJson` → AppModule / PraxisApp | `setImageOutputSettingsJson` |

### 5.7 Linux / 终端 / 键盘（8 个）

| 键 | 类型 | 默认 | 读 | 写 |
|---|---|---|---|---|
| `linux_env_distro` | String（枚举名） | 空（调用方回落） | `linuxEnvDistro()` → ProotEnvPort | `setLinuxEnvDistro`（ProotEnvPort） |
| `linux_env_mirror` ⚠ | String | 空 | `linuxEnvMirror()` **零调用点** | `setLinuxEnvMirror`(:846) **零调用点** |
| `linux_env_release` ⚠ | String | 空 | `linuxEnvRelease()` **零调用点** | `setLinuxEnvRelease`(:851) **零调用点** |
| `ftp_runtime` | String（JSON，不含密码） | 空 | `ftpRuntime()` → ProotEnvPort | `setFtpRuntime`（ProotEnvPort） |
| `terminal_font_size_sp` | Float | 13f（夹 9–20） | `terminalSettings` → PraxisApp / SettingsHost / TerminalViewModel | `setTerminalFontSize`（PraxisApp） |
| `terminal_scrollback_lines` | Int | 1000（夹 100–5000） | 同上 | `setTerminalScrollback`（PraxisApp） |
| `terminal_show_banner` | Boolean | true | 同上 | `setTerminalShowBanner`（PraxisApp） |
| `linux_shared_tmp` | Boolean | false | `linuxSharedTmp` → LinuxEnvViewModel / TerminalStorageRoute（决定 proot 的 -b host:/tmp 与体积统计口径） | `setLinuxSharedTmp`（同两处） |

### 5.8 会话 / 记忆 / 技能 / 工具 / 权限 / 行为 / 键盘（11 个）

| 键 | 类型 | 默认 | 读 | 写 |
|---|---|---|---|---|
| `chat_display_style` | String（cursor / bubble） | "cursor" | `chatDisplayStyle` → ChatTab / AppearanceRoute / AppearanceScreen | `setChatDisplayStyle`（AppearanceRoute） |
| `last_chat_session_id` | String | 空 | `lastChatSessionId` → PraxisApp / ChatSessionsPage | `setLastChatSessionId`（PraxisApp） |
| `auto_memory_enabled` | Boolean | true | `autoMemoryEnabled` → ChatTab / ChatScreen / ChatSettingsBar | `setAutoMemoryEnabled`（ChatTab） |
| `disabled_skills` | String（逗号） | 空集 | `disabledSkills` → ChatTab / ChatScreen / ChatSettingsBar | `setDisabledSkills`（ChatTab） |
| `disabled_tools` | String（逗号） | 空集 | `disabledTools` → PraxisApp / ChatTab / ChatScreen / ChatSettingsBar / PermissionSwitchRow / ToolPermissionState | `setDisabledTools`（PraxisApp） |
| `permission_level` ⚠ | String（枚举名） | 缺键 = null（= 从未表达意图） | **零调用点**（`permissionLevel` 流无人订阅） | `setPermissionLevel`（ProvisionGate:116） |
| `disable_stream_output` | Boolean | false | `disableStreamOutput` → PraxisApp / ChatTab / ChatScreen / ChatSettingsBar / ChatViewModel | `setDisableStreamOutput`（ChatTab） |
| `auto_read_replies` | Boolean | false | `autoReadReplies` → ChatTab / ChatScreen / ChatSettingsBar | `setAutoReadReplies`（ChatTab） |
| `active_memory_space` | String | 空（= 默认空间） | `activeMemorySpace` → PraxisApp / SettingsHost | `setActiveMemorySpace`（PraxisApp） |
| `memory_spaces`（读/增/切 ✅，删 ⚠） | String（␁ 分隔） | 空 | `memorySpaces` → PraxisApp / MemoryRoute / SettingsHost | `addMemorySpace`（PraxisApp）；`removeMemorySpace`(:1362) **零调用点** → 空间只能建不能删 |
| `virtual_keyboard_labels` | String（␁ × 14） | 内置 14 标签 | `virtualKeyboardLabels` → PraxisApp / TerminalStorageRoute | `setVirtualKeyboardLabels / resetVirtualKeyboardLabels`（TerminalStorageRoute） |

### 5.9 键账本的三个副产物

1. **死键 5 处**：use_miuix_engine、linux_env_mirror、linux_env_release、permission_level（读侧）、memory_spaces（删侧）。前三个连 setter 都没人调，属纯历史遗留。
2. **死常量 4 个**：KEY_MODEL_INDEX / KEY_TEMPERATURE / KEY_CONTEXT_LIMIT / KEY_COMPACT_THRESHOLD 各只在声明处出现一次（setter 用字符串名重建 key）——改它们不影响任何行为，是「看着像配置点其实不是」（G18）。
3. **两个值得记住的默认值**：gateway_enabled = true（只在档案 kind=Gateway 时生效，故默认开安全）、auto_memory_enabled = true（隐私相关，但用户关掉后会持久，已落盘）。

---

## 6. 重点回答 3：Room 数据库版本、迁移链、每张表谁用

### 6.1 版本与迁移链

- 数据库定义：`app/src/main/java/dev/praxis/data/db/PraxisDatabase.kt`，`@Database(version = 16, exportSchema = true)`（:49-50），10 个实体。
- 手写迁移共 14 段：`MIGRATION_2_3 / 3_4 / 4_5 / 5_6 / 6_7 / 7_8 / 8_9 / 9_10 / 10_11 / 11_12 / 12_13 / 13_14 / 14_15 / 15_16`（PraxisDatabase.kt:89–600），**全部**在 `AppModule.kt:36-97` 用 `addMigrations(...)` 注册。
- 链是否连续：**2 → 16 连续 ✅；1 → 2 缺失 ❌**。全仓没有 `MIGRATION_1_2`，而 KDoc 的版本历史明确写着「v2：新增 messages」。仍停在 DB v1 的安装升级到 v16 时，Room 会在打开库时抛 `IllegalStateException: A migration from 1 to 2 was required but not found`（表现是启动几秒后崩，与 KDoc 里记录的 12→13 事故同形态）→ **G20**。
- `exportSchema = true`，但 `app/schemas/.../` 只有 1/2/3/4/5/7/9/10/11.json（缺 6、8、12–16）。因为迁移已全改手写、不读 schema 文件，不阻塞编译；代价是「schema 变化无法在 diff 里 review」。
- `@Database` 上**没有 autoMigrations**（文档提到的 AutoMigration(11,13) 已被手写链取代，注释保留了解释）。

### 6.2 每张表 + 谁用

| 表 | 实体位置 | 建立/变更版本 | 读 | 写 | 备注 |
|---|---|---|---|---|---|
| sessions | app SessionEntity | v1（v4 pinned、v6 displayOrder、v11 contextUsed/contextLimit） | ChatSessionsPage / ChatRepository / 侧栏 | RoomChatPersistence / ChatRepository | messages 的父表（CASCADE） |
| messages | app MessageEntity | v2（v7 variant、v9 reasoning、v10 排序键改 createdAt） | ChatRepository / ChatViewModel | RoomChatPersistence | 摘要以 role='summary' 存在这里 |
| memories | app MemoryEntity | v3（v8 **故意 DROP 重建**加 spaceId） | RoomMemoryStore / MemoryPortImpl | 同上 | v8 是唯一一次有意丢数据 |
| character_cards | data/character/CharacterEntities.kt | v5 | CharacterRepository.cards → 设置页 / 聊天提示词 | 同上（ensureDefaultCard / create / save / importFromTavernJson） | 默认卡被 SQL 保护（仅 isDefault=0 可删） |
| prompt_tags | 同上 | v5 | CharacterRepository.tags / activePrompt | 同上（createOrReuseTag / saveTag；删标签同事务清引用） | 无外键，引用是冗余 id 列表 |
| gateway_account | data/gateway/GatewayAccountEntity.kt | v11→12 | GatewayAccountStore.all / routingViews / accountRows、GatewayImageAccountPool | applyImport / addAccount | 凭证列恒为密文（加密失败**中止**，与 apiKey 的降级策略相反） |
| gateway_provider | GatewayProviderEntity.kt | v11→12（v15 quirks 两列、v16 modelsFetchedAt） | GatewayAccountStore.providers / nativeModels、GatewayProvidersScreen | applyImport / addProvider / updateProviderModels | 内置家**不在**此表 |
| gateway_model ⚠ | GatewayModelEntity.kt | v12→13 | GatewayModelStore.all / enabled / visibleModelIds（app 包装类） | **无写入方**：GatewayModelStore.upsertAll 全仓零调用点 | 表 + DAO + Store 齐全但永远空 → 死表（G19） |
| gateway_request | GatewayUsageEntities.kt | v13→14 | GatewayUsageStore.summarySince / tokensByAccountSince / recentRequests / countSince / tokensSince → 报表页、token 预算、日志页 | GatewayUsageStore.record ← PraxisApp:804 / :1111 / :1357 的 recordUsage（GatewayProvider 回调） | 报表扫明细，isTest 排除 |
| gateway_daily ⚠ | 同上 | v13→14 | GatewayUsageDao.dailySince **零调用点** | GatewayUsageDao.upsertDaily **零调用点** | 表建成即空（KDoc 说等真有性能问题再上）→ 与 G19 同一类 |

### 6.3 gateway_builtin_models 这个 JSON 聚合键的读写方（重点）

- **类型**：DataStore String；内容形如 providerId → 模型数组（BuiltinModelsCodec：编码时 provider 键排序、模型去重排序）。
- **写**：只有 PraxisApp.kt:1869-1876 一处，且三重门槛：① 拉清单成功；② GatewayModelCatalogCache.decideWrite(success=true, fetched) == Write（**空清单不覆盖**，保住旧值）；③ providerId 属于 BuiltinProviders.ids()（内置家）。之后 BuiltinModelsCodec.merge(current, providerId, cleaned) 再整体写回。
  - 隐患：merge 的 current 来自 Compose 里 remember 的快照，**两次并发刷新时后写覆盖前写**（丢一次清单，下次刷新自愈）→ 记为「可接受」。
- **读**：三处，全在 PraxisApp：:745-761（内置家行与模型数）、:1122 与 :1368（展平成 NativeModel(provider, id) 交给候选链）。**没有这份数据，内置家账号选不出模型 → 一句话都发不出去**（BuiltinModelsCodec 的 KDoc 已写明这条后果）。
- **为什么必须是这个形态**（而不是往 gateway_provider 插行）：内置家是代码常量；插进表后「自定义提供商」页会把它们列出来、还能删 —— 语义错。于是「表管自定义家、这个键管内置家」是刻意分工。
- **规模风险**：单键聚合，任一家的清单变化都整串重写。21 家 × 数百模型后单值可达几十 KB，每次全量读写；到那时建议拆键（G21，当前可接受）。

---

## 7. 重点回答 4：core:plugin 的 9 个历史失败用例

> 结论先行：9 条**全部只在测试层**，不影响 APK 运行。其中 1 条命中的是**真实产品缺陷**（PluginLoadOrder 链式依赖不传播），其余是「测试夹具/断言与产品演进脱节」。修复难度：改测试 6 条约 20 分钟；修产品 1 条约 3~5 行 + 补 1 条断言；剩下 1 条待确认。
> 数量与 docs/GATEWAY-PORTING-STATUS.md 记录一致（21 例 6 失败 / 10 例 2 失败 / 9 例 1 失败）。
> 方法说明：本机不能跑 Gradle（纪律 + 环境内存），以下全部是**逐行推演**，每条都给出「可复现的最小改动」。

### 7.1 PluginRepositoryTest：6 条

**根因 A（4 条）：测试夹具的清单没有 main 指向的文件，而产品现在「有致命问题 → 强制 enabled=false」**

- 产品侧事实：PluginRepository.kt:186 `enabled = storage.isEnabled(packageId) && problems.none { it is Fatal }`；ManifestMapper 把「main 指向的文件不存在」判为 **Fatal**（field=main）。
- 测试侧事实：`storage.seed(validManifest)` 只写 manifest.hjson，没有 packages/main.js → 每条都带 Fatal。
- 命中用例与断言：
  1. 未显式记录启停状态时按启用处理 → 期望 enabled=true，实际 false；
  2. 扫描按启用优先再按标题排序 → 两个包都 false，退化成按标题排：期望 [z.pkg, a.pkg]，实际 [a.pkg, z.pkg]；
  3. 启用与停用 → setEnabled(true) 后仍被 Fatal 压成 false；
  4. id 含路径分隔符时拒绝安装 → install() 先 inspect()，而 seedSource("slip") 没给 main 文件，抛的是「清单不可用（main）」而不是「含路径分隔符」，断言 contains("分隔符") 失败。
- **判定：测试过时**（产品行为正确：清单坏了就不该显示为「已启用」）。
- 修法：这 4 条 seed 时补 `files = mapOf("packages/main.js" to "// js")`；第 4 条同样补上，让断言真的走到分隔符检查。难度 ★☆☆。

**根因 B（2 条）：测试注入的「安装源」没被假存储读到**

- 事实链：测试用 `externalSources["mem://pkg1"]` + `PluginRepository.externalArchiveFactory` 让 inspect() 能读到包内容；但 install() 随后调 `storage.installFrom(sourcePath, id)`，而 `InMemoryPluginStorage.installFrom` 查的是**它自己的私有 seeds 表**（只有 `storage.seedSource(key, files)` 才会写）。测试从未调用 `storage.seedSource` → 抛「测试未预置 sourcePath=mem://pkg1」。
- 命中用例：安装成功并写入来源、市场安装时用 expectedId 覆盖包自报 id。
- **判定：测试 wiring 错**（install() 的产品逻辑正确）。
- 修法：在测试的 `seedSource()` 助手里加一行 `storage.seedSource(key, files)`（约 3 行）。难度 ★☆☆。
- 顺带价值：这条说明「外部归档工厂」与「假存储的安装源」是**两套注入点**，新测试极易只喂一半 —— 建议给 `InMemoryPluginStorage` 加一个「同一份 map 同时喂两处」的便捷方法，从根上消除这类错。

### 7.2 PluginLoadOrderTest：2 条

**第 1 条：链式失效会传递（A 依赖 B，B 依赖坏掉的 C）— 产品缺陷**

- 事实：`firstDependencyFailure`（PluginLoadOrder.kt:149-171）只在「目标**自身**有 Fatal」时判失败（关键条件是 :161 的 `requirement.id !in eligibleIds && target.problems.any { it is ManifestProblem.Fatal }`）。B 因 C 被 skipped 后自身 problems 为空，于是第二轮判定里 A 通过 → ordered=[A]，而断言要求 ordered 为空且 skipped["a"] 非空 → 双失败。
- 真机影响：A 仍会被装载；A 在 require/调用依赖工具时拿到 undefined —— 正是本类 KDoc 描述的「错误与真正原因隔三层」。
- 修法：判定改成「依赖目标一旦不在 eligible 集合里就失败」（把 :161 的条件简化为 `requirement.id !in eligibleIds`，并按「未安装 / 已禁用 / 自身坏 / 被连锁跳过」分文案）。改动 2~5 行，现有 4 条通过用例不受影响。难度 ★★☆。

**第 2 条：版本不满足时跳过 — 断言过时**

- 事实：该用例里 base(1.0.0) 自身无问题，只有 consumer 被 min_version=2.0.0 拦下 → ordered=[base]，断言却写 ordered.isEmpty()。
- 判定：产品行为正确（无关的包当然要装），是断言写错。
- 修法：断言改为 `ordered.map { it.id } == listOf("base")` 且 skipped["consumer"] 含 "2.0.0"。难度 ★☆☆。

### 7.3 ExtensionSnapshotTest（位于 MarketAndSnapshotTest.kt）：1 条 — **未确认**

该类共 9 例。我对着 `buildExtensionSnapshot`（ExtensionSnapshot.kt:94-176）、`ExtensionEntry.isUsable`、`InstalledPlugin.toExtensionEntry`、`PluginManifest.providesUi`、`McpServerConfig.usability` 逐条推演，**9 条断言在现行代码下都应通过**：

| 用例 | 推演结论 |
|---|---|
| 四类都出现在同一份快照里 | 1 toolpkg + 1 mcp + 1 skill = 3；排序 isUsable→kind(ToolPackage=0, Mcp=2, Skill=3) ✅ |
| 坏包排在可用包之后 | bad 的 failure="main：缺" → isUsable=false → 沉底 ✅ |
| 提供 UI 的包额外出现在沙盒分组 | resources 含 ui.main → providesUi()=true；description 走 fallback 含「同一个包」；两 key 不同 ✅ |
| 没有 UI 资源的包不进沙盒分组 | ✅ |
| 技能的启停状态来自集合 | Skill.toExtensionEntry 的 id=name ✅ |
| MCP 缺少 endpoint 时带失败原因 | usability="缺少 endpoint" ✅ |
| stdio 传输被标为不可用并说明原因 | usability 含 "stdio" ✅ |
| 已安装版本查询 | version="1.0.0"，未安装返回 null ✅ |
| 加载顺序问题被原样带出 | Ordering 同一实例比较 ✅ |

因此这 1 条有三种可能：① 我漏了某一处空值分支（最可能是 LocalizedText.resolveZh() 或 providesUi()）；② 失败来自该类内别的隐秘依赖；③ **CI 记录已过时**（计数自 58cbd54 起一直是 9，存在「历史照抄」的可能）。
**建议动作**：翻最近一次 CI 的 `core/plugin/build/test-results/testDebugUnitTest/TEST-*ExtensionSnapshotTest*.xml` 里 `<failure>` 节点的 message/expected/actual，或本地 `./gradlew :core:plugin:test --tests '*ExtensionSnapshotTest' --info` 单跑一次，把用例名贴回 STATE.md。**我不猜。**

### 7.4 九条总览

| # | 类 | 用例 | 性质 | 难度 | 最小动作 |
|---|---|---|---|---|---|
| 1 | PluginRepositoryTest | 未显式记录启停状态时按启用处理 | 测试过时 | ★☆☆ | seed 补 packaged main.js |
| 2 | 同上 | 扫描按启用优先再按标题排序 | 测试过时 | ★☆☆ | 同上（两个包都补） |
| 3 | 同上 | 启用与停用 | 测试过时 | ★☆☆ | 同上 |
| 4 | 同上 | id 含路径分隔符时拒绝安装 | 测试过时 | ★☆☆ | 同上 |
| 5 | 同上 | 安装成功并写入来源 | 夹具 wiring | ★☆☆ | seedSource 同步喂 storage.seedSource |
| 6 | 同上 | 市场安装时用 expectedId 覆盖 | 夹具 wiring | ★☆☆ | 同上 |
| 7 | PluginLoadOrderTest | 链式失效会传递 | **产品 bug** | ★★☆ | firstDependencyFailure 增「目标不在 eligible 即失败」 |
| 8 | 同上 | 版本不满足时跳过 | 断言过时 | ★☆☆ | 断言改成「只跳过 consumer」 |
| 9 | ExtensionSnapshotTest | 未知（9 条推演皆过） | 待确认 | ? | 取 CI XML 的 failure 节点再定 |

---

## 8. 缺口 / 疑点清单

> 每条：**证据 → 影响 → 建议动作**。严重度：🔴 会让功能不可用/丢数据；🟠 会让用户看到错的事实或半生效；🟡 设计债/可接受。

### 🔴 G13 apiKey 与多配置的迁移缺口（最严重）

- 证据：`ensureProfiles`(SettingsRepository.kt:394) 只把 `readModelSettings(prefs, null)` 搬进新 profile（`writeModelSettings`），**没有搬 `model_api_key`**；`profileScopedApiKeyName`(:955) 与 `apiKey()`(:968) 只读 `p:<activeId>:model_api_key`；`migrateApiKeyIfNeeded`(:988) 只处理旧扁平键。测试 `ModelProfilesTest` 无 apiKey 用例。
- 影响：老用户首次升级（ensureProfiles 播种后）`apiKey()` 返回空串 → 标题生成/对话/生图三条路全部按「未配置」处理（或直接 401）。用户视角是「我的 key 没了」，且没有任何提示。
- 建议动作：在 `ensureProfiles` 建第一个 profile 的同一事务里，把旧 `model_api_key` 复制为 `p:<newId>:model_api_key`（值原样搬，密文/明文都可）；补 2 条测试（老用户有明文 key / 有密文 key）。**这是本轮审计建议的唯一「必须立刻做」项。**

### 🔴 G8 角色卡工具白名单从未生效

- 证据：`CharacterToolFilter.allowedTools / isAllowed`（core:model）全仓零调用点（只被自身文件与 KDoc 提到）；`CharacterCard.toolAccessConfig` 有完整存储（Room 列 + 编解码 + 归一化）。
- 影响：用户给角色卡配的「只允许 read_file」完全不影响发给模型的工具集 —— 典型的「能配但没用」（GOAL 铁律禁止）。
- 建议动作：在 `ToolRegistry.descriptorsFor(snapshot)` 的调用链上接一层 `CharacterToolFilter`（需要把当前 active card 的 config 传进去）；或先按「假入口」原则把该 UI 隐藏，并写进 STATE 的待办。

### 🔴 G2 permission_level 只写不读

- 证据：写方 ProvisionGate:116（从 `praxis_provision` SharedPreferences 转存）；读方 `settingsRepository.permissionLevel` **零订阅**。当前实际层级由 `CapabilitySnapshot.deriveFrom` 现算，与用户选择无关。
- 影响：引导页/权限页里「用户希望用到哪一级」的语义没落地；「有 Root 就把所有 Root 工具发给模型」的越权风险仍在。
- 建议动作：把 `permissionLevel` 作为**上限**接进工具描述发放（`min(deriveFrom, 用户选择)`），或在 UI 上明确写「本机实际级别」并删掉选择项。

### 🟠 G5 插件依赖链式失效不传播（产品 bug，见 §7.2）

- 证据：PluginLoadOrder.kt:161 的复合条件。影响：装载顺序里出现「依赖了一个被跳过的包」的包，装载后脚本侧 undefined。建议：按 §7.2 修法改判定并补一条「目标被连锁跳过」用例。

### 🟠 G15 标题生成链不带网关模型映射

- 证据：PraxisApp.kt:1341-1353 的 `GatewayProvider(modelRules = { GatewayModelRules() })`，而对话链 :1107 传的是 `{ gatewayModelRules }`；同一文件 :1105 的注释还写着「modelRules 的持久化还没接」（已过时）。
- 影响：用户配了「对外名 → 上游名」映射后，对话走映射、自动标题不走 —— 标题请求可能 400 或落到错误的模型；排查时注释还在误导。
- 建议动作：两处统一传 `{ gatewayModelRules }`，并删掉过时注释。

### 🟠 G3 出站脱敏只覆盖 message.text

- 证据：SettingsBackedProvider:64-72 只 map `message.text`。
- 影响：工具结果（`toolResult.content`）、工具参数（`toolCalls.argumentsJson`）、图片 base64 原样出站。用户以为「开了脱敏就安全」，而真实泄漏路径恰恰常在工具输出里。
- 建议动作：`OutboundSanitizer` 增加「对 toolResult/args 也应用规则」的重载，并在 `SettingsBackedProvider` 里对这三处一并处理（图片不动，但要在设置页说明）。

### 🟠 G4 压缩的可配置项没有入口

- 证据：`CompactionSettings`（messageCountThreshold / globalRules / useEnglish）与 `SummarySectionOverride` 只有定义 + `ContextCompactor` 内部消费；ChatViewModel:402 是 `private val compactionSettings: CompactionSettings = CompactionSettings()`（硬编码）；SettingsRepository 无对应键。
- 影响：Operit 的「摘要小节覆盖 / 全局规则 / 条数阈值」在 Praxis 里是死的；用户无法要求「务必保留所有文件路径」这类摘要约束。
- 建议动作：加一个 `compaction_settings` JSON 键 + 设置页一小节；或明确不做并把 `SummarySectionOverride` 删除以免误导。

### 🟠 G6 图片压缩的「小图不碰」阈值不生效

- 证据：`ChatImageCompressionRules.decide` 零调用点；`ChatImagePreparer` 只读 `policy.enabled/maxEdge/quality`；`thresholdKb` 从设置页一路存到 `GatewaySystemSettings.imageCompress` 后被忽略。
- 影响：用户把阈值设成 512KB 期望「小图不重压」，实际仍会被压缩（掉一层画质）。
- 建议动作：在 `ChatImagePreparer` 解码前用 `resolver` 的字节数判阈值（或直接调 `decide` 拿到 Keep/Compress 再执行），补一条「小图 Keep」的端到端用例。

### 🟠 G22 运行时注册的入口 kind 被抹平

- 证据：引导脚本把 `registerToolboxUiModule` 与 `registerDesktopWidget` 都 push 进 `reg.uiRoutes`（带 `toolbox:true` / `runtime:'widget'`）；`PluginRegistrationCodec` 解析 uiRoutes 时**恒**设 `kind = UiRoute`，忽略 toolbox 标记。
- 影响：工具箱卡片与桌面组件在「兼容插件入口」里都被当成普通页面；分类/图标/挂载面语义丢失。
- 建议动作：codec 里按 `toolbox` 字段与 `runtime=="widget"` 映射成 `Kind.Toolbox / Kind.DesktopWidget`，补 2 条断言（引导脚本与 codec 是同一契约的两个半边）。

### 🟡 G7 用户模板漏占位符时高层内容静默丢失

- 证据：`SystemPromptTemplates.fill` 只替换模板中出现的占位符；`SystemPromptBuilder.buildLayers` 把用户规则写在 `USER_RULES` 占位符里。用户自定义模板若忘写该占位符，其「最高优先级规则」直接消失，无任何提示。
- 影响：与「系统提示词权重最高」的产品承诺冲突，且失败是静默的。
- 建议动作：保存模板时校验 `ALL` 里至少包含 `USER_RULES`，或在 `buildLayers` 里检测「模板不含 USER_RULES 且 custom 非空」时把规则前置追加。

### 🟡 G1 三个纯死键 + 一个缺失的删除入口

- 证据：见 §5.2 / §5.7 / §5.8（use_miuix_engine、linux_env_mirror、linux_env_release 的读写两侧零调用点；`removeMemorySpace` 零调用点）。
- 影响：前三个是死代码（不构成用户可见问题，但违反「不留假控件」的记账纪律）；`memory_spaces` 只能建不能删 —— 用户建错一个空间名后无法清掉。
- 建议动作：删键与 setter（或补消费方）；记忆空间补「删除」入口（记得同步处理 activeMemorySpace 指向被删空间时的回落）。

### 🟡 G10 / G11 网关自定义家的两处数据保真问题

- G10：`GatewayAccountStore.addProvider` 重建实体时只带 id/name/baseUrl/modelsJson/mappingsJson/createdAt，**丢掉 `extraHeadersJson / urlSuffix / modelsFetchedAt`** → 用户改一次地址，Cline/GLM 类必需头就没了（表现为 403/beta 特性失效）。
- G11：`modelsJson` 有两种写形态 —— `GatewayCustomProvider.toEntity` 用换行分隔，`updateProviderModels` 用 JSON 数组；读侧 `decodeLines`（Mapper）与 `nativeModels`（按换行 split）各自解析 → 从 `updateProviderModels` 写入后，`nativeModels()` 会把整行 JSON 当一个模型名。
- 建议动作：统一成一种编码（建议换行，与实体注释一致），并给 addProvider 加 `previous?.extraHeadersJson` 等三项的沿用；各补 1 条测试。

### 🟡 G12 looksEncrypted 的误判窗口

- 证据：判据是「含一个冒号且两段都能 Base64 解码」。恰好形如 `abc==:def` 的明文 key 会被判成密文 → decrypt 失败 → apiKey() 返回空。
- 影响：低概率但后果是「key 凭空消失」，且 `KeystoreCipherTest` 只锁了负例。建议：加密时加一个固定前缀（如 `v1:`）作为强判据，保留旧判据做兼容。

### 🟡 G14 SkillRegistry 吞扫描异常

- 证据：`SkillRegistry.reload` 里 `runCatching { source.listSkillFiles() }.getOrDefault(emptyList())`。
- 影响：目录读失败（权限/SDCARD 卸载）表现为「一个技能都没有」，无错误提示。建议：把异常记成 `SkillSkip("(source)", reason)` 或返回 `Result`，让界面能说「扫描失败」。

### 🟡 G16 SseParser 的丢字节信号没人读

- 证据：`didDropOversize()` 只有测试在用；provider 未检查。影响：异常网关注入超长垃圾时，用户只看到「回复很短」，日志里也没有信号。建议：provider 在 `finish()` 前后检查一次并写一条 W 级日志（不必中断请求）。

### 🟡 G17 摘要的跨进程识别依赖文本头

- 证据：`CompactionApplier.SUMMARY_HEADER = "# 早前对话摘要"`，`isSummaryMessage` 用 startsWith。
- 影响：改文案就等价于「所有历史摘要失效」（会被当普通 assistant 消息发送）。当前两处共用常量，暂时安全。建议：给消息表加一个 role='summary' 之外的稳定标记（已有 role 就是 'summary' —— 更稳的做法是恢复用 role 判定而不是文本头）。

### 🟡 G9 酒馆导出无往返测试

- 证据：`TavernCardExporter.toJson` 有实现，`CharacterRepository.exportCardAsTavernJson` 有调用，但**没有任何测试**；导入侧有 `TavernCardPasteTest`（5 条）。
- 影响：导出把 description/personality 等写空、只靠 `extensions.operit` 回读 —— 一旦结构漂移，Perplexity「导出→导入」就会静默丢字段。建议：补 1 条 round-trip（卡 → JSON → 卡，断言关键字段相等）。

### 🟡 G18 四个死常量

- 证据：KEY_MODEL_INDEX / KEY_TEMPERATURE / KEY_CONTEXT_LIMIT / KEY_COMPACT_THRESHOLD 各只出现一次（声明）。影响：改它们没有效果，后人会以为改了默认值。建议：删除或让 setter 复用它们。

### 🟡 G19 两张「建成即空」的表

- 证据：`gateway_model` 的 `GatewayModelStore.upsertAll` 零调用点；`gateway_daily` 的 `upsertDaily/dailySince` 零调用点。
- 影响：不是 bug（有注释说明），但会让「报表/模型管理」看起来做完了其实没有数据源。建议：在 STATE.md 的「未完成」里显式登记，避免下一轮误判为已完成。

### 🟡 G20 迁移链缺 1→2

- 证据与影响见 §6.1。建议：补一条 `MIGRATION_1_2`（建 messages 表 + 索引，DDL 可从 2.json 抄），并注册进 `addMigrations`；或在 @Database 上把 minVersion 说明写清「不再支持 v1」。

### 🟡 G21 gateway_builtin_models 单键聚合

- 证据与影响见 §6.3。建议：等内置家数量上到两位数后按 provider 拆键。

---

## 9. 附录：核对方法与未确认清单

### 9.1 我实际做了什么

1. 递归枚举四个模块 `src/**`，逐文件全文读（含每个测试文件），本地统计 47 / 23 / 27 / 36。
2. 用「键名字面量 + API 名」双路 grep 全仓（653 个 `.kt`）建立**消费方账本**（§5）：键字面量几乎只在 SettingsRepository 出现（说明没有旁路读写），属性/方法名用于找消费点。
3. 对 §4 的每条链路都读了**平台侧装配点**（PraxisApp / AppModule / SettingsBackedProvider / PraxisDatabase）以确认「真的有消费者」，而不是只看核心模块自洽。
4. 9 条失败用例用「读测试 → 读实现 → 手推断言」的方式定位，并对每条给出最小修复动作（§7）。

### 9.2 未确认（如实登记，不猜）

| 事项 | 状态 | 下一步 |
|---|---|---|
| `ExtensionSnapshotTest` 具体哪条失败 | **未确认**（9 条推演全过） | 取 CI 的 TEST-*.xml failure 节点，或单跑该类 |
| `AnthropicStreamAssembler.errorMessage()` 是否被 provider 使用 | 未确认（grep 未见），若未用则上游 error 事件会以 Done 收尾 | 读 AnthropicProvider 的 finish 处理 |
| `gateway_proxy`、`gateway_route_groups` 等 JSON 键是否真的改变了请求行为 | 只确认「读进 PraxisApp 并作为参数传给网关层」，未逐个追到使用点 | 下一轮读 core:gateway |
| `feature:chat` 里 `compactionSettings` 是否另有赋值路径 | 只见硬编码初始化 | 全文读 ChatViewModel（本次只 grep） |
| 真机行为（Keystore、WebView 插件、SSE 断流） | 均未验证（无设备，且纪律禁止） | 需要真机/模拟器轮次 |

### 9.3 给下一轮的三条建议

1. **先修 G13**（apiKey 迁移）—— 这是唯一会让老用户「配好的东西突然不工作」的缺陷，改动小、收益大。
2. **再清 core:plugin 的 8 条**（§7.1/§7.2 里除第 9 条外全部）—— 让 CI 回到「零容忍」，同时把 G5 这个真实 bug 修掉；第 9 条用 XML 定位后一并处理。
3. **然后处理 5 处死键 + G8/G4/G6 三个「能配不生效」**—— 它们直接违反 GOAL 铁律 1（每个设置项必须有消费方），而且是用户最容易感知为「假控件」的地方。
