# 05 · feature / ui / core（杂项）通读与审计

- 审计范围：`feature:chat` · `feature:settings` · `feature:terminal` · `ui:design` · `ui:widgets` · `ui:navigation` · `ui:theme` · `ui:icons` · `core:linux` · `core:tool` · `core:goal` · `core:memory` · `core:workflow` · `core:terminal` · `ui:provision`
- 执行者：agent-6（只读 + 写文档）
- 时间：2026-11-16
- 方法说明（如实交代）：
  1. **核心文件逐行精读**：`ui:navigation` 全部 12 个、`ui:theme` 全部、`ui:icons`、`SettingsEntry/SettingsHomeScreen/SettingsHost`、`ChatState.kt`、`ChatViewModel.kt` 的发送链路（send → startStream → runToolCalls → 续写 → 失败态）、各 core 模块的关键实现（ToolExecutor / WorkflowEngine / MemorySearchConfig / TerminalBuffer）、`ui:widgets` 中自研控件的 KDoc 与签名、`ui:design` 中 glass/effect 的关键点。
  2. **其余文件做「声明级全量通读」**：逐文件读包头 KDoc + 全部顶层/成员声明 + 行号，用于填表与找疑点；测试文件按「测试名清单」通读。
  3. 未做的事（受纪律限制）：**没有跑 gradle / 单测 / 真机**，所以所有「运行期后果」的结论都标注为**未验证**，只给静态证据。
  4. `ui:provision` 按要求只做清单 + 入口，不逐行读（搬运代码，不改）。

---

## 1. 范围与文件清单（数量核对）

| 模块 | 目录里实际文件数 | 我读到的文件数 | 其中 main | 其中 test |
|---|---|---|---|---|
| feature/chat/src | 55 | 55 | 36（35 kt + 1 manifest） | 19 kt |
| feature/settings/src | 57 | 57 | 53（52 kt + 1 manifest） | 4 kt |
| feature/terminal/src | 13 | 13 | 8（7 kt + 1 manifest） | 5 kt |
| ui/design/src | 44 | 44 | 40（36 kt + manifest + 3 资源 xml） | 4 kt |
| ui/widgets/src | 34 | 34 | 32（30 kt + manifest + 1 drawable） | 2 kt |
| ui/navigation/src | 12 | 12 | 12（11 kt + 1 manifest） | 0 |
| ui/theme/src | 8 | 8 | 7（6 kt + 1 manifest） | 1 kt |
| ui/icons/src | 1 | 1 | 1 kt | 0 |
| core/linux/src | 20 | 20 | 11 kt | 9 kt |
| core/tool/src | 16 | 16 | 11 kt | 5 kt |
| core/goal/src | 2 | 2 | 1 kt | 1 kt |
| core/memory/src | 6 | 6 | 4 kt | 2 kt |
| core/workflow/src | 4 | 4 | 3 kt | 1 kt |
| core/terminal/src | 5 | 5 | 3 kt | 2 kt |
| **小计（源码模块）** | **277** | **277** | 232 | 55 |
| ui/provision/src（清单级） | 130 | 130（只列清单 + 入口，未逐行读） | 130（53 java + 2 aidl + 1 manifest + 71 资源 + 3 assets） | 0 |
| **合计** | **407** | **407** | — | — |

- 构成：Kotlin 266 个、Java 53 个（全在 ui:provision）、AIDL 2 个，其余 86 个是 manifest / 资源 XML / assets（`marked.min.js`、`github_style.css`、`glsl` 等）。
- 计数的坑（供后续轮次参考）：`core/tool`、`core/goal`、`core/memory`、`core/workflow`、`core/terminal` 这 5 个目录**各含 `build/` 产物**（分别 257 / 186 / 304 / 151 / 136 个文件，共 1034 个），`readdir` 递归会把它们算进去，导致「274 / 189 / 311 / 156 / 142 个文件」这种假数字。真实源码只有 16 / 2 / 6 / 4 / 5。
- 另有 5 个模块级 `build.gradle.kts`（core:tool / goal / memory / workflow / terminal）与各模块 `build.gradle.kts`：我读了它们 **de 依赖声明行**（用于判断模块边界），未逐行读构建脚本正文。

---

## 2. 这些模块整体是什么

一句话：**`ui:*` 是「长得像什么样 + 怎么走」，`feature:*` 是「每个页面怎么把状态接到控件」，`core:*（本批）是「真正干活的纯逻辑」**。

- `ui:theme / ui:icons / ui:design`：主题（miuix 单一色彩源 + M3 语义色映射）、图标、以及**一整套自研的视觉/手势基础设施**（液态玻璃、弹簧跟随、自动跟随滚动、逐字显现引擎、IME 抬升、Scaffold 三段式）。
- `ui:navigation`：**35 个具名 `PraxisRoute` + 一次 entry 注册 + 标题穷举 when + Navigator 栈操作**。这是「二级页篡页」这类历史缺陷的根治层。
- `ui:widgets`：所有页面共用的控件（开关 = LiquidToggle、对话框进场动画、markdown 渲染、侧栏、种子色卡、拖拽列表…）。
- `feature:chat`：一个 5025 行的 `ChatViewModel` 承载完整对话状态机（流式、工具、续写、压缩、变体、队列、编辑重发），加上 `ChatScreen`（2523 行）/`ChatSettingsBar`（1336 行）/`MessageEditor`（1079 行）三个大 UI 文件。
- `feature:settings`：设置首页 + 18 个入口枚举 + **122 个参数的巨型分发器** `SettingsHost`（1107 行）+ 各子页 Route/Screen 成对出现。
- `feature:terminal`：真 PTY 终端（`PtySessionPort`）+ 虚拟键盘（两行七列、Ctrl/Alt 修饰、历史翻页）+ 渲染层（`TerminalScreen` 1439 行）。
- `core:linux`：proot 容器的一切——发行版清单、镜像源、`ProotCommand`（912 行，负责所有 shell 脚本生成）、SSH/FRP、磁盘占用、会话工具（6 个 AI 可调工具）。
- `core:tool`：三档权限模型 + 工具注册表 + 串行执行器 + 失败归因（Argument/Denied/Execution/Present）+ 自定义工具（白名单原语）。
- `core:goal / memory / workflow / terminal`：会话目标（Phase + 步骤 + 提示词渲染）、记忆检索（RRF + 关键词/标签/图边 + 覆盖率重排）、工作流引擎（Trigger→Tool/Condition/Transform→Output，带步数与时长上限）、ANSI 终端缓冲（256 色、宽字符、行版本号）。

---

## 3. 逐文件一表

> 说明：路径相对模块 `src/` 下的 `main/java/dev/praxis/...` 或 `main/kotlin/...` 缩写；「疑点」列为「—」表示未发现。所有行号均为该文件内的 1 基行号。

### 3.1 feature:chat（55）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| main/AndroidManifest.xml | 空壳 manifest，只放 `tools:overrideLibrary` 放行 miuix 的 minSdk | — | 与 :feature:terminal 的 `<manifest/>` 写法不一致（见 G12） |
| chat/ChatViewModel.kt (5025) | 对话的唯一状态机：发送、流式、工具、续写、压缩、变体、队列、编辑重发、持久化节流 | `ChatViewModel(provider, model, …20+ 端口)`；`state: StateFlow<ChatState>`；`send()`、`sendOrEnqueue()`、`cancel()`、`retry()`、`continueGeneration()`、`switchVariant()`、`onMessageAction()` | 工具轮无上限（G2）；`startStream` 自取消再重启（G3）；`askHandler = null` 使 ASK 降级为拒绝（ChatViewModel.kt:2749，G4） |
| chat/ChatState.kt (1014) | 全部 UI 状态与派生量：气泡、阶段、上下文用量、错误、队列、编辑态 | `ChatBubble`、`BubbleRole`、`ChatStage`、`ChatState`、`MessageAction`（11 项）、`ChatCopyContent`、`cleanMessageContentForCopy` | `ChatEvent`（Delta/Finished/Failed）定义了但全仓无引用（死类型，G10） |
| chat/ChatScreen.kt (2523) | 对话主界面：列表 + 定位器 + 输入条 + 附件条 + 变体切换 + 发送/入队/停止按钮 | `ChatScreen(state, onInputChange, onSend, …60+ 可空回调)`、`SendActionMode`、`sendActionMode()`、`buildAnnotatedScreen` 无关 | 60+ 可空回调全靠调用点记得传；无一人用 `ChatState.modelPickerRequested/exportRequested/newSessionRequested`（见 G11） |
| chat/ChatSettingsBar.kt (1336) | 对话页顶部的设置抽屉：模型、思考档位、工具、记忆、技能、插件、TTS 开关 | `ChatSettingsBar(...)`、`ModelPill`、`ModelGroup` | `hasAnySection` 为假时整条消失（无提示，ChatSettingsBar.kt:189-193） |
| chat/ChatPersistence.kt (330) | 持久化端口（feature 侧接口，实现在 :app/core:data） | `appendMessage`、`loadMessages`、`updateMessageContent`、`updateMessageReasoning`、`insertMessageAt`、`appendVariant`、`attachToVariantGroup`、`PersistedMessage` | 一半方法给了默认空实现（`clearMessages`/`deleteMessage`/`updateContextUsage`/`appendVariant`…），漏实现时**静默无效**（G9） |
| chat/ChatMarkupRegex.kt (239) | 所有内部标记（think/search/tool/attachment/emotion/meta）的正则与剥离函数 | `statusTag`、`thinkTag`、`searchTag`、`toolTag`、`metaTag`、`removeImageLinks`、`removeOpenAiResponsesProtocolMeta` | 只覆盖已知供应商；新供应商 meta 会漏进正文（非阻塞） |
| chat/ChatCopyText.kt (110) | markdown → 纯文本（复制用） | `ChatCopyText.markdownToPlainText` | 依赖 `MarkdownParser` 的块模型，代码块内列表符会被当文本保留（可接受） |
| chat/ChatContextUi.kt (177) | 上下文用量条（带压缩阈值刻度） | `ContextUsageBar` | — |
| chat/ChatListScreen.kt (16) | `SessionItem` 数据类（列表模型） | `SessionItem` | 文件名与内容不符：真正的页面在同目录 ChatListUi.kt |
| chat/ChatListUi.kt (191) | 会话列表页 | `ChatListScreen(sessions, …)` | — |
| chat/ChatTitlePort.kt (33) | 标题生成端口 | `ChatTitleGenerator.generate` | — |
| chat/ChatWindowPolicy.kt (112) | 渲染预算常量：历史窗口 200 条、markdown 缓存 16 条 / 短文本 512 字符 | `ChatRenderBudget.HISTORY_WINDOW`、`MarkdownParseCachePolicy` | 与 `MarkdownText.kt:516`、`MarkdownParseCache.kt:40` 各写一份同值常量（漂移风险，G13） |
| chat/SessionTitle.kt (150) | 会话标题的兜底与清洗（防注入系统提示词） | `SessionTitle.sanitize/userPrompt/stripThinking/fallback`、`MAX_LENGTH = 40` | — |
| chat/ThrottledContentWriter.kt (97) | 流式落库节流（默认窗口内合并写） | `submit/flush/forget/drainPending` | 进程被杀时 `drainPending` 之外的已提交内容只活在内存（见 G8 关联） |
| chat/PendingQueue.kt (229) | 待发队列纯逻辑（入队/上移/置顶/重排/恢复） | `PendingQueue`、`QueuedMessage`、`PendingQueueOps` | 上限靠「返回原实例」表达，UI 需自己判断（PendingQueue.kt:111） |
| chat/PendingQueueCodec.kt (137) | 队列的文本存档格式（v1，TAB 分隔 + base64） | `encode/decode` | 损坏存档静默返回空队列（有意，但用户丢了排队内容无提示） |
| chat/PendingQueueStore.kt (26) | 队列存档端口 | `save/load` | 默认空实现 |
| chat/AttachmentPanel.kt (168) | 附件九宫格面板（图/文件/位置/通知/记忆/包/截屏/拍照） | `AttachmentPanel(cells…, onPickImage, …)` | 只有 8 个格子却占满一屏网格，无折叠 |
| chat/ImageGalleryPort.kt (29) | 保存图片到相册端口 | `saveImage` | — |
| chat/MemoryPort.kt (70) | 记忆端口（对话侧） | `recall/remember/recent/setActiveSpace` | `recent` 默认返回空表（MemoryPort.kt:48） |
| chat/SkillView.kt (27) | 技能视图模型 | `SkillView` | — |
| chat/command/BuiltinCommands.kt (60) | 5 个内置斜杠命令 | `COMPACT/MODEL/NEW/CLEAR/EXPORT` | 都 `submitsToModel = false`，无一能带参数执行（G14） |
| chat/command/SlashCommand.kt (131) | 斜杠命令解析与查表 | `ParsedCommand`、`SlashCommand.parse/find`、`CommandDefinition` | 命令名正则要求小写字母开头，中文别名不可能（有意） |
| chat/command/SlashCommandPanel.kt (146) | 命令补全面板 | `SlashCommandPanel` | — |
| chat/editor/MessageEditor.kt (1079) | XML 标签化消息编辑器（可视化 / 原文两模式） | `MessageEditor`、`parseMessageContentForEditor`、`recomposeMessageFromParts`、`ParsedMessagePart` | 往返解析靠正则 `<tag attr>…</tag>`，嵌套同名标签会切错（MessageEditor.kt:165，已知限制未在 KDoc 声明） |
| chat/menu/MessageContextMenu.kt (564) | 气泡长按菜单 + 复制预览 sheet + 删除确认 + 信息弹窗 | `MessageContextMenu`、`actionsForBubble`、`MessageCopyPreviewSheet`、`MessageInfoDialog`、`MessageDeleteConfirmDialog` | — |
| chat/navigator/ChatMessageLocator.kt (277) | 消息定位：预览摘要、回合切分、搜索归一化、居中解析 | `buildTurnEntries`、`TurnEntry`、`normalizeMessageSearchText`、`resolveCenteredMessageIndex` | — |
| chat/navigator/ChatScrollNavigator.kt (605) | 右侧滚动定位条 + 消息跳转面板（含搜索、防抖 180ms） | `ChatScrollNavigator`、`ChatMessageLocatorDialog` | 「当前条」靠 layoutInfo 反算，快速滑动时会滞后（视觉抖动，未验证） |
| chat/queue/PendingQueuePanel.kt (619) | 待发队列面板（拖动重排、逐条操作） | `PendingQueuePanel`、`rememberDragReorderState`、`DragReorderState` | 拖动重排自己实现指针逻辑，与 ui:widgets 的 `MiuixDraggableList` 是两套（重复实现，G15） |
| chat/widget/MessageBubble.kt (736) | 气泡渲染（用户/助手/工具/错误/摘要五种） | `ChatMessageItem`、`MessageBubble`、`BubbleKind` | `BubbleKind` 与 `BubbleRole` 两套枚举语义重叠（需人肉映射） |
| chat/widget/ToolCallCard.kt (345) | 工具调用卡（运行中/成功/失败/参数流式） | `ToolCallCard` | 明确不用 squircle（性能，见 PraxisRadius KDoc 引用） |
| chat/widget/SummaryDivider.kt (303) | 压缩摘要分割线 + 详情弹窗 + 撤销 | `SummaryDivider`、`SummaryDetailDialog` | — |
| chat/widget/ModelConfigCard.kt (140) | 模型未配好时的提示卡 | `ModelConfigCard` | — |
| chat/widget/TokenUsageBadge.kt (91) | token 用量徽章（≥80% 变警示色） | `TokenUsageBadge`、`formatTokens` | — |
| chat/widget/ImageThumbnail.kt (71) | 本地图片 → base64 JPEG（512 边 / Q80） | `ImageThumbnail.toBase64Jpeg` | 只在 `generate_image` 回填图片时用；与 :app 的 `ChatImagePreparer`（1024 边 / Q85）**两套压缩参数**（G16） |
| test/ChatContinueTest.kt (143) | 「继续」可用性矩阵（10 例） | — | — |
| test/ChatStageTest.kt (323) | 阶段流转 + 取消后状态（8 例） | — | — |
| test/ChatStateCanSendTest.kt (71) | `canSend` 判定 | — | — |
| test/ChatStreamingTextTest.kt (116) | 流式文本槽与气泡文本的优先级（8 例） | — | — |
| test/ChatTitleTriggerTest.kt (321) | 标题生成触发条件与覆盖规则（6 例） | — | — |
| test/ChatWiringGuardTest.kt (834) | **源码文本断言**护栏（参数是否真的透传） | — | 靠读 .kt 源码做字符串匹配，重命名即失效（KDoc 自认）；834 行测试里有大量 `assertTrue(vm.contains(...))`，属于「用测试代替类型系统」的味道（G17） |
| test/command/SlashCommandTest.kt (123) | 解析规则 16 例 | — | — |
| test/MessageContextMenuTest.kt (292) | 菜单集合 + 复制洗白 18 例 | — | — |
| test/MessageDeletePersistenceTest.kt (265) | 删除消息的落库行为 | — | — |
| test/MessageEditorPartsTest.kt (112) | 编辑器标签往返 9 例 | — | — |
| test/navigator/BuildTurnEntriesTest.kt (118) | 回合切分 | — | — |
| test/PendingQueueCodecTest.kt (101) | 队列存档 8 例 | — | — |
| test/PendingQueueTest.kt (174) | 队列操作 17 例 | — | — |
| test/ReasoningPersistenceTest.kt (253) | 思考内容落库（一次而非逐 delta）5 例 | — | — |
| test/SendActionModeTest.kt (82) | 发送按钮四态 6 例 | — | — |
| test/SessionTitleTest.kt (102) | 标题清洗 11 例 | — | — |
| test/SlashQueryTest.kt (55) | 补全触发条件 6 例 | — | — |
| test/ThrottledContentWriterTest.kt (184) | 节流器 13 例 | — | — |
| test/ToolCardExecutingTest.kt (288) | 工具卡「先建卡后填充」4 例 | — | — |

### 3.2 feature:settings（57）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| main/AndroidManifest.xml | 空壳 + overrideLibrary | — | 同上 |
| root/SettingsEntry.kt (220) | **设置首页入口枚举（18 项）**，每项直接持有 `PraxisRoute` + 标题 + 摘要 | `SettingsEntry`（18 项）、`SettingsEntry.of(route)` | `of()` **全仓零引用**（G5）；枚举有 18 项但首页只画了 16 项（G1） |
| root/SettingsHomeScreen.kt (153) | 设置首页：4 个分组、`Card` 里直排 `MiuixNavigationItemWidget` | `SettingsHomeScreen()`、`SettingsCard(vararg)` | **显式列举**而不是遍历 `SettingsEntry.entries`，已漏掉 `Characters` 与 `CompatPluginEntries`（SettingsHomeScreen.kt:67-119，G1） |
| root/SettingsHost.kt (1107) | 122 参数分发器：`PraxisPage` 壳 + `when(route)` 穷举到具体页面 | `SettingsHost(route, …122 参数)` | 122 参数、1107 行；无 `else` 兜底页而是 `SettingWrongRoute`（这是对的）；参数表见 `4.2` |
| root/ImageGenRoute.kt (888) | 设置→本地生图主页：引擎探活、起停、目标选择、下载源、默认参数、输出、诊断 | `ImageGenRoute(probeEngine, engine*, installedModels, …)`、`ProbeUi` | 888 行单文件；`PLAN_LINES` 里有些说明是硬编码计划文案（ImageGenRoute.kt:608），改实现后容易忘改 |
| root/ImageGenModelsRoute.kt (225) | 模型库：内置清单 + 下载进度 + 取消 | `ImageGenModelsRoute(models, installedIds, state, …)` | 全局单飞下载（同一时刻只能下一个，ImageGenModelsRoute.kt:46）——用户点第二个模型会看到原因，符合现状 |
| root/ImageGenTargetDialog.kt (352) | 生图目标选择（本地模型组 / 服务商组） | `ImageGenTargetDialog`、`ImageGenRemoteModel` | 服务商组来自 `gateway_provider.modelsJson`，字段没填过的家不会出现（不是 bug，但用户会以为「没有」） |
| ai/ModelScreen.kt (1437) | 模型配置页：服务商、端点、模型池、上下文、压缩阈值、思考档位、温度、Key、额外头、RPM/并发 | `ModelScreen` / `ModelRoute`、`maskApiKey` | 1437 行；服务商预设 13 项来自 `ApiProvider`（core:model），与网关的 24 内置家**两套清单**（G18） |
| ai/ModelRoute.kt (303) | 模型页接线（配置档案新建/重命名/切换 + 读写 repository） | `ModelRoute(gatewayModels, repository, modelCatalogFetcher)` | — |
| ai/ModelPickerDialog.kt (512) | 模型**多选**弹窗（模型池） | `ModelPickerDialog` | 与 ModelSelectDialog 两套单选/多选实现（有意，KDoc 说明） |
| ai/ModelSelectDialog.kt (262) | 模型**单选**弹窗（功能位） | `ModelSelectDialog` | — |
| ai/FunctionModelScreen.kt (443) | 功能位模型配置（含「生图」行接 imagegen 目标） | `FunctionModelRoute` | 生图行与其它功能位不是同一套覆盖模型（KDoc 已说明） |
| ai/SystemPromptRoute.kt (43) | 系统提示词接线（含人格覆盖） | `SystemPromptRoute` | — |
| ai/SystemPromptScreen.kt (255) | 系统提示词页（分层优先级说明 + 人格块） | `SystemPromptScreen` | — |
| appearance/AppearanceRoute.kt (78) | 外观页接线（主题 6 个写入口 + 聊天显示风格） | `AppearanceRoute` | — |
| appearance/AppearanceScreen.kt (415) | 外观页（模式/取色/调色板/色卡/预测性返回/聊天风格） | `AppearanceScreen`、`ThemeMode.displayName` 等扩展 | 9 个调色板 × 2 代色规 × 18 色卡，全量渲染（SeedSwatch 已避免逐卡算 scheme） |
| capability/MemoryRoute.kt (88) | 记忆页接线（删后整表重读） | `MemoryRoute(store, activeSpaceId, onSelectSpace…)` | — |
| capability/MemoryScreen.kt (281) | 记忆库页（空间切换、新建空间、条目列表） | `MemoryScreen` | 无搜索（有意）；空空间与默认空间文案见 `spaceLabelOf` |
| capability/MemoryCard.kt (124) | 记忆卡片（3 行折叠 + 标签 FlowRow + 相对时间） | `MemoryCard`、`relativeTime` | — |
| capability/WorkflowRoute.kt (70) | 工作流接线（运行 + 轨迹） | `WorkflowRoute` | — |
| capability/WorkflowScreen.kt (328) | 工作流页（3 条示例流 + 运行 + 逐步轨迹） | `WorkflowScreen`、`SampleWorkflows` | 只有「跑示例」没有编辑（KDoc 明说），入口存在的意义需与用户预期对齐 |
| capability/WorkflowNodeCard.kt (169) | 节点卡片（5 类节点参数展示） | `WorkflowNodeCard`、`WorkflowBranchRow` | 收「原始字段」避免依赖 core:workflow（取舍已说明） |
| character/CharactersRoute.kt (67) | 角色卡列表接线（`remember` 建 VM，不用 `viewModel()`） | `CharactersRoute` | — |
| character/CharactersScreen.kt (334) | 角色卡列表（使用中徽章、导入酒馆 JSON、长按无关） | `CharactersScreen` | 列表页无删除（有意，放编辑页） |
| character/CharacterEditRoute.kt (78) | 编辑页接线（cardId=null 为新建） | `CharacterEditRoute` | — |
| character/CharacterEditScreen.kt (416) | 编辑页（字段顺序 = 提示词拼接顺序） | `CharacterEditScreen` | — |
| character/CharacterViewModel.kt (394) | 角色卡状态机（列表 + 编辑两套 StateFlow） | `CharacterViewModel`、`CharactersUiState`、`CharacterEditUiState` | 需外部 Scope（可纯 JVM 测），但生命期靠 `rememberCoroutineScope` |
| character/PromptTagsRoute.kt (68) | 标签库接线（引用数内存计算） | `PromptTagsRoute` | — |
| character/PromptTagsScreen.kt (349) | 标签库（新建/编辑正文/删除） | `PromptTagsScreen` | — |
| common/SettingsList.kt (161) | 设置类页面统一滚动容器（顶栏避让 + 底栏滚动限位） | `SettingsList` | 收敛得对：早期 8 个页面各硬编码 `bottom = 24.dp` 导致最后一项被底栏压住（KDoc 记录） |
| env/LinuxEnvRoute.kt (73) | 环境页接线（`viewModel(key="linux-env")`） | `LinuxEnvRoute(port, …)` | 固定 key 是为了与终端设置页共享同一实例（见 TerminalStorageRoute） |
| env/LinuxEnvViewModel.kt (1376) | 环境状态机：装/换源/DNS/SSH/FRP/FTP/占用/清 tmp/重置/日志 2000 行 | `LinuxEnvViewModel`、`LinuxEnvUiState`、`LinuxEnvPort`（13 个方法） | 1376 行「上帝 VM」；`MAX_LOG_LINE_LENGTH = 2000` 截断隐藏信息（LinuxEnvViewModel.kt:455） |
| env/LinuxEnvScreen.kt (800) | 环境页 UI（发行版、镜像、安装、启动、SSH、FRP、FTP、包组、日志） | `LinuxEnvScreen` | 单文件承载全部子系统，800 行 |
| env/InstallLogPanel.kt (146) | 安装日志面板（等宽、可复制） | `InstallLogPanel` | — |
| env/PackageGroupsCard.kt (232) | 软件包组卡（6 组、可展开、勾选） | `PackageGroupsCard` | 默认全收起（有意） |
| env/TerminalSettingsScreen.kt (261) | 终端设置页（字号/回滚/横幅 + 存储 + 键盘） | `TerminalSettingsScreen`、`TerminalStorageState` | — |
| env/TerminalStorageRoute.kt (150) | 把 `LinuxEnvViewModel` 接成 `TerminalStorageState`（复用 key） | `rememberTerminalStorageState` | 跨页共享同一 VM 靠「没有 NavHost + 同一个 ViewModelStoreOwner」这条隐含前提（TerminalStorageRoute.kt:73，G19） |
| env/TerminalStorageSection.kt (572) | 存储管理卡 + 共享 tmp + 虚拟键盘卡 + 键盘编辑器 | `StorageManagementCard`、`SharedTmpCard`、`VirtualKeyboardCard`、`VirtualKeyboardEditorDialog` | 键盘编辑器允许任意标签，长度/emoji 无校验（未验证是否会被 `terminalKeyLayoutOf` 拒绝） |
| home/SettingsUnfinished.kt (101) | 「这一项没做」原因页 + 路由传错页 | `SettingsUnfinished`、`SettingWrongRoute` | — |
| info/AboutScreen.kt (632) | 关于页（hero 玻璃 logo、滚动淡入、许可入口、致谢） | `AboutScreen`、`GlassCard` | 为对齐上游写了 `spacerHeightPx` 的魔法公式（AboutScreen.kt:190-195） |
| info/LogScreen.kt (700) | 日志页（按天文件、级别色、折叠、请求记录视图） | `LogScreen(gatewayRequests, onLoadGatewayRequests)` | 「请求记录」把网关报表塞进日志页（两件事混一页，G20） |
| info/OpenSourceLicenseScreen.kt (157) | 开源许可明细（手写库清单） | `OpenSourceLicenseScreen` | 许可数据手写而非构建产物生成（KDoc 自认） |
| plugin/PluginsRoute.kt (48) | 扩展中心接线（remember(repository, market)） | `PluginsRoute` | — |
| plugin/PluginsScreen.kt (263) | 扩展中心（已安装 → 市场 → 说明） | `PluginsScreen` | 不显示插件注册了多少工具（运行时才知道，有意） |
| plugin/PluginViewModel.kt (236) | 插件状态机（装/卸/启停/市场分页/文件安装） | `PluginViewModel`、`PluginUiState`、`installedEntries()` | `installedEntries()` 恒返回 `emptyList()`（PluginViewModel.kt:234）——死方法（G21） |
| plugin/CompatPluginEntryRoute.kt (86) | 兼容插件入口接线（一次扫描、不监听） | `CompatPluginEntryRoute` | 装/卸包后本页不刷新（需重进），KDoc 接受 |
| plugin/CompatPluginEntryScreen.kt (183) | 插件声明的导航项/页面/工具箱卡片清单 | `CompatPluginEntryScreen` | — |
| plugin/PluginEntryScreen.kt (144) | 插件页面渲染（Loading/Ready/Failed 三段 + 重试） | `PluginEntryScreen` | — |
| plugin/ComposeDslView.kt (170) | compose_dsl 渲染树 → Compose；白名单在 core:plugin | `ComposeDslView` | `MAX_DEPTH = 48` 之后静默截断（ComposeDslView.kt:150） |
| tools/ToolsScreen.kt (181) | 工具清单（全部工具 + 档位/风险徽章 + 启用开关） | `ToolsScreen`、`PermissionTier.tierLabel`、`ToolRisk.riskLabel` | — |
| tools/PermissionScreen.kt (399) | 权限页（真实授权状态 + 三档策略 + 每档可用工具数） | `PermissionScreen`、`CapabilityRow` | — |
| tools/PermissionSwitchRow.kt (179) | 逐工具的实际判定展示（ALLOW/ASK/FORBID 是算出来的，不可切换） | `PermissionSwitchRow`、`ToolAccessLevel` | 与用户「逐工具三态」的预期不符（KDoc 已解释底层模型不支持） |
| tools/PrivilegeStatusCard.kt (307) | 大状态卡 / 统计卡 / 统计行（照抄 InstallerX 视觉） | `PrivilegeStatusCard`、`PrivilegeStatCard`、`PrivilegeStatRow` | — |
| test/CharacterUiStateTest.kt (140) | 角色卡派生状态 11 例 | — | — |
| test/LinuxEnvViewModelTest.kt (351) | 环境 VM 13 例（busy 复位、端口校验、安装顺序…） | — | 覆盖了「只能真机才发现」的四类失败（KDoc） |
| test/LinuxEnvSharedRootfsTest.kt (79) | 共享 rootfs 的「该不该显示应用软件源」6 例 | — | — |
| test/SettingsMappingTest.kt (173) | 判定/标签/节点映射 12 例 | — | — |

### 3.3 feature:terminal（13）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| main/AndroidManifest.xml (2) | `<manifest/>` 空文件 | — | 与其它模块的 overrideLibrary 写法不一致（G12） |
| terminal/TerminalViewModel.kt (1137) | 终端状态机：多 Tab、真 PTY / 管道两种模式、外部会话同步、快照恢复、UTF-8 增量解码 | `TerminalViewModel`、`CommandRunner`、`Utf8StreamDecoder`、`isRealTerminal` | 1137 行；`isRealTerminal` 由「port != null」推出，端口装配失败时静默退回管道模式（TerminalViewModel.kt:158，G22） |
| terminal/TerminalScreen.kt (1439) | 终端渲染（滚动、自适应、虚拟键盘、Tab 条、操作条、输入条）+ 调色板 | `TerminalScreen`、`TerminalUiState`、`TerminalTab`、`TerminalPalette`、`TerminalTypography`、`buildAnnotatedScreen` | 1439 行，含 256 色调色板与排版；`TerminalPalette.defaultBackground` 等三个函数直接返回入参（TerminalScreen.kt:1319-1329），是空壳占位（G23） |
| terminal/PtySessionPort.kt (148) | 真 PTY 端口接口 + `NoPtySessionPort` 降级 | `PtySessionPort`、`screen()`、`sessionNames()` | `NoPtySessionPort` 让「没装配」与「已装配但没会话」在类型上不可区分（G22） |
| terminal/TerminalSnapshotPort.kt (84) | 快照持久化端口（Tab 屏幕行 + 历史 + 输入） | `TerminalSnapshotPort`、`TerminalSnapshot`、`TabSnapshot` | — |
| terminal/keyboard/TerminalKeyboardModel.kt (223) | 键位模型（两行七列、`TerminalKeyId` 身份）与标签映射 | `TerminalKeyId`、`TerminalKey`、`TerminalKeyLayout`、`DefaultTerminalKeyLayout`、`terminalKeyLayoutOf(labels)` | `TerminalKeyLayout` 构造会对行列数做 `IllegalArgumentException`（测试已锁） |
| terminal/keyboard/TerminalKeyEdit.kt (277) | 键位编辑纯逻辑（插入、光标、按词移动、Ctrl/Alt、历史翻页 5 条） | `TerminalKeyInput`、`TerminalModifiers`、`applyTerminalKey` | 历史翻页固定 5 条（`HISTORY_PAGE_SIZE`） |
| terminal/keyboard/TerminalVirtualKeyboard.kt (227) | 虚拟键盘渲染 | `TerminalVirtualKeyboard`、`KeyCap` | — |
| test/TerminalViewModelTest.kt (243) | Tab/命令/取消/清屏/banner 11 例 | — | — |
| test/ExternalSessionSyncTest.kt (217) | 外部会话合并去重 8 例 | — | — |
| test/Utf8StreamDecoderTest.kt (135) | UTF-8 跨包 7 例 | — | — |
| test/TerminalAnnotatedScreenTest.kt (98) | 屏幕文本导出 7 例 | — | — |
| test/keyboard/TerminalKeyEditTest.kt (308) | 键位编辑 22 例 | — | — |

### 3.4 ui:navigation（12）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| main/AndroidManifest.xml | 空壳 + overrideLibrary | — | — |
| navigation/Routes.kt (319) | **35 个具名目的地**（`data object` + 3 个参数化 `data class`）+ `MainTab` 四 Tab | `PraxisRoute`（35 子类型）、`PraxisRoute.Main/ChatSessions/Settings*/PluginEntry`、`MainTab`、`MainTab.of/indexOf` | **15 个 Route 缺 `@Serializable`**（Routes.kt:176-264）而文件自己的 KDoc（Routes.kt:43-47）要求「每个 Route 必须 @Serializable」，见 G6 |
| navigation/PraxisNavDisplay.kt (240) | 每个 Route 一行 `entry<…>`（35 行）+ `swipeDismiss` 逐页配置 + `title()` 穷举 + `isRoot()` + `currentRoute()` + `ensureSeeded()` | `PraxisNavDisplay`、`PraxisRoute.title()`、`isRoot`、`currentRoute`、`ensureSeeded` | 35 个 entry 与 35 个 Route **一一对应**（已程序化核对）；`entry` 的缩进在 146-158 行有断裂（格式化噪声，不影响编译） |
| navigation/Navigator.kt (121) | 栈操作唯一入口（push 幂等、pop 100ms 防抖、popNow、popToRoot）+ `LocalNavigator` | `Navigator.push/pop/popNow/popToRoot/current/backStackSize`、`LocalNavigator` | `lastPopTime` 是实例字段，多 Navigator 实例不共享防抖窗口（当前只有一个实例） |
| navigation/MainPagerState.kt (137) | Tab 切换的 Pager 状态机（选中态先行、可打断、像素补齐、距离相关时长） | `MainPagerState.animateToPage/syncPage`、`rememberMainPagerState` | 无测试（nav 模块 0 个测试） |
| navigation/MainScreenBackHandler.kt (97) | 返回键第二级：只剩根页且不在第 1 个 Tab → 回第 1 个 Tab | `MainScreenBackHandler(mainPagerState, navigator, homeTabIndex)` | 依赖宿主提供 `NavigationEventDispatcher`；KDoc 记录过「整级静默失效」的真实事故（MainScreenBackHandler.kt:28-48） |
| navigation/PraxisBottomBar.kt (151) | 浮层底栏（`FloatingBottomBar` / 实色降级） | `PraxisBottomBar(selected, onSelect)` | 未下发 `LocalPageBackdrop` 时走实色分支（正确） |
| navigation/BottomBarGlass.kt (93) | 底栏玻璃修饰符（vibrancy → blur → lens）+ 半透明底色 | `bottomBarSurface`、`rememberBottomBarBackdrop`、`Modifier.bottomBarGlass` | 与 `PraxisBottomBar` 各自读一次 `GlassBackend`，两处判据需同步 |
| navigation/transition/NavTransitionDispatcher.kt (42) | 档位 → `NavTransition` 的唯一分发点（Aosp/Miuix/Scale/Classic） | `navTransitionFor(animation, exitDirection)` | 无测试 |
| navigation/transition/AospNavTransition.kt (369) | 1:1 复刻 AOSP 预测性返回（缩放 0.9→1、边缘贴合、Y 跟手、弹跳） | `AospNavTransition` | 大量魔法常量（BOUNCE_*/FADE_*），注释给了来源但无可回归的数值测试 |
| navigation/transition/ScaleAndClassicNavTransition.kt (229) | Scale / Classic 两档退场动画 | `scaleNavTransition(exitDirection)`、`ClassicNavTransition` | 同上 |
| navigation/transition/NavTransitionGeometry.kt (92) | 缓动曲线（双贝塞尔拼接）与像素吸附工具 | `FastOutExtraSlowIn`、`snapScaleToPixelExtent`、`snapTranslationToPixelEdge` | 无测试（纯函数，最容易补的那种，G24） |

### 3.5 ui:theme / ui:icons / ui:design（8 + 1 + 44）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| theme/PraxisTheme.kt (248) | 双引擎主题门面：miuix 为唯一色彩源，M3 语义色**照抄** miuix；`movableContentOf` 保证切主题不重建 | `PraxisTheme(settings, content)`、`LocalThemeSettings`、`LocalPraxisIsDark`、`rememberM3FromMiuix` | M3 色板只在 `primary/surface/background/tertiaryContainer` 变化时重算（PraxisTheme.kt:174）——其它字段变了不会重组，理论上有陈旧色彩风险（未验证） |
| theme/PraxisThemeSettings.kt (13) | `ThemeSettings.keyColorOrNull`（跟壁纸时必须为 null） | `keyColorOrNull` | — |
| theme/PraxisCapabilities.kt (34) | API 33/31 能力判定（带 `@ChecksSdkIntAtLeast`） | `glassSupported/dynamicColorSupported/predictiveBackSupported` | — |
| theme/PraxisRadius.kt (103) | 圆角令牌（7 档 + 预置 Shape） | `PraxisRadius.Micro…Huge`、`*Shape`、`SideSheetShape` | 明确不引入 squircle（有实测掉帧结论） |
| theme/PraxisMotion.kt (170) | 动效令牌（时长/曲线/弹簧/缩放/位移/展开） | `PraxisMotion.Durations/Curves/Springs/Scales/Offsets/Expansion` | `Expansion` 的四个值与 `Durations` 刻度不同源（有意，上游原值） |
| theme/SeedSwatch.kt (208) | 色卡派生（HSL 近似）+ 18 个预置品牌色 | `seedSwatch`、`SeedSwatch`、`PresetSeedColors.all/labelOf` | 手写 HSL 而不是 HCT（有意，避免 18×scheme 计算） |
| icons/PraxisIcons.kt (96) | 全项目唯一图标源（outlined 语义命名 + 手写 Logo） | `PraxisIcons.Chat/Terminal/Memory/Settings/Info/Document/Tools/Logo` | 只有 8 个图标，其余图标散落在各 feature 模块的 material-icons 直引 |
| design/component/AppScaffold.kt (407) | 三段式 Scaffold：顶栏（可磨砂）、内容（`LocalPageBackdrop` 采样源）、底栏 slot | `AppScaffold`、`LocalBottomBarInset`、`LocalTopBarInset`、`LocalScrollBottomLimit`、`BlurredTopBar` | 三个 CompositionLocal 是全项目「避让」的隐式契约，页面漏读就是被压住（`SettingsList` KDoc 记录过真实事故） |
| design/component/AutoFollowScroll.kt (677) | 自动跟随滚动（Lazy / 普通两版）+ 三条纯函数判据 | `AutoFollowScrollLazy`、`AutoFollowScroll`、`shouldFollowContent/Scroll`、`isAtBottom`、`unknownRemainingPx` | 677 行、6 个派生判据；测试只覆盖 3 个纯函数（AutoFollowScrollTest 13 例） |
| design/component/RevealEngine.kt (198) | 逐字显现的速度/背压/FPS 估算（纯函数） | `RevealEngine.charsThisFrame/backpressure/smoothLineAdvance/shouldPause/estimateFps` | — |
| design/component/SpringFollower.kt (110) | 弹簧跟随积分器（k=130 c=24，dt 钳位） | `SpringFollower.step` | — |
| design/component/ScrollThumbRail.kt (240) | 滚动缩略轨 + 回底按钮 | `ScrollThumbRail`、`shouldShowScrollToBottom` | — |
| design/component/ImeLift.kt (152) | IME 抬升（内容抬多少 + 底部 pad 多少） | `ImeLift`、`rememberImeLift`、`Modifier.imeLiftPadding` | — |
| design/component/PraxisPage.kt (115) | 每页自带 Scaffold（标题 + 返回 + PopupHost + 背景） | `PraxisPage`、`PageSurface` | 这是「弹窗不弹/闪退」结构修复的落点（KDoc 有历史） |
| design/component/LowAppScaffold.kt (88) | 低版本实色 Scaffold | `LowAppScaffold` | — |
| design/component/FloatingIconButton.kt (87) | 圆形浮动图标按钮 | `FloatingIconButton` | — |
| design/diagnostics/UiDiagnostics.kt (143) | 重组计数/进入计数/错误上报 + `TraceRecomposition` | `UiDiagnostics` | 生产路径也在写（`note`），无开关；错误只进内存列表（G25） |
| design/effect/AnimatedFluidBackground.kt (431) | 流动渐变背景（多层 + 无限过渡） | `AnimatedFluidBackground` | 每帧多图层 alpha/color 动画，低端机功耗未评估 |
| design/effect/BgEffectBackground.kt (207) | OS2/OS3 背景效果入口（AGSL） | `BgEffectBackground`、`rememberDeviceType` | 需要 API 33；低版本靠 `isRuntimeShaderSupported()` 兜底 |
| design/effect/BgEffectConfig.kt (210) | 背景预设（OS2/OS3 × 手机/平板 × 亮/暗 8 套色） | `BgEffectConfig.get` | — |
| design/effect/BgEffectModifier.kt (241) | `Modifier.bgEffectDraw`（自定义 Element/Node） | `bgEffectDraw` | — |
| design/effect/BgEffectPainter.kt (210) | RuntimeShader 封装 + 参数缓存 | `BgEffectPainter` | 大量 `cached*` 字段，靠手工失效 |
| design/effect/GlowShader.kt / OS2BgFrag.kt / OS3BgFrag.kt (370/101/104) | AGSL 着色器源码常量 | `GLOW_AGSL`、`OS2_BG_FRAG`、`OS3_BG_FRAG` | 着色器错误只能在设备上暴露（无测试） |
| design/effect/HyperCeilerStartupGlow.kt (271) | 开屏光晕（1:1 复刻 HyperCeiler） | `HyperCeilerStartupGlow` | — |
| design/effect/DeviceType.kt (24) | 设备类型（手机/平板） | `DeviceType` | — |
| design/glass/GlassBackend.kt (62) | 玻璃后端开关（AUTO/强制低/强制高） | `GlassBackend.mode/useLowPipeline/noGlassAtAll` | 全局可变状态，无持久化（重启回 AUTO，可能是有意） |
| design/glass/FloatingBottomBar.kt (657) | iOS 风浮动底栏（三层背景 + 重力高光 + 拖拽切 Tab） | `FloatingBottomBar`、`FloatingBottomBarColors`、`FloatingBottomBarMode` | 657 行；`rememberGravityRotatedHighlight` 依赖传感器读值（取不到时静默用固定方向，G26） |
| design/glass/kyant/LiquidToggle.kt (308) | **全项目唯一开关视觉**（液态玻璃滑块 + 拖拽） | `LiquidToggle(selected, onSelect, backdrop, enabled)` | 见 `4.6`；要求调用方 provide backdrop |
| design/glass/low/LowLiquidToggle.kt (199) | 低版本开关（纯色但保留全部交互） | `LowLiquidToggle` | 两实现必须同步改（颜色映射表在文件里各写一份，G27） |
| design/glass/kyant/LiquidButton.kt (206) | 液态玻璃按钮（三档 tone） | `LiquidGlassButton`、`LiquidButtonTone` | — |
| design/glass/kyant/LocalKyantBackdrop.kt (43) | 采样源 CompositionLocal + 回退 | `LocalKyantBackdrop`、`rememberPageBackdropOrFallback` | — |
| design/glass/LocalPageBackdrop.kt (25) | 页面级采样源（供顶栏/底栏折射） | `LocalPageBackdrop` | 与 `LocalKyantBackdrop` **两个采样源并存**（一个给底栏/顶栏，一个给开关/按钮），页面必须两个都 provide（G28） |
| design/glass/liquid/Lens.kt (226) | 折射镜片 shader（含色散） | `BackdropEffectScope.lens` | 只支持 `CornerBasedShape`（Lens.kt:73 else return） |
| design/glass/liquid/Vibrancy.kt (20) | 饱和增强 | `vibrancy` | KDoc 自认是 Kyant 的「轻量替代」 |
| design/glass/liquid/InnerShadow.kt (164) | 内阴影 Modifier | `innerShadow` | — |
| design/glass/liquid/CombinedBackdrop.kt (48) | 两层采样源合并（滑块要折射轨道 + 页面） | `rememberCombinedBackdrop` | — |
| design/glass/animation/DampedDragAnimation.kt (225) | 阻尼拖拽（弹簧 + 按压形变 + 速度挤压） | `DampedDragAnimation` | — |
| design/glass/animation/InteractiveHighlight.kt (120) | 按压高光跟随 | `InteractiveHighlight` | — |
| design/glass/DragGestureInspector.kt (104) | 拖拽手势底层工具 | `inspectDragGestures` | — |
| design/glass/low/LowFrostedTopBar.kt (89) | 低版本磨砂顶栏 | `LowFrostedTopBar` | 固定 α=0.85（唯一可调点） |
| design/util/DeviceCornerRadius.kt (104) | 设备圆角读取（`RoundedCorner` / 资源回退） | `rememberDeviceCornerRadius` | 反射读资源 id（`@SuppressLint("DiscouragedApi")`，DeviceCornerRadius.kt:87） |
| design/res/values{,-night}/colors.xml、res/anim/sine_in_out_interpolator.xml | 主题配色与插值器资源 | — | — |
| theme/test/PaletteMappingTest.kt (59) | 调色板映射 / 壁纸必须给 null seed / 18 色一致性 5 例 | — | — |
| design/test/AutoFollowScrollTest / RevealEngineTest / ScrollThumbRailVisibilityTest / SpringFollowerTest（239/181/107/110） | 纯函数判据回归 | — | 都没有 Compose 层面的「组合行为」测试 |

### 3.6 ui:widgets（34）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| widgets/MiuixBaseWidget.kt (206) | **开关/复选框行的唯一实现**（内部换成 `LiquidToggle`） | `MiuixSwitchWidget`、`MiuixCheckboxWidget`、`MiuixMultiApkCheckboxWidget` | 12 处调用点已收敛；但 `MiuixCheckboxWidget` 仍是 miuix 原生 Checkbox（视觉与开关不同源，G29） |
| widgets/FoldableContent.kt (132) | 折叠面板内容区（高度 + 透明度联动，支持从底边长） | `FoldableContent(visible, expandFromBottom, content: ColumnScope.()->Unit)` | **约束**：必须常驻调用，只翻 `visible`；`if (visible)` 包一层会导致退场瞬间消失（FoldableContent.kt:59-64） |
| widgets/MiuixAnimatedDialog.kt (309) | 带进出场动画的居中弹窗（上移 min(6%,48dp)、全屏点击层） | `MiuixAnimatedDialog(visible, onDismissRequest, maxWidth, content)` | **约束**：必须常驻挂载（不要 `if (visible)`）；内部 mount/visible/progress 三态（MiuixAnimatedDialog.kt:81-148） |
| widgets/MiuixAnimatedPopup.kt (146) | 同上，用于 Popup 定位的弹层 | `MiuixAnimatedPopup` | 同一约束 |
| widgets/MiuixDialog.kt (173) | 确认框 / 单选列表弹窗（支持 `LocalDismissState`） | `MiuixConfirmDialog`、`MiuixSelectionDialog<T>` | — |
| widgets/MiuixBlockingLoadingDialog.kt (34) | 阻塞式加载弹窗 | `MiuixBlockingLoadingDialog(visible, text)` | — |
| widgets/MiuixButtons.kt (143) | 返回按钮 / 文本动作按钮 / 侧栏动作按钮 | `MiuixBackButton`、`MiuixTextActionButton`、`SidebarActionButton` | — |
| widgets/MiuixCards.kt (194) | 提示卡（警告/设置/安装器）+ 错误块 | `WarningCard`、`MiuixSettingsTipCard`、`MiuixInstallerTipCard`、`MiuixErrorTextBlock` | — |
| widgets/MiuixBadge.kt (54) | 小徽章 | `MiuixBadge` | — |
| widgets/MiuixCharacterSwitcher.kt (113) | 顶部角色名切换（截断 12 字） | `MiuixCharacterSwitcher` | — |
| widgets/MiuixDraggableList.kt (185) | 通用拖拽排序列表（卡片式） | `MiuixDraggableList<T>` | 与 `PendingQueuePanel` 的 `DragReorderState` 重复（G15） |
| widgets/MiuixDropDownItem.kt (50) | 下拉项（首尾额外 padding） | `DropdownItem` | — |
| widgets/MiuixNavigationItemWidget.kt (36) | 设置项行（ArrowPreference 包装，整行可点） | `MiuixNavigationItemWidget` | 设置首页与多处依赖它「整行可点」（KDoc 记录过自拼 Row 不可点的缺陷） |
| widgets/MiuixNumPickerWidget.kt (116) | 数字选择器 | `MiuixIntNumberPickerWidget` | — |
| widgets/MiuixSelectableRow.kt (61) | 可选中行 | `MiuixSelectableRow` | — |
| widgets/MiuixSidebar.kt (842) | 侧边栏（拖拽开合、进度缓动、会话行、分组标题、空态） | `MiuixSidebar`、`SidebarSessionRow`、`SidebarSectionLabel`、`SidebarEmptyState`、`sidebarEasedProgress` | 842 行；手势与页面横滑竞争，靠 `SIDEBAR_HORIZONTAL_DOMINANCE` 判据协调（MiuixSidebar.kt:784） |
| widgets/MiuixSmallTitleDropDown.kt (198) | 标题式下拉（SmallTitle + 弹层） | `MiuixDropdown` | — |
| widgets/MiuixTextField.kt (101) | 带 placeholder 动效的输入框 | `MiuixHintTextField` | 圆角/颜色写死在本文件（16.dp、surfaceContainer），未走 PraxisRadius（G30） |
| widgets/PraxisWebViewSheet.kt (313) | WebView 弹层（进度、页面底色探测、回调拦截） | `PraxisWebViewSheet`、`parsePageColor` | 注入 JS 探页面底色（PraxisWebViewSheet.kt:277）——需 `SetJavaScriptEnabled` |
| widgets/ProgressButton.kt (149) | 带进度填充的按钮 | `ProgressButton`、`ProgressButtonDefaults.progressButtonColors` | — |
| widgets/SeedColorGrid.kt (220) | 18 色卡网格（扇形 + 中心圆） | `SeedColorGrid` | — |
| widgets/SettingsSectionHeader.kt (106) | 设置分组标题 | `SettingsSectionHeader` | — |
| widgets/StreamingText.kt (84) | 流式文本 + 闪烁光标 | `StreamingText` | 与 `MarkdownText` 的流式光标两套实现（G31） |
| widgets/MarkdownText.kt (517) | markdown 渲染（块级 + 行内 + 流式冻结 + 异步解析） | `MarkdownText(text, streaming, asyncParse, …)` | 短文本同步解析阈值 512 与 `MarkdownParseCachePolicy` 重复定义（G13） |
| widgets/markdown/MarkdownParser.kt (351) | markdown → `MdBlock`/`MdInline` 纯解析 | `MarkdownParser.parse/parseInline`、`MdBlock`（7 种）、`MdInline`（6 种） | 不支持嵌套列表/HTML（有意）；表格要求分隔行 |
| widgets/markdown/MarkdownParseCache.kt (151) | LRU(16) 解析缓存 + 后台线程 | `MarkdownParseCache.getOrParse/parseAsync/peek/put` | 自建单线程 Executor（MarkdownParseCache.kt:60），与协程体系并存 |
| widgets/markdown/StreamingMarkdownState.kt (140) | 流式增量解析（冻结已完成块） | `StreamingMarkdownState.blocksFor/stableCut` | — |
| widgets/ChatDisplayStyle.kt (78) | 聊天显示风格（Cursor / 气泡…） | `ChatDisplayStyle`、`fromStorage/toStorage` | — |
| widgets/BuildFlags.kt (19) | 全局 debug 标志（由 :app 写入） | `BuildFlags.isDebug` | 可变全局状态 |
| widgets/theme/MiuixSheetColors.kt (35) | sheet 卡片色（非动态色时手工兜底） | `miuixSheetCardColor(s)` | 硬编码 `0xFF1C1C1E` |
| widgets/res/drawable/ic_praxis_drag_handle.xml | 拖拽柄图标 | — | — |
| widgets/test/markdown/MarkdownParserTest.kt (202) | 解析 17 例 | — | — |
| widgets/test/markdown/StreamingMarkdownStateTest.kt (135) | 流式增量 9 例 | — | — |

### 3.7 core:linux（20）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| main/kotlin/…/ProotCommand.kt (912) | **容器命令的唯一生成处**：启动/登录/执行/会话参数、bind 列表、换源脚本、Kali 源、DNS、legacy sources 退役 | `LinuxEnvironment`、`ProotCommand.launchArgs/loginArgs/execArgs/sessionArgs/sourceSwitchCommand/switchMirrorScript/dnsScript/retireLegacySources`、`REQUIRED_MOUNTS` | 912 行靠字符串拼 shell；**所有脚本都无 dry-run/回显**，出错只能看 apt 输出（G32） |
| …/LinuxDistro.kt (193) | 发行版清单（Ubuntu/Kali 家族、rootfs URL、解包大小、内置 assets）+ `KaliMirror` | `LinuxDistro`、`DistroFamily`、`KaliMirror` | rootfs URL 硬编码在代码里（无版本校验） |
| …/UbuntuMirror.kt (204) | Ubuntu 镜像（archive/ports）与发行版代号（Noble 等）+ 基础镜像 URL 候选 | `UbuntuMirror`、`UbuntuRelease`、`UbuntuBaseImage.candidateUrls` | `patchLevels` 手工维护，上游发新点版本后候选 URL 会 404（有多个候选，非致命） |
| …/LinuxEnvSettings.kt (61) | 环境页持久化的非敏感配置（发行版/镜像/端口/FTP） | `LinuxEnvSettings`、`toFtpSettings` | 不含密码（密码单独存，正确） |
| …/FtpSettings.kt (94) | FTP 配置 + 四个可用性判据 | `FtpSettings`、`isPortUsable/isPassiveRangeValid/isUserUsable/isHomeUsable/problem` | — |
| …/RemoteAccess.kt (325) | SSH（sshd_config 生成、用户/密码/密钥）+ FRP（frpc.toml）脚本与运行参数 | `SshConfig`、`SshdScript`、`FrpConfig`、`FrpScript` | 密码经 `chpasswd` 走命令行参数，会出现在进程列表里（RemoteAccess.kt:128，G33） |
| …/LinuxDiskUsage.kt (303) | 目录占用统计（去重 inode / 符号链接）+ 清理 + 人类可读 | `LinuxDiskUsage.diskUsage/clearContents/environmentUsage/humanBytes` | 递归统计大目录在主线程调用会卡（调度器未确认） |
| …/SpillPolicy.kt (114) | 超长文本「溢出」策略（阈值 16k，头 4k 尾 1.5k + 定位符） | `SpillPolicy.shouldSpill/makePreview`、`SpilledText.toModelText` | — |
| …/LinuxToolPort.kt (83) | 给 AI 用的 Linux 能力端口（状态 + 执行） | `LinuxToolPort`、`LinuxStatus`、`LinuxExecResult` | 与 `LinuxEnvPort`（feature:settings）两套端口语义重叠（G34） |
| …/TerminalSession.kt (395) | 会话模型 + 控制序列常量 + 命令/输出块模型 | `TerminalSessionInfo`、`TerminalSessionCommand`、`TerminalControl`、`TerminalControlSequence`、`TerminalChunk` | — |
| …/TerminalSessionTools.kt (522) | 6 个 AI 可调终端工具（建会话/执行/流式执行/关闭/输入/取屏） | `CreateTerminalSessionTool`、`ExecuteInTerminalSessionTool`、`ExecuteInTerminalSessionStreamingTool`、`CloseTerminalSessionTool`、`InputInTerminalSessionTool`、`GetTerminalScreenTool` | `session_id` 缺失时会「按名字猜最近一个」（resolveSession，TerminalSessionTools.kt:469），模型容易打错目标会话 |
| test/AptSourcesRetireTest · FtpSettingsTest · LinuxDiskUsageTest · LinuxEnvSettingsTest · ProotCommandTest · ProotLaunchArgsTest · RemoteAccessTest · SpillPolicyTest · TerminalSessionToolsTest（137/103/260/60/516/108/224/110/371） | 上述纯逻辑的回归锁（约 62 个用例） | — | 覆盖率最高的一批；`ProotCommandTest` 516 行说明脚本生成被认真测过 |

### 3.8 core:tool（16）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| …/ToolContract.kt (110) | 三档权限 + 判定 + 风险 + `ToolSpec`/`ToolResult`/`ToolContext`/`Tool` | `PermissionTier`（STANDARD/ADVANCED/ROOT）、`PermissionDecision`（ALLOW/ASK/FORBID）、`ToolRisk`、`ToolSpec`、`ToolResult`、`ToolContext`、`Tool` | `ToolResult.stage` 可选，构造点容易忘填（执行器会统一兜） |
| …/ToolRegistry.kt (146) | 工具注册表（保序、重名抛异常、按档位过滤）+ 默认判定策略 | `register/registerOrReplaceAll/removeWhere/find/all/names/descriptorsFor`、`PermissionTier.allows`、`DefaultPermissionPolicy.decide` | — |
| …/ToolExecutor.kt (202) | **串行**执行器：权限判定 → askHandler → 执行 → 失败归因 → 计时 | `ToolExecutor.executeAll/executeOne`、`ToolInvocation`、`ToolExecution`、`ToolPresentState` | 串行（测试已锁「保持输入顺序」）；`askHandler` 在 feature:chat 里恒为 null（G4） |
| …/ToolFailureStage.kt (79) | 失败阶段（Argument/Denied/Execution/Present）+ 可否重试 + 恢复提示 | `ToolFailureStage`、`safeToRetry`、`recoveryHint` | — |
| …/ToolPermissionState.kt (72) | 运行期权限态（档位 + 单工具禁用）与快照 | `ToolPermissionState`、`ToolPermissionSnapshot`、`descriptorsFor(snapshot)` | — |
| …/ToolArguments.kt (77) | JSON 参数读取助手（缺参给可读错误） | `ToolArguments.string/int/boolean/stringOrNull`、`ToolArgumentException` | — |
| …/builtin/FileTools.kt (490) | 7 个文件工具（list/read/write/delete/exists/info/move）+ 根目录沙箱 | `FileTools`、`resolveInRoot`、`ReadFileTool.maxBytes = 32_000` | 沙箱靠 `canonicalPath.startsWith(root)`（符号链接已解，OK）；读文件 32k 截断无提示（G35） |
| …/builtin/AskUserQuestionTool.kt (109) | 反问工具（走 `AskUserPort`） | `AskUserPort`、`AskUserQuestionTool` | 端口未接时降级为拒绝（测试覆盖） |
| …/custom/CustomToolDefinition.kt (204) | 自定义工具定义 + 白名单原语 + 校验器 | `CustomToolDefinition`、`ToolPrimitives.SUPPORTED`、`CustomToolValidator.validate` | 校验只查「原语是否在白名单」「占位符是否已声明」，不校验参数类型（G36） |
| …/custom/CustomToolRunner.kt (172) | 按步骤执行自定义工具（占位符替换 + 超时） | `CustomToolRunner.run`、`RunOutcome` | 步骤间无隔离，任一步失败即整体失败 |
| …/custom/CustomTool.kt (82) | 把定义包成 `Tool`（参数 JSON 由定义生成） | `CustomTool` | — |
| test/FileToolsTest · CustomToolValidatorTest · ToolFailureStageTest · ToolLayerTest · ToolPermissionStateTest（81/139/165/217/113） | 沙箱、校验、失败归因、权限矩阵（约 44 例） | — | — |

### 3.9 core:goal / memory / workflow / terminal（2 + 6 + 4 + 5）

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| goal/SessionGoal.kt (165) | 会话目标（Phase 四态 + 步骤 + `toPromptText`） | `SessionGoal`、`GoalPhase`、`PlanStep`、`SessionGoalOps` | **全仓无生产调用方**（只有自身与测试）——逻辑写好但没接进 ChatViewModel（G7） |
| goal/test/SessionGoalTest.kt (127) | 12 例 | — | — |
| memory/MemoryContract.kt (112) | 记忆条目/查询/命中/写入结果 + `MemoryStore` | `MemoryItem`、`MemoryQuery`、`MemoryHit`、`MemoryWriteResult`、`MemoryStore` | `findSimilar` 是实现方必须提供的方法，返回空表会让去重静默失效 |
| memory/MemoryRetriever.kt (202) | 召回第一段：关键词/时间/标签三路 RRF 融合 | `MemoryRetriever.retrieve`、`tokenize`、`DEFAULT_RRF_K` | CJK 按字/双字分词、英文按词；中英混排效果未验证 |
| memory/MemorySearchConfig.kt (345) | 打分四档模式与权重 + `MemoryRanker`（覆盖率重排 + 图边传播）+ `MemorySearchPipeline` | `MemorySearchConfig`、`MemoryScoreMode`、`MemoryEdge`、`MemoryRanker.rank`、`MemorySearchPipeline.search` | `vectorWeight` 恒为 0（测试锁死）——向量检索是「配置里留了位置但没实现」（G37） |
| memory/MemoryWritePipeline.kt (111) | 写入去重（Jaccard 超阈值则合并） | `MemoryWritePipeline.write`、`jaccardSimilarity` | 字符集合 Jaccard 对语序不敏感（「猫咬狗」约等于「狗咬猫」），存在误合并风险（G38） |
| memory/test/MemoryTest.kt、MemorySearchPipelineTest.kt（244/170） | 召回/写管线约 22 例 | — | — |
| workflow/WorkflowContract.kt (135) | 节点模型（Trigger/Tool/Condition/Transform/Output）+ 结果/轨迹 + `WorkflowToolPort` | `WorkflowNode`、`WorkflowDefinition`、`StepRecord`、`WorkflowRunResult`、`WorkflowToolPort` | — |
| workflow/WorkflowEngine.kt (237) | 执行器：单入口、步数/时长上限、失败即停、变量表 | `WorkflowEngine.run`、`DEFAULT_MAX_STEPS`、`DEFAULT_MAX_DURATION_MS` | 失败即整体失败（无「跳过继续」）；`looksLikeToolFailure` 靠字符串前缀猜工具失败（WorkflowEngine.kt:188，G39） |
| workflow/WorkflowExpression.kt (148) | 条件表达式（==/!=/contains/truthy，含引号处理）+ 模板渲染 | `ConditionExpression.evaluate/renderTemplate` | 不支持括号与逻辑与（有意，KDoc 有边界说明） |
| workflow/test/WorkflowTest.kt (378) | 表达式/渲染/引擎 30+ 例 | — | — |
| terminal/AnsiScanner.kt (256) | ANSI/CSI/OSC 扫描器（跨包不泄漏） | `AnsiScanner.feed/reset`、`AnsiEvent`（Text/Control/Csi/Osc/Esc） | — |
| terminal/TerminalBuffer.kt (450) | 单元格网格：宽字符、pending wrap、滚动区、scrollback、行版本号 | `TerminalBuffer`、`CellStyle`、`Cell`、`rowVersion`、`deepestUsedRow` | 行版本号本为「只重绘变化行」，但 `scrollUp` 会整体 bump（TerminalBuffer.kt:305）→ 滚动即全屏重绘 |
| terminal/TerminalSession.kt (218) | 把事件流应用到 buffer（SGR/光标/擦除/256 色） | `TerminalSession.feed/reset/apply`、`applySgr` | — |
| terminal/test/TerminalTest.kt、TerminalBufferReuseTest.kt（328/195） | 扫描器/缓冲/宽字符/版本 30+ 例 | — | — |

### 3.10 ui:provision（130，**搬运代码，不改**）

> 结论先说：这是 HyperCeiler 与 fan/provision 的**开机引导链**（Java + XML + AppCompat），1:1 搬运；由 `:app` 的 `ProvisionGate` 拉起（`MainActivity.kt:304-325`）。本轮按要求**只给清单与入口，不逐行读、不建议改**。

| 目录 | 文件数 | 内容 |
|---|---|---|
| main/java/com/sevtinge/hyperceiler/provision/activity | 6 | `BaseActivity`、`DefaultActivity`（唯一入口，自带「已引导则 finish」）、`PermissionSettingsActivity`、`TermsAndStatementActivity`、`CongratulationActivity`、`ProvisionDetailActivity` |
| …/fragment | 7 | `BaseFragment`、`BaseListFragment`、`StartupFragment`、`PermissionSettingsFragment`、`TermsAndStatementFragment`、`CongratulationFragment`、`WebFragment` |
| …/state | 7 | `StateMachine`、`State`、`StateInfo` + 4 个具体状态 |
| …/renderengine | 5 | `GlowController`、`GlowPainter`、`GlowShader`、`RenderCompat`、`RenderViewLayout`（开屏光晕） |
| …/utils | 14 | `ProvisionManager`、`ProvisionPrefs`（sp 名 `praxis_provision`）、`ProvisionStateHolder`、`ProvisionPrefs`、`PageIntercepHelper` 等 |
| …/widget | 5 | `MarkdownView`、`PermissionItemView`、`PermissionWarningSheet`、`TermsAndStatementBottomSheet`、`SimpleTextWatcher` |
| …/text/style | 2 | `ClickSpan`、`TermsTitleSpan` |
| …/service | 1 | `ProvisionAnimService`（action `fan.intent.action.OOBSERVICE`） |
| main/java/fan/provision | 4 | `CustomDispatchFrameLayout`、`OobeUtils`、`ProvisionAnimHelper`、`ProvisionBaseActivity` |
| aidl(2) + assets(3) + res(71) + manifest(1) | 77 | `IProvisionAnim`/`IAnimCallback`；`marked.min.js`、`github_style.css`、`markdown_template.html`；9 anim、17 layout、16 drawable、4 glsl、18 种语言 strings |

- **入口清单**：`DefaultActivity`（exported + MAIN + `ProvisionTheme`）→ 权限页 / 协议页 / 完成页；状态由 provision 自己的 `pref_oobe_state/is_provisioned` 持有，`:app` 只读（`ProvisionGate.isDone`）；引导完成后 `MainActivity` 把 `permission_level` 转存进 `SettingsRepository`（`ProvisionGate.importFromProvision`）。
- **疑点**：见 G40（权限声明重复 / `MANAGE_EXTERNAL_STORAGE` 在引导期申请 / sp key 是跨模块硬编码契约）。

---

## 4. 关键机制深挖（含重点问题的逐条回答）

### 4.1 导航三条清单是否 1:1（重点问题 1）

**结论：Routes.kt 的 35 个目的地 ↔ PraxisNavDisplay.kt 的 35 个 entry<…> ↔ 35 条 title() 分支，三者严格 1:1，没有孤儿路由、没有漏注册。**

核对方式（程序化，不是眼看）：提取三个清单做集合差，结果：
- 声明 = 35（其中 data object 32 个，参数化 data class 3 个：SettingsCharacterEdit(cardId)、SettingsGatewayAddLogin(providerId)、PluginEntry(pluginId, entryId, title)）
- entry<…> = 35，唯一多出的匹配是 KDoc 里的示例（PraxisNavDisplay.kt:32）
- title() 分支 = 35，全部 Route 都有标题，无多余分支（多出的两个匹配是 title / isRoot 两个函数名）
- 差集全部为空。

但**同一份文件里有两个静态缺陷**（不是 1:1 问题，属 G6/G12）：
1. **15 个 Route 缺 @Serializable**：SettingsGatewayAccounts / Import / Routing / Add / AddLogin / Proxy / System / Providers / Report / Models、SettingsImageGen / ImageGenModels、SettingsCompatPluginEntries、PluginEntry（Routes.kt:176-264）。而 Routes.kt:43-47 自己写着「rememberNavBackStack 用 kotlinx.serialization 把整栈编成字符串存 Bundle，所以每个 Route 必须 @Serializable」，PraxisApp.kt:1417 也留了「存栈时抛 SerializationException」的注释。→ 静态可判定「契约被违反」，运行期后果（进程被回收后恢复栈）**未验证**（不能跑构建）。
2. entry 的缩进在 PraxisNavDisplay.kt:146-158 断裂（网关 7 行 + 生图 2 行顶格），说明这段是后期粘贴进来的 —— 与「角色卡三页漏写 entry」那次事故同源的工艺风险。

**可读性事实**：swipeDismiss 分两组（编辑类页面取 None：SettingsModel / SettingsSystemPrompt / SettingsCharacterEdit / SettingsLinux / SettingsTerminal / SettingsImageGen；其余允许侧滑），理由写在 PraxisNavDisplay.kt:37-45，与页面里有无横向手势一致 —— 我抽查了 ModelScreen（温度滑杆）、SystemPromptScreen（长文本横向滚动）、LinuxEnvScreen（Spinner），判断成立。

**派生事实（重要）**：MainScreenBackHandler + Navigator 的三级返回优先级，只在 MainActivity 提供了 NavigationEventDispatcher 时才生效（MainScreenBackHandler.kt:28-48 记录过一次整级静默失效）。

### 4.2 SettingsEntry ↔ 设置首页 ↔ 各 Route，以及 SettingsHost 参数表（重点问题 2、4）

**Q2 结论：不是 1:1。18 个枚举，首页只画了 16 个，漏掉 Characters（角色卡）与 CompatPluginEntries（兼容插件入口）。**

证据链（静态、可复现）：
- 枚举 18 项：Model、Gateway、ImageGen、FunctionModel、SystemPrompt、Characters、Appearance、Tools、Permission、Memory、Workflow、Speech、Linux、TerminalPrefs、Packages、CompatPluginEntries、Log、About（SettingsEntry.kt:46-202）
- 首页 16 项：AI 组 5（Model/Gateway/ImageGen/FunctionModel/SystemPrompt）+ 观感 1 + 能力 5（Tools/Permission/Memory/Workflow/Speech）+ 环境与信息 5（Linux/TerminalPrefs/Packages/Log/About）（SettingsHomeScreen.kt:67-119）
- 全仓搜索 PraxisRoute.SettingsCharacters：只有 4 处 —— 枚举声明、SettingsHost 的 when 分支、entry 注册、title() 分支。**没有任何 navigator.push(...)**。CompatPluginEntries 同样只有 4 处。
- 连带后果：SettingsPromptTags（标签库）与 SettingsCharacterEdit（编辑）**只能从角色卡列表页进入**（PraxisApp.kt:2479、SettingsHost.kt:755），列表页进不去 → **角色卡整套 3 个页面在 UI 上完全不可达**；而 :app 已经为它们提供了 characterRepository、onOpenCharacterEdit、onCloseCharacterEdit、onOpenPromptTags、onExportCharacterJson 五个真实实现（PraxisApp.kt:2462-2504）。
- 根因不是漏写一行，而是**首页用「显式列举」而不是遍历 SettingsEntry.entries**，且没有任何测试锁覆盖面（SettingsEntry.of() 这个反查函数全仓零引用）。

**Q4 结论：SettingsHost 的 122 个参数全部有消费方，且除 modifier 外全部由 :app 显式传值 —— 「提供商一个都没做出来」不是 SettingsHost 的接线问题。**

- 参数总数 122；用「参数名在 SettingsPageBody 的 when 体内出现次数」检查：**0 个参数从未被使用**。
- 反向检查 :app 的调用点（PraxisApp.kt:1776-2505，730 行）：**121 个参数被传；只有 modifier 用默认值**。
- 抽查「值是空的就算没做」的怀疑：没有拿 emptyList()/null 硬顶的假数据。gatewayAccountRows ← gatewayAccountStore.accountRows(...)；gatewayModelRows ← gatewayModelStore.all()；gatewayProviderRows ← gatewayAccountStore.providers()；gatewayBuiltinProviderRows ← BuiltinProviders.ids()（**24 家**：openai、anthropic、openrouter、huggingface、nvidia、deepseek、glm、glm-cn、minimax、minimax-cn、siliconflow、volcengine-ark、alicode、alicode-intl、alims-intl、alitp-intl、xiaomi-tokenplan、opencode-go、opencode-zen、clinepass、ollama-local、sub2api、newapi、sensenova，见 app/…/data/gateway/BuiltinProviders.kt）；gatewayReportWindows ← gatewayUsageStore.summarySince；gatewayRequestRecords ← gatewayUsageStore.recentRequests。
- 「添加账号」三级流程真的通：SettingsGatewayAdd → GatewayAddProviderScreen(providers = gatewayProviderChoices) → push(SettingsGatewayAddLogin(providerId)) → GatewayAddLoginScreen(onSave = …) → :app 的 gatewayAccountStore.addAccount(draft)（PraxisApp.kt:1938-1957）；「拉模型清单」也真发请求（OkHttpModelCatalogFetcher(client).fetch(...)，PraxisApp.kt:1819-1857）。
- **所以，若用户抱怨的是「设置里看不到家 / 看不到模型」，原因不在这一层**。可疑落点（都在别人范围内，标注未确认）：① opencode-zen 的 needsClientEmulation 明标「暂不能连」；② 账号页的 onOpenAccount = { } 是空实现（SettingsHost.kt:1054），「点账号进去看详情/改」确实没有页面；③ 内置家只能在「自定义提供商」页看到，账号页列表来自 accountRows（空库时为空，除「去添加」外没有引导）；④ 聊天页模型选择走 ApiProvider（core:model，13 家）而不是网关的 24 家，两套清单（G18）。建议 lead 与用户对齐具体症状再定责。

**副作用级事实**：这 122 个参数里有 5 个（characterRepository、onOpenCharacterEdit、onCloseCharacterEdit、onOpenPromptTags、onExportCharacterJson）虽然「有消费方」，但消费方页面不可达 → **实际等价于无消费方**（与 G1 同一根因）。

### 4.3 ChatViewModel 一次发送的完整状态机（重点问题 3）

分四段：入栈 → 发流 → 工具回路 → 收尾/续写。行号均指 ChatViewModel.kt。

**① send()（1254-1479）**
1. 重置 autoContinueRounds = 0（1257）→ 先做**斜杠命令拦截**（1267，命中则整条不发模型）。
2. 守卫：文本空且无附件、或 isStreaming → 直接 return（1275）。
3. **恢复门禁**：若历史还没恢复过，则置 pendingSendAfterRestore = true、提示「正在加载历史，稍后自动发送」、调 restoreIfNeeded() 并 return（1296-1302）—— 首次冷启动发送会被**延迟一拍**，恢复完成后由恢复流程补发。
4. 造两条气泡（用户 + 空助手 streaming = true），把用户消息推进 history（含 replyTag 引用前缀与图片）。
5. 一次性状态更新：input = ""、清附件、isStreaming = true、error = null、stage = Connecting（1361-1377）。
6. 落库：ensureSession → appendMessage(user) → appendMessage(assistant) → registerRowId → startStream(assistantId)（1417-1442）。
7. 若是本会话第一条用户消息，追加一次**标题生成**（generateTitleIfPossible，走 persistScope，失败保留兜底标题）。

**② startStream()（2242-2625）**
- 开头 streamingJob?.cancel()、清 pendingToolCardIds、清 liveReasoningBuffer，然后 streamingJob = viewModelScope.launch { … }。
- 流前：readRuntimeConfig()（系统提示词 / 人格 / 上下文上限 / 压缩阈值 / 思考档位 / 摘要模型 / 是否流式；任一项读失败只记 UiDiagnostics.note("config", …)，不影响发送，1882-1933）→ publishUsage → maybeCompact(config)（工具执行中会自行跳过）→ buildPreparedMessages → 若有 continueHint 则在末尾追一条 user 消息（2296-2300）。
- 流事件（AiStreamEvent 六种）：
  - Delta：首包把 stage 切 Receiving；append 到 buffer → applyStreamText（写 streamingText 槽）→ persistContent（节流落库，窗口见 ThrottledContentWriter）。
  - ReasoningDelta：只写 streamingReasoning，**不落库**（收尾时 sealReasoning 一次写入；ReasoningPersistenceTest 锁这条）。
  - ToolCallDelta：更新 toolDraft（名字 + 参数字符数），并**先建一张「参数流式」工具卡**（按 callIndex 去重，2390-2432）。
  - ToolCalls（完整调用）：stage = ExecutingTool、清 toolDraft、sealStreamText 收口正文、sealReasoning、为每个调用补齐工具卡、把 assistant(toolCalls) 推进 history、**转入 runToolCalls**（2435-2509）。
  - Usage：目前 Unit（**丢弃**，用量在流前/流后由 TokenEstimator 自算，2513）。
  - Done：清 toolDraft、dropPendingToolCards（丢掉没被填充的占位卡）、sealStreamText、flushContent（补最后一次写库）、sealReasoning、history += assistant(text)、rememberIfWorth(...)（12~300 字才记忆）、用 FinishSignalRules.of(event.finishReason) 记录 truncatedSignal 与 truncatedAtId（2515-2550）。
- 流后：isStreaming = false、stage = Idle、清 pendingRetryRestore / inFlightQueueMessage、drainQueueIfIdle()（把排队消息发出去）。
- **autoContinue 续写回合**（2575-2599）：AutoContinuePolicy.decide(config = autoContinueConfig(), signal, roundsDone = autoContinueRounds)：
  - Continue → autoContinueRounds = decision.nextRound 并 continueGeneration(target)（对同一条助手气泡再起一流，起手注入 CONTINUE_HINT）；
  - Stop(MaxRoundsReached) → 只留一条 notice「已自动续写 N 轮（到设置的上限了）」；
  - 配置默认 AutoContinueConfig() = 关闭（多跑一轮就多花钱）。
- 失败态（2600-2688）：CancellationException 原样抛出（不产生错误气泡）；AiException 与其它异常都进 onStreamFailure：
  - 造 BubbleRole.Error 气泡（文案由 AiError.displayText() 映射，4922-4945）；
  - sealStreamText + flushContent（**保留已收到的正文**）+ dropPendingToolCards + sealReasoning；
  - 若这一轮是「重试」触发的，则用 pendingRetryRestore 把原气泡换回来再追加错误气泡（避免正文重复）；
  - 在飞的队列消息 restore 回队列、suppressNextAutoDequeue = true（避免失败后立刻自动重发同一条）。
- **取消**（1549-1596）：streamingJob?.cancel() + 置 null、状态归位、保留已收正文、不产生错误气泡（ChatStageTest 有 4 例锁这条）。

**③ 工具回路（runToolCalls，2696-2948）**：executor 未装配 → 在助手气泡里追加一行说明并回 Idle（2700-2709）；否则 ToolInvocation 列表 → executeAll（**串行**，askHandler = null）→ 每个结果：history += tool(result)、generate_image 成功时把 ImageThumbnail.toBase64Jpeg 的图挂回助手气泡、工具卡填结果、落库 → 最后**新建一条空助手气泡并 startStream(followUpId)**（2898-2947）。即「一次用户输入 = N 次模型流式调用（N = 工具轮次 + 1）」，旧流靠 startStream 开头的 streamingJob?.cancel() 自行了断。

**④ 图片压缩不在本模块**：feature:chat 只接收已压好的 ChatImage(mimeType, base64)（addAttachment，1159）。真正的「读图 → 采样 → 缩放 → JPEG → base64」在 :app 的 dev.praxis.data.chat.ChatImagePreparer.prepare(context, uri, policy)（app/…/ChatImagePreparer.kt:59-125：inSampleSize 二分、长边 ≤ policy.maxEdge（默认 1024）、JPEG Q85、超 1.2MB 再压 Q60、policy.enabled = false 时原样 base64）。而 feature:chat 自己还有一份 ImageThumbnail.toBase64Jpeg（512 边 / Q80），只用于把 generate_image 产物贴回气泡 —— **两套参数**（G16）。

**⑤ 阶段枚举**（ChatState.kt:250-283）：Idle → Connecting → Receiving →（ExecutingTool | Summarizing）→ Idle；isBusy = isStreaming || stage != Idle；canSend = input 非空 || 有附件（ChatState.kt:620-624）。

### 4.4 工具结果如何回到模型，以及压缩 / 记忆两条旁路

- **工具 → 模型**：见 4.3③。副作用是**工具轮没有上限**：autoContinueRounds 只统计「收尾信号」续写，不统计工具轮；runToolCalls 每次都新起流。一个不断调工具的模型会一直转（G2）。
- **上下文压缩**：maybeCompact（2108-2219）三个前置条件 —— stage != ExecutingTool（用户原话「先等 AI 执行完工具再压缩」）、上限已知或条数到阈值、coveredCount 之后确实还有内容；压缩本身是**一次额外模型调用**（collectSummary，2221-2240），失败不影响发送（只记 notice）；压缩**只改「发出去时折叠多少条」**，从不删除原始消息，且 undoCompaction 可逆（3138-3190；ChatWiringGuardTest 断言了 compacted = CompactedContext.None 与 coveredTokens = TokenEstimator.estimate）。内存压力另有一条 compactForMemoryPressure（3949-3972），用 lastMemoryCompactLevel 去重、失败会回退档位以免「永久不重试」。
- **记忆**：rememberIfWorth（1836）只对 12~300 字的助手回复调 memoryPort.remember；召回在发送前经 MemoryPort.recall 拼进上下文。core:memory 的检索是「关键词 + 标签 + 图边」三路 RRF + 覆盖率重排，vectorWeight 恒 0（G37）。

### 4.5 ui:widgets 自研控件清单与使用约束（重点问题 5）

| 控件 | 用途 | 硬约束（不遵守会怎样） |
|---|---|---|
| MiuixSwitchWidget（MiuixBaseWidget.kt:53） | **全项目开关的唯一实现**，内部换成 LiquidToggle（12 处调用点已收敛） | 采样源用 rememberPageBackdropOrFallback：页面没 provide LocalKyantBackdrop 时回退纯色 —— 不会变黑块，但**没有折射**。想要玻璃效果必须由页面下发采样源 |
| LiquidToggle（design/glass/kyant，308 行） | 液态玻璃开关（三层：轨道 / 玻璃滑块 / 阻尼拖拽），颜色全走 MiuixTheme 语义色 | ① 只在 API 33+ 有意义（低版本由 LowLiquidToggle 兜底，两个文件必须同步改颜色映射）；② **不要读 isSystemInDarkTheme()**（KDoc 明令，否则 App 强制暗色 + 系统亮色时取错配色）；③ 滑块用 rememberCombinedBackdrop 折射「轨道 + 页面」两层，去掉会看起来浮在轨道外 |
| MiuixAnimatedDialog（widgets，309 行） | 带进出场动画的居中弹窗（进 240ms 缩放 0.94→1，退 160ms 到 0.97，整体上移 min(屏高×6%, 48dp) 且下限 16dp） | **必须常驻挂载**：写成 if (visible) { MiuixAnimatedDialog(...) } 会让退场动画来不及播（直接消失）。内部 visible/mounted/progress 三态 + 全屏透明点击层（usePlatformDefaultWidth = false 后系统不再有「外部」概念）；它**不改** visible，只回调 onDismissRequest |
| MiuixAnimatedPopup（146 行） | 同上，给 Popup 定位的弹层 | 同上 |
| FoldableContent（132 行） | 折叠面板内容区（expandVertically + fadeIn 双动画，时长取 PraxisMotion.Expansion） | **必须常驻调用、只翻 visible**；贴底面板必须传 expandFromBottom = true，否则「卡片向上长 + 内容向下长」叠加成抖动（KDoc 记录过用户反馈） |
| MarkdownText + markdown/*（517 + 351 + 151 + 140） | markdown 渲染：块/行内模型、流式冻结、LRU(16) 缓存、短文本（< 512 字符）同步解析 | 流式场景必须传 streaming = true 才会用 StreamingMarkdownState 冻结已完成块，否则每帧全量重解析（长回答掉帧） |
| MiuixSidebar（842 行） | 侧边栏（拖拽开合 + 进度缓动 + 会话行 + 分组 + 空态） | 与页面横滑手势竞争，判据是 SIDEBAR_HORIZONTAL_DOMINANCE；开合阈值 SIDEBAR_SETTLE_THRESHOLD = 0.38 |
| MiuixDropdown / MiuixIntNumberPickerWidget / MiuixSelectableRow / MiuixNavigationItemWidget / MiuixHintTextField / ProgressButton / SeedColorGrid / MiuixDraggableList / PraxisWebViewSheet / StreamingText / WarningCard / MiuixBadge / MiuixCharacterSwitcher / SettingsSectionHeader / MiuixBlockingLoadingDialog / MiuixConfirmDialog / MiuixSelectionDialog / MiuixBackButton / MiuixTextActionButton | 项目自研 miuix 风格控件集 | MiuixNavigationItemWidget 是设置项「整行可点」的唯一正确写法（自拼 Row{Text;Icon} 曾导致整行不可点）；PraxisWebViewSheet 会开 JS 探页面底色 |

### 4.6 两条采样源链路（玻璃效果的隐藏约束）

- 链路 A：LocalPageBackdrop（design/glass/LocalPageBackdrop.kt:25）—— 由页面 Scaffold（AppScaffold / PraxisPage）下发，供**顶栏磨砂与底栏玻璃**（PraxisBottomBar → FloatingBottomBar、BottomBarGlass）。
- 链路 B：LocalKyantBackdrop（kyant/LocalKyantBackdrop.kt:28）—— 语义相同，但给 **LiquidToggle / LiquidGlassButton** 用。
- 页面必须**两条都 provide** 才有「全页玻璃」；漏一条的表现是「开关有折射但底栏没有」或反之（不崩，只是降级）。GlassBackend.mode（AUTO / 强制低 / 强制高）是全局可变状态，两个消费点各自读一次（G28）。

### 4.7 core 侧三个值得记住的实现

1. **ToolExecutor 的失败归因**（ToolExecutor.kt:117-200）：未知工具 → 归因 Argument 并**把可用工具名一并回给模型**（这条直接减少「模型瞎猜工具名」的死循环）；ASK 且无 askHandler → 视为拒绝（Denied）；ToolArgumentException → Argument；其余异常 → Execution；全部附带 recoveryHint（例如 Execution 阶段提示「先确认状态再重试」）。ToolPresentState 把「呈现失败 ≠ 执行失败」分开，避免把一次 UI 异常记成工具失败。
2. **WorkflowEngine.run 的护栏**（WorkflowEngine.kt:36-156）：唯一 Trigger（多个即报错）、步数上限 DEFAULT_MAX_STEPS、时长上限 DEFAULT_MAX_DURATION_MS、任一点失败即 fail(...) 且**带上已跑过的 steps**（UI 里那串轨迹就是它）；分支指向不存在的节点会显式报错而不是静默结束。
3. **TerminalBuffer 的宽字符与行版本**（TerminalBuffer.kt:185-306）：pendingWrap 处理「写到最后一列不立刻换行」；宽字符（CJK）占两格且第二格标 continuation（渲染时跳过，避免重复画）；rowVersion 数组本意是「只重绘变化的行」，但 scrollUp 会 bumpAllRowVersions —— 滚动一屏等于整屏重绘。

---


---

## 5. 缺口 / 疑点清单（G1–G40，编号与第 3 节表格里的引用一一对应）

> 严重度：**P0** = 用户可感知的功能不可用 / 数据风险；**P1** = 功能存在但入口或回路断了（「做了一半」）；**P2** = 技术债 / 一致性 / 可维护性。
> 每条：证据（路径:行号）→ 影响 → 建议动作。标「未验证」的是本轮不能跑构建/真机、只能给静态证据的部分。

| 编号 | 严重度 | 一句话 |
|---|---|---|
| G1 | P0 | 设置首页漏 2 个入口 → 角色卡整套 3 页在 UI 上不可达 |
| G2 | P0 | 工具调用轮次无上限，可无限自激 |
| G3 | P0 | startStream 自取消再重启，状态可能被旧流覆盖 |
| G4 | P0 | 工具权限 ASK 在对话中被静默降级为拒绝 |
| G5 | P1 | SettingsEntry.of() 零引用（G1 的同源病灶） |
| G6 | P1 | 15 个 Route 缺 @Serializable，违反自身契约 |
| G7 | P1 | core:goal 写好但没接进对话 |
| G8 | P1 | 节流写库在进程被杀时丢最后一段正文 |
| G9 | P1 | ChatPersistence 一半方法是默认空实现（漏实现静默失效） |
| G10 | P1 | ChatEvent 是死类型 |
| G11 | P1 | /model /new /export 三个命令只置标志、无人读 → 空动作 |
| G12 | P2 | feature:terminal 的 manifest 是空壳 |
| G13 | P2 | markdown 两个常量在两处各写一份 |
| G14 | P1 | 斜杠命令没有参数能力（5 个内置命令全是零参数） |
| G15 | P2 | 拖拽重排有两套实现 |
| G16 | P1 | 图片压缩两套参数（1024/Q85 vs 512/Q80） |
| G17 | P2 | 用源码文本断言替代类型系统 |
| G18 | P1 | 聊天模型 13 家 vs 网关 24 家两套清单 |
| G19 | P1 | 跨页共享 ViewModel 依赖隐含前提 |
| G20 | P2 | 网关请求记录塞进日志页 |
| G21 | P2 | PluginViewModel.installedEntries() 恒空 |
| G22 | P1 | 真终端能力不可用时静默降级为管道模式 |
| G23 | P2 | TerminalPalette 三个默认色函数是空壳 |
| G24 | P2 | 转场几何纯函数零测试 |
| G25 | P2 | UiDiagnostics 在生产路径无开关 |
| G26 | P2 | 底栏重力高光取不到传感器时静默用固定方向 |
| G27 | P2 | 两个开关实现的颜色映射各写一份 |
| G28 | P1 | 两条采样源 CompositionLocal 并存，页面必须都 provide |
| G29 | P2 | 复选框仍是 miuix 原生，与开关不同源 |
| G30 | P2 | MiuixTextField 圆角/颜色硬编码 |
| G31 | P2 | 流式光标两套实现 |
| G32 | P2 | ProotCommand 生成的脚本无回显/dry-run |
| G33 | P1 | SSH 密码经 chpasswd 走命令行参数（会进进程列表） |
| G34 | P2 | 两套 Linux 端口（core:linux 与 feature:settings）语义重叠 |
| G35 | P2 | 文件工具读 32k 截断无提示 |
| G36 | P2 | 自定义工具不校验参数类型 |
| G37 | P2 | 记忆向量检索留了配置没实现 |
| G38 | P2 | 记忆去重用字符集合 Jaccard（语序不敏感） |
| G39 | P2 | 工作流靠字符串前缀猜工具失败 |
| G40 | P2 | provision 的 sp key 是跨模块硬编码契约 |

### P0

**G1 · 设置首页漏两个入口，导致角色卡整套 3 页在 UI 上不可达**
- 证据：SettingsEntry 18 项（SettingsEntry.kt:46-202）vs SettingsHomeScreen.kt:67-119 只列 16 项，缺 Characters 与 CompatPluginEntries；全仓搜 PraxisRoute.SettingsCharacters / SettingsCompatPluginEntries 只有 4 处（声明、SettingsHost 分支、entry 注册、title），**无任何 navigator.push**；SettingsPromptTags / SettingsCharacterEdit 只能从角色卡列表进入（PraxisApp.kt:2479、SettingsHost.kt:755）。
- 影响：用户点不到「角色卡」「兼容插件入口」；角色卡 / 标签库 / 编辑页整链不可用；:app 为它们准备的 5 个真实实现（PraxisApp.kt:2462-2504）全部空转。属「定义了却进不去」的复发。
- 建议：① 首页改为遍历 SettingsEntry.entries 分组渲染；② 加覆盖率测试（entries ⊆ 首页集合、每个 Route 至少一处 push），可用第 3 节同款源码断言手法；③ 删掉零引用的 SettingsEntry.of() 或真正用起来。

**G2 · 工具调用轮次没有上限，模型可以无限自激**
- 证据：runToolCalls 结尾无条件 startStream(followUpId)（ChatViewModel.kt:2898-2947）；autoContinueRounds 只由 AutoContinuePolicy（收尾信号）驱动（2575-2599）；全文件无 MAX_TOOL_ROUNDS 类常量（grep 确认）。
- 影响：模型反复调工具（文件不存在→重试→再失败）会持续烧 token，用户只能手动停止；表现为一直转圈而不是报错。
- 建议：加工具轮上限（如 8 轮）+ 超限给明确 Error 气泡「工具已连续执行 N 轮，已中止，最后一次结果：…」；上限做成设置项（有消费方）。

**G3 · startStream 里「自取消再重启」是脆弱写法**
- 证据：startStream 首行 streamingJob?.cancel()（ChatViewModel.kt:2243）随后 streamingJob = viewModelScope.launch（2263）；而它常被**当前 job 内部**调用（续写 2585、工具回路 2947）。
- 影响：旧 job 到下一个挂起点才真正退出，期间仍可能写 _state（例如把 isStreaming 置回 false）；现在靠「取消异常原样抛出」侥幸正确，任何人在流后/失败路径加一行 _state.update 就可能出现「新流已开始、状态被旧流覆盖」的闪烁。**运行期未验证**。
- 建议：拆成 launchStream（只负责 launch）与 finalizeStream；调用方显式 await 旧 job 或带 skipFinalize 标记；加单测锁住「续写/工具回合时旧流不再写状态」。

**G4 · 工具权限 ASK 在对话里被静默降级为拒绝**
- 证据：runToolCalls 传 askHandler = null，注释自认「弹框征询本轮不做，ASK 降级为拒绝」（ChatViewModel.kt:2749）；ToolExecutor 对 ASK 无 handler 时返回 Denied + recoveryHint（ToolExecutor.kt:143-163）。
- 影响：档位不足时模型只得到「权限不足…已拒绝执行」，界面上没有「允许一次 / 去提权」入口 —— 有拒绝、无出路。
- 建议：把 askHandler 接到对话内确认弹窗（MiuixConfirmDialog 已有），或在工具卡上给「允许一次 / 去设置提权」。

### P1

**G5 · SettingsEntry.of() 全仓零引用**
- 证据：grep SettingsEntry.of / SettingsEntry.entries → 0 处调用（G1 的同源病灶：有反查能力却不用，首页走显式列举）。

**G6 · 15 个 Route 缺 @Serializable，违反 Routes.kt 自己的契约**
- 证据：Routes.kt:43-47 要求「每个 Route 必须 @Serializable」，实际注解只出现在 20 处（Routes.kt:49-289）；缺注解的 15 个见 4.1；PraxisApp.kt:1417 已留「存栈时抛 SerializationException」的告诫。
- 影响：进程被回收后恢复返回栈时，网关/生图/插件入口页面可能整栈恢复失败或抛异常。**未验证**。
- 建议：补齐注解；加反射式单测（遍历 PraxisRoute 子类断言有 serializer），否则新增路由还会漏。

**G7 · core:goal 写好了但没接进对话**
- 证据：SessionGoal / SessionGoalOps / toPromptText 全仓仅出现在自身与 SessionGoalTest.kt；buildPreparedMessages（ChatViewModel.kt:1943-2038）无 goal 拼接点。
- 影响：165 行逻辑 + 12 个测试是死代码；用户没有任何「设定目标 / 看进度」入口。
- 建议：接进系统提示词组装（一行 toPromptText 追加）+ 给入口；或登记为「已实现待接线」。

**G8 · 节流写库在进程被回收时丢最后一段正文**
- 证据：persistContent 走 ThrottledContentWriter（ChatViewModel.kt:1057-1070）；onCleared 只 drainPending 一次（1033-1048），而「被系统杀死」不走 onCleared。
- 影响：长回答生成到一半被杀，最后 ≤ 一个窗口的正文丢失（重启后读到的是短的）。**未验证**（取决于窗口常数与真实 kill 时机）。
- 建议：窗口改小，或 onStop 也 drain 一次。

**G9 · ChatPersistence 一半方法是默认空实现**
- 证据：ChatPersistence.kt:45/100/119/133/142/207/233 给 {} 或空表默认值（updateMessageReasoning、clearMessages、deleteMessage、updateContextUsage、loadContextUsage、attachToVariantGroup、appendVariant、loadVariants）。
- 影响：实现方漏实现时**编译通过、功能静默失效**（思考不落库、变体切换无效、上下文用量不恢复）。
- 建议：空实现只留给测试可见成员（默认抛 NotImplementedError），或启动期自检把缺失能力显示出来。

**G10 · ChatEvent（Delta/Finished/Failed）是死类型**
- 证据：ChatState.kt:660-669 定义，全仓仅此文件出现。
- 建议：删除，或让它成为 UI 订阅流式事件的真正契约（现在 UI 直接读 streamingText 槽，两套模型并存）。

**G11 · /model /new /export 三个命令只置标志、无人读**
- 证据：runCommand（ChatViewModel.kt:3228-3290）对 model / new / export 只置 modelPickerRequested / newSessionRequested / exportRequested（3245/3248/3263），全仓**无任何读取点**（grep 只有 ChatState 声明 + 这三处写入）。
- 影响：输入这三条命令只清空输入框，什么都不发生 —— 典型假控件。
- 建议：让 ChatTab/ChatScreen 读这三个标志并真的弹选择器/新建/导出；否则从 BuiltinCommands 移除，只留真生效的 compact 与 clear。

**G14 · 斜杠命令没有参数能力**
- 证据：CommandDefinition 有 inputHint 字段（command/SlashCommand.kt:117）与 needsArgument()，但 ChatViewModel.needsArgument 恒返回 false（ChatViewModel.kt:3333）；5 个内置命令全部 submitsToModel = false（command/BuiltinCommands.kt:32-58）。
- 影响：用户无法「/model gpt-4o」直接带参执行，只能走 G11 的三条空动作或手动点 UI。
- 建议：给需要参数的命令实现「取当前行剩余文本作为参数」，与 G11 一起修。

**G16 · 图片压缩有两套参数**
- 证据：:app 的 ChatImagePreparer（长边 1024 / Q85 / >1.2MB 再压 Q60，ChatImagePreparer.kt:45-125）vs feature:chat 的 ImageThumbnail（MAX_EDGE 512 / Q80，widget/ImageThumbnail.kt:25-46）。
- 影响：同一张图「发出去的」与「generate_image 回贴的」清晰度不同，用户会觉得生图结果更糊。
- 建议：ImageThumbnail 吃同一个 ChatImageCompression policy。

**G17 · 用源码文本断言替代类型系统**
- 证据：feature/chat/src/test/…/ChatWiringGuardTest.kt（834 行，source(relative) 读 .kt 源码做 contains 断言）；:app 也有同类护栏。
- 影响：测试替类型系统干活 —— 重命名/格式化即失效，且只防「已知的那几处」。它反映的真实问题是「ChatScreen 60+ 可空回调的参数透传没有编译期保障」。
- 建议：中期把页面需要的一组回调收成数据类（如 ChatActions），漏传就从「静默失效」变成「构造期缺字段」；短期保留护栏但把断言集中到一处常量表。

**G18 · 聊天模型服务商（13 家）与网关门（24 家）是两套清单**
- 证据：core:model 的 ApiProvider 13 项（ApiProvider.kt:52-122）vs app 的 BuiltinProviders 24 项；ModelScreen 用前者（ai/ModelScreen.kt:1391），网关用后者。
- 影响：用户会问「为什么模型页只有 13 家、网关有 24 家」；两套的 baseUrl/协议也可能分叉（GLM 在网关表里是 Anthropic 协议）。
- 建议：在 UI/文档里明确分工（直连单家 vs 多账号多路由），或在模型页服务商列表加一组「来自网关」。

**G19 · 跨页共享 ViewModel 依赖「没有 NavHost」这个隐含前提**
- 证据：SettingsHost.kt:844-856、env/TerminalStorageRoute.kt:73-90 依赖「整页共享同一个 ViewModelStoreOwner + 同一个 key(linux-env)」。
- 影响：一旦设置页进入真正的 NavHost（每 entry 一个 owner），终端设置页会拿到另一个 LinuxEnvViewModel → 显示「未安装」而环境页明明装好了。
- 建议：写成 KDoc 禁止事项，或改用 CompositionLocal 显式传 VM 实例。

**G22 · 真终端能力「装配失败」与「没装会话」不可区分**
- 证据：PtySessionPort.kt:132-146 的 NoPtySessionPort 让 isRealTerminal 只判 port != null（TerminalViewModel.kt:158）；PraxisApp.kt:482-483 说明 adapter 无条件注册。
- 影响：容器没装好时终端静默退回「管道模式」，用户看到能跑 shell 但不是真 PTY 的终端，没有任何提示。
- 建议：顶栏加状态胶囊（真 PTY / 管道 / 不可用 + 原因）；把 ptyPort 是否等于 NoPtySessionPort 显式暴露。

**G28 · 两条采样源 CompositionLocal 并存**
- 证据：design/glass/LocalPageBackdrop.kt:25（顶栏/底栏用）与 kyant/LocalKyantBackdrop.kt:28（LiquidToggle/LiquidGlassButton 用）。
- 影响：页面漏 provide 其中一条时只是「局部没玻璃」，不报错；两处 GlassBackend 判据各读一次，难排查。
- 建议：统一成一个（或让 Kyant 版默认取 Page 版），至少在两个 KDoc 里互相指路。

**G33 · SSH 密码经 chpasswd 走命令行参数**
- 证据：core/linux/RemoteAccess.kt:128 setPasswordScript 用 echo user:pass | chpasswd 的字符串拼接，最终由 proot 以参数形式执行。
- 影响：密码会出现在进程命令行（同机其它进程可见），与「凭证不进日志」的纪律不一致。
- 建议：改为 stdin 写入（exec 时通过标准输入传），或至少做一次 shell 转义并避免明文出现在 argv。

### P2

**G12 · feature:terminal 的 manifest 是空壳**
- 证据：feature/terminal/src/main/AndroidManifest.xml 内容为 <manifest />；其余 6 个模块都写 tools:overrideLibrary 放行 miuix。
- 影响：现在能编（终端模块是否直接引用 miuix 更高 API 未确认），但一旦直接引用就会在 manifest 合并时因 minSdk 冲突报错，且错误指向合并结果很难定位。
- 建议：照抄那行 overrideLibrary，成本 3 行。

**G13 · markdown 两个常量在两处各写一份**
- 证据：ChatWindowPolicy.kt:102/111（MarkdownParseCachePolicy.SHORT_TEXT_LIMIT / MAX_ENTRIES）与 MarkdownText.kt:516、MarkdownParseCache.kt:40 重复。
- 影响：改一处忘另一处 → 测试测的是别的常数。

**G15 · 拖拽重排有两套实现**
- 证据：queue/PendingQueuePanel.kt:510-619（rememberDragReorderState / DragReorderState）与 ui:widgets/MiuixDraggableList.kt。
- 影响：两套手势/动画行为不一致，修 bug 要修两处。
- 建议：队列面板改用 MiuixDraggableList，或把 DragReorderState 上移为共享实现。

**G20 · 网关请求记录塞进日志页**
- 证据：LogScreen(gatewayRequests, onLoadGatewayRequests)（SettingsHost.kt:1064-1068）+ info/LogScreen.kt:638 GatewayRequestCard。
- 影响：日志页职责变成「运行日志 + 网关请求」，筛选/清空语义混在一起；查「聊天请求失败原因」会先来这里，但这里只有网关的。
- 建议：并进报表页（GatewayReportScreen 已存在）或独立 Route。

**G21 · PluginViewModel.installedEntries() 恒返回空表**
- 证据：plugin/PluginViewModel.kt:234。
- 影响：死方法，名字却像真实现，后来者会以为插件工具条目从这里取。
- 建议：删除或接上 PluginRuntimeHost.capturedEntries。

**G23 · TerminalPalette 三个「默认色」函数是空壳**
- 证据：feature/terminal/TerminalScreen.kt:1319-1329 defaultBackground/defaultForeground/inverseBackground 都直接 return 入参。
- 影响：看起来像「终端配色可主题化」，实际没有；深色背景下无 SGR 的输出对比度可能不足。
- 建议：实现（取 MiuixTheme 的 surface/onSurface）或删掉并注明「配色完全由 SGR 决定」。

**G24 · 转场几何/缓动纯函数零测试**
- 证据：ui/navigation/src 下无 test 目录；NavTransitionGeometry.kt 的 FastOutExtraSlowIn / snapScaleToPixelExtent / snapTranslationToPixelEdge 全是纯函数。
- 影响：出错只表现为「动画有点抖/有点慢」，用户说不清，也测不到。
- 建议：补 5~8 个纯函数测试（像素吸附的整数性、曲线端点、单调性）。

**G25 · UiDiagnostics 在生产路径无开关**
- 证据：UiDiagnostics.kt:97 note() 被 ChatViewModel（config 失败）直接调用；recompositions/enters 两个 SnapshotStateMap 常驻。
- 影响：生产构建也在累积重组计数；note 的内容只进内存，日志页看不到。
- 建议：用 BuildFlags.isDebug 包一下，或把 note 接到 PraxisLogger。

**G26 · 底栏重力高光取不到传感器时静默用固定方向**
- 证据：design/glass/FloatingBottomBar.kt:173-190（GRAVITY_DIR_THRESHOLD_SQ + rememberGravityRotatedHighlight）。
- 影响：模拟器/无传感器设备上高光方向恒定，观感与真机不同；没有「降级了」的任何提示（属良性，但排查动效问题时容易误判）。
- 建议：在 KDoc 里补一句「无传感器时按 LIGHT_REF 固定方向」，本轮已记录。

**G27 · 两个开关实现的颜色映射各写一份**
- 证据：kyant/LiquidToggle.kt:137-140 与 low/LowLiquidToggle.kt:78-81 是同一张三行映射表。
- 建议：抽成一处（liquidToggleColors(colorScheme)）。

**G29 · 复选框仍是 miuix 原生，与开关不同源**
- 证据：widgets/MiuixBaseWidget.kt:98-130（开关换成 LiquidToggle，Checkbox 未换）。
- 影响：勾选框出现在角色卡标签、包组勾选等页面，视觉与开关不同源。

**G30 · MiuixTextField 圆角/颜色硬编码**
- 证据：widgets/MiuixTextField.kt:38-40（cornerRadius = 16.dp、surfaceContainer）；与 PraxisRadius.Large 恰好同值，但改令牌不跟。

**G31 · 流式光标两套实现**
- 证据：widgets/StreamingText.kt:66-84 与 widgets/MarkdownText.kt:492-516，都是 rememberInfiniteTransition + alpha 动画。
- 建议：合并成一个内部 Composable。

**G32 · ProotCommand 生成的脚本无回显/dry-run**
- 证据：core/linux/ProotCommand.kt 全文（912 行）都是 buildString 拼 shell；install/换源/DNS 三步无「先打印将要执行什么」。
- 影响：装环境失败时用户只看到 apt 的报错尾部，无法确认实际执行的命令（安装顺序错这类问题只能靠猜）。
- 建议：在环境页的安装日志里先回显关键脚本（或至少回显 sources.list 内容），与 SpillPolicy 的「头尾可见」是同一条思路。

**G34 · 两套 Linux 端口语义重叠**
- 证据：core/linux/LinuxToolPort.kt（status + run）与 feature/settings/env/LinuxEnvViewModel.kt:203-400 的 LinuxEnvPort（13 个方法）。
- 影响：同一件事（容器状态/执行）有两个接口，AI 工具走前者、设置页走后者，两边的「是否可用」判据可能分叉。
- 建议：明确「LinuxToolPort 是 AI 面、LinuxEnvPort 是 UI 面」，并让前者由后者实现（现在是 :app 各写一份）。

**G35 · 文件工具读 32k 截断无提示**
- 证据：core/tool/builtin/FileTools.kt:122 maxBytes = 32_000。
- 影响：模型读到被截断内容却不知道被截断，会基于残文下结论（core:linux 的 SpillPolicy 是同一问题的另一种处理：头尾 + 定位符）。
- 建议：截断时在尾部追加说明（「已截断，全文 N 字节，可带 offset 继续读」）。

**G36 · 自定义工具不校验参数类型**
- 证据：core/tool/custom/CustomToolDefinition.kt:123-192（只校验名称、白名单原语、占位符、副作用、步数）。
- 影响：required 的 int 参数传成字符串要到运行时才炸，且归因 Execution 而不是 Argument。

**G37 · 记忆向量检索留了配置没实现**
- 证据：MemorySearchConfig.vectorWeight 默认 0.0；MemorySearchPipelineTest 的用例名就是「向量权重恒为零」。
- 影响：用户会以为记忆是语义检索，实际只有关键词/标签/图边；KDoc 未明说。

**G38 · 记忆去重用字符集合 Jaccard**
- 证据：core/memory/MemoryWritePipeline.kt:102-109 用 toSet() 求字符集合。
- 影响：语序不同的两句话会被判相似而合并（「A 导致 B」vs「B 导致 A」= 语义反转被当作重复）。
- 建议：至少改成字符二元组集合。

**G39 · 工作流靠字符串前缀猜工具失败**
- 证据：core/workflow/WorkflowEngine.kt:188 looksLikeToolFailure 匹配 ERROR_MARKERS 前缀。
- 影响：工具返回「失败：…」或 JSON 带 error 字段会漏判；正常输出以 Error 开头会误判。
- 建议：WorkflowToolPort 返回值带 isError（ToolResult 已有该字段）。

**G40 · provision 的 sp key 是跨模块硬编码契约**
- 证据：app/…/ProvisionGate.kt 直接按名字读 provision 的 sp（pref_oobe_state / is_provisioned / praxis_provision / permission_level），读失败由 runCatching 兜成默认值；provision 侧声明了 MANAGE_EXTERNAL_STORAGE / SYSTEM_ALERT_WINDOW / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS / 位置（ui/provision/src/main/AndroidManifest.xml）。
- 影响：provision 是「不改」的搬运代码，字符串一旦漂移 → 引导完成后权限档位静默丢失且无任何报错；权限声明重复（app 模块也有）会让商店审核多问。
- 建议：:app 侧加启动自检（读不到 permission_level 就在日志页记一条），并把 key 收成 :app 侧常量集中定义。

---

## 6. 给 lead 的三条建议（按性价比排序）

1. **先修 G1**（首页改遍历 + 覆盖率测试）。一次改动同时消掉 G5、并让 5 个已接线的角色卡参数真正有消费方 —— 根因单行、用户可感知、能被测试锁死。
2. **再修 G6 + G2 + G11/G14**（补 15 个 @Serializable、给工具轮上限、把 3 个空命令补齐或移除）。这三条都属于「编得过、跑起来才发现」那类，正是 Routes.kt KDoc 总结的教训。
3. **最后处理 G4 / G22 / G33**（ASK 弹窗与出路、真终端状态可见、密码不进 argv）。这三条是「有拒绝无出路 / 静默降级 / 凭证暴露面」，与 GOAL 铁律 1、2 直接冲突，但改动面大，适合单独一轮。

### 本轮没做的事（供下一轮接力）
- 没跑任何构建 / 单测 / 真机（纪律限制），所有运行期结论都标了「未验证」。
- ui:provision 只做清单与入口，未逐行读（按指示：搬运代码，不改）。
- 网关 / 生图 / 插件 / ai 等**其他 agent 范围**的模块，只在「接线是否真的通」这个必要范围内扫了 :app 侧调用点（PraxisApp.kt:1776-2505），未做逐文件审计。
- 建议下一轮接手的人优先读：SettingsHomeScreen.kt（G1 落点）、ChatViewModel.kt:2242-2960（发送/工具/续写三段）、Routes.kt:160-265（缺注解那 15 个）。
