# 网关移植 · 当前状态与偏离记录

本文记录 Agent2API 网关移植到 Praxis 的实际进度：**做成了什么、怎么验证的、哪些明确没做、哪些地方有意偏离了文档**。

规格来源是 PORTING-GATEWAY.md。凡与本文冲突之处，以那份文档为准；本文只解释「实现与它差在哪、为什么」。

## 一、一句话现状

P0 / P1 / P2 全部完成，P3 除「Images（其实已有，见 §五-1）」外全部完成：
路由引擎、失败切换、账号池、三类错误、策略（顺序/轮询+粘性）、容错（熔断四参）、
**token 预算**、请求记录 + 保留期、报表页、日志来源筛选、导出导入、总开关、
**三种协议全链路**（OpenAI Chat / Anthropic Messages / OpenAI Responses）、
调用日志、**整库备份（导出）**。

生图线（另一份文档）：P0a / P1 全部完成 —— 内核、模型目录、下载（含并发）、
设置六区、槽位与持久化、三个工具、结果进对话、进度显示、自动存相册、诊断。

**仍然只有 2 家内置家**（官方 `anthropic` / `openai`）。其余 19 家的地址与模型清单要等 P5
的 Rust 内核 —— 我不凭印象写地址（写错的表现是「配了账号却一直 404/401」，而用户会
以为是自己的 key 错了）。自定义家（`custom-*`）与这两家都能端到端跑通。

**最大的未消除风险不是功能，而是「零真机验证」**：见 §十（8 项）。
**明确不可完成的项**见 §十一（8 条，每条带解锁条件与解锁人）。

## 二、现在能走通的完整路径

```
设置 → 网关 → 导入导出 → 从剪贴板
  粘贴账号文件（format: agent2api-account）→ 四段报告 → 写入（凭证加密落库）
  粘贴 modelRules（含 mappings）→ 保存映射
模型页切到「网关」档案 → 发消息
  → SettingsBackedProvider 按 kind 分派
  → GatewayProvider → 路由选路 → 发请求
  → 401 先续期、仍失败换号；429 冷却后换号；5xx 退避换号；413 不换号直接报错
```

## 三、验证与 CI 记录

最近一次全绿（提交 **a683271**，2026-11-09，含备份导出与全部协议工作）：

```
build   success
verify  success   （Lint 也过；除既有 core:plugin 债外全绿）
test suites: 144   tests: 1481   failed: 9
```

与上一次全绿相比：**套件 137 → 144、用例 1414 → 1481**，失败数不变（9 条仍是 core:plugin 债）。
新增的 7 个套件全部通过，包括：

```
PASSED  ProviderProtocolsTest              6
PASSED  AnthropicRequestBuilderTest       13
PASSED  AnthropicStreamAssemblerTest      13
PASSED  ResponsesRequestBuilderTest       12
PASSED  ResponsesStreamAssemblerTest      11
PASSED  GatewayTokenBudgetTest             6
PASSED  RoutePlannerBudgetTest             4
PASSED  GatewayRetentionTest               6
PASSED  BackupManifestTest                 6
```

> 注意：只改 `docs/**` 的提交**不触发 CI**（工作流有路径过滤）—— 别把「没跑」当成「漏了」。

历史记录（提交 **58cbd54**，2026-11-08）：

```
build   success   (Build + Locate APK + Upload APK 全过)
verify  success   (Lint 也过；测试步骤除既有的 core:plugin 债外全绿)
test suites: 111   tests: 1160   failed: 9
```

失败的 9 条与历史完全一致，仍全是既有的 core:plugin 债：

```
FAILED  dev.praxis.core.plugin.PluginRepositoryTest:   21 tests, 6 failures
FAILED  dev.praxis.core.plugin.PluginLoadOrderTest:    10 tests, 2 failures
FAILED  dev.praxis.core.plugin.ExtensionSnapshotTest:   9 tests, 1 failures
```

> 因此「路由页 5 例 + 错误映射 6 例」这 11 条**已确认通过**（套件 108→111、用例 1143→1160，
> 失败数不变）。此前交接时 verify 仍在跑，属于未确认状态。

再往后一次全绿（提交 **7b0163c**，2026-11-08，导出功能）：

```
build   success   (:app 与 :feature 都编过 —— 这一步就是 UI 接线的编译验证)
verify  success
test suites: 113   tests: 1175   failed: 9
```

较 58cbd54：套件 +2、用例 +15（`GatewayExportPlanTest` 10 条 + `GatewayExportWriterTest` 5 条），
失败数仍是 9（同一批 core:plugin 债）。

端侧生图内核（P0a/P1）落地后（提交 **4d61367**，2026-11-08）：

```
build   success   (新增 core:imagegen 模块 + :app 的探活/客户端/工具都编过)
verify  success
test suites: 116   tests: 1230   failed: 9
```

较 7b0163c：套件 +3、用例 +55 —— 正好是 `core:imagegen` 的三套（18+17+20）。

总开关落地后（提交 **87328ae**，2026-11-08）：

```
build   success
verify  success
test suites: 117   tests: 1234   failed: 9
```

较 4d61367：套件 +1、用例 +4（`GatewayGateTest`），CI 上逐条 PASSED。

三类错误产出点落地后（提交 **dadaff5**，2026-11-09）：

```
build   success
verify  success
test suites: 118   tests: 1247   failed: 9
```

较 87328ae：套件 +1、用例 +13（`UpstreamErrorClassifierTest`，CI 上 13 条 PASSED）；
`OpenAiCompatProviderNonStreamingTest` 5 条在 `:app` per-class 步骤执行、无 FAILED。

流式拦截接线后（提交 **c7a10e5**，2026-11-09）：

```
build   success
verify  success
test suites: 118   tests: 1252   failed: 9
```

较 dadaff5：用例 +5（`OpenAiStreamAssemblerTest` 的流式拦截五条），套件数不变、失败数不变。
`GenerateImageToolTest`（10 条）在「Unit tests (:app, per class)」那步执行并通过
（10 条 STARTED 之后直接 BUILD SUCCESSFUL、无 FAILED），但它**不进汇总行** ——
因为「Summarize JVM test results」排在 per-class 那两步之前（既有管线顺序，非本次引入）。

| | 网关动工前 | 现在 | 差 |
|---|---|---|---|
| 套件 | 87 | 108 | +21 |
| 用例 | 945 | 1143 | **+198** |
| 失败 | 9 | 9 | 不变 |

失败的 9 条全是既有的 core:plugin 债（PluginRepositoryTest 6 / PluginLoadOrderTest 2 / ExtensionSnapshotTest 1），与网关无关。

新增的 198 条里，约 187 条是网关相关的纯 JVM 测试（core:gateway 167 + core:data 20），其余是配置档案相关的少量用例。它们都能在无设备环境下验证。

**这些用例此前从未在本地跑过**：本机 Gradle 起不来（内存不足），一律由 CI 执行。

## 四、已实现（按文档章节）

| 文档 | 实现 | 用例 |
|---|---|---|
| 6.1 凭证形态 | GatewayCredential + 编解码 | 7 |
| 6.3 模型条目 | ModelEntry + 老配置无损迁移 + gateway_model 表 | 8 + 6 |
| 6.5 运行期状态 | CooldownKey / RouteState（退避/垫底/熔断/限额，时钟可注入） | 13 |
| 7.1 候选链 | CandidateChain（含大小写不敏感与遮蔽检测） | 16 |
| 7.2 过滤排序 | RoutePlanner（含家级 RPM） | 18 |
| 7.3 粘性 | ConversationKey | 7 |
| 7.4 失败动作 | FailurePolicy + FailoverLoop | 10 + 13 |
| 7.5 自动续写 | AutoContinuePolicy | 10 |
| 7 全串起来 | RouterSession（状态机） | 11 |
| 8.2/8.3/8.4/8.5 | 账号文件、modelRules、config 片段三条线双向可逆 + 导入计划 | 27 |
| 9.0 三态灯 | GatewayAccountStatus + GatewayStatusText | 10 + 10 |
| 9.3 账号页 | MiuixDraggableList + MiuixBadge + 多选批量（清空运行状态） | — |
| 9.7 导入页 | 从剪贴板导入（账号文件与 modelRules 按内容分流） | — |
| 10 接线 | GatewayModule(Koin) + SettingsBackedProvider 按 kind 分派 | — |

## 五、明确未实现（不是忘了，是还没到）

1. **内置家（21 家）**：目前只接了 **2 家**（官方 `anthropic` / `openai`，见 `BuiltinProviders`）。
   其余 19 家的地址、协议、模型清单要在 P5 的 Rust 内核里一起给 —— 我不凭印象写地址，
   因为写错的表现是「配了账号却一直 404/401」，而用户会以为是自己填错了 key。

   **协议面已就绪**（这是与 P5 解耦的那一半）：

   | 协议 | 出站编码 | 入站组装 | provider | 分派 |
   |---|---|---|---|---|
   | OpenAI Chat | 已有 | 已有 | 已有 | 已有 |
   | Anthropic Messages | `AnthropicRequestBuilder`（13 例） | `AnthropicStreamAssembler`（13 例） | `AnthropicProvider` | ✅ |
   | OpenAI Responses | `ResponsesRequestBuilder`（12 例） | `ResponsesStreamAssembler`（11 例） | `ResponsesProvider` | ✅ |
   | Images | 未做（P4） | — | — | — |

   Responses 走的是自定义家 id `responses` / `openai-responses` —— 内置表里没有哪家默认走它
   （官方 OpenAI 走 Chat 更通用）。这条写在 `ProviderProtocol` 的 KDoc 里。
2. ~~导出~~ **已完成**（提交 7b0163c）：`GatewayExportPlanner`（计划）+ `GatewayExportWriter`（落盘）
   + 导入导出页两个入口（仅配置 / 含凭证，后者二次确认）+ `:app` 接线，CI 编译通过、15 条单测全绿。
   **设备端未复验**：还没在真机上点过「导出」、确认文件真的出现在
   `Android/data/dev.praxis/files/exports/accounts/` 下。
   两处已知缺口由计划层如实写在 notes 里：路由/重试/超时 Praxis 尚未持久化、`apiKeys` 未包含（P6）
3. **添加账号三级流程 + H5 容器**：目前只能靠粘贴导入，不能手动新建
4. **模型页自动填网关模型清单**：**机制已接通**（本轮）：模型页候选现在由两个来源合并 ——
   结构化条目（`gateway_model` 表，带启停与能力位）+ 各家声明与映射别名（`GatewayModelCatalog`）。
   合并规则在 `core:gateway/GatewayModelOptions`（5 例单测：结构化优先、大小写不敏感去重）。
   **仍缺数据源**：`gateway_model` 表至今没有写入路径，所以眼下候选里只有目录名。
   要真正用上结构化条目，需要「模型管理页」（手动增删改 + 逐条启停）或等 P5 的内置家清单。
5. ~~路由页 / 模型管理页~~ **已落地**：
   - 候选队列可视化（位次 · 账号 · 可用/跳过原因）—— 早已有；
   - **策略区**（提交 2bed3ac）：顺序 / 轮询 + 粘性开关，真正接进 `RoutePlanner` /
     `RouterSession` / `GatewayProvider` 与队列预测（11 例单测）；
   - **容错区**（本轮）：熔断四参（开关 / 阈值 / 冷却秒 / 恢复次数）持久化并接到 `RouteState`，
     用 `updateBreakerConfig` 而不是重建 —— 重建会连带清空当前冷却/熔断运行态。
   - 模型管理页（提交 2bed3ac 之前）：`gateway_model` 的写入口（添加 / 逐条启停 / 删除）。
6. ~~gateway_enabled 总开关~~ **已完成**（提交 87328ae）：`GatewayGate`（纯函数，4 例单测）
   决定「非网关档案不受影响 / 网关档案 + 开 = 走网关 / 网关档案 + 关 = GatewayDisabled」，
   概览页顶部有开关（`MiuixSwitchWidget`）。关掉时**明确报错而不回落** ——
   网关档案上没有 baseUrl/apiKey，回落等于拿空地址发请求
7. ~~core:ai 三类错误的产出点~~ **已完成**（提交 dadaff5）：新增 `UpstreamErrorClassifier`
   （13 例单测）—— 正文标记优先、状态码兜底；`finish_reason` 是内容拦截的权威信号。
   接线两处：HTTP 错误路径改用它（429+配额正文 → 额度耗尽；401+过期标记 → 令牌过期；413/上下文超限 → 不换号）；
   非流式 `emitNonStreaming` 在「content_filter 且一个字都没吐」时抛 `CONTENT_BLOCKED`。
   流式 SSE 路径也已接线（提交 **c7a10e5**）：`OpenAiStreamAssembler.blockedWithoutOutput()`
   = 「finish_reason 命中拦截 + 无正文 + 无工具调用」三项同时成立；provider 在 `finish()` 之前判，
   **已吐内容时不算**（收不回来，判成拦截只会让用户丢掉半截回答、换家后从头再来）。
   判定字面量抽成 `core:ai` 的 `ContentFilterReason`，`core:gateway` 的分类器改为引用它。
8. ~~token 预算限流~~ **已完成**（提交 585a300 + 8bca74f）：
   - `core:gateway/GatewayTokenBudget`（滚动窗口，与 `RateLimited` 分开的原因见其 KDoc）+
     `SkipReason.BudgetExhausted`（10 例单测）；
   - 用量按账号聚合（`tokensByAccountSince`，**排除 `accountId` 为空的记录** ——
     算到谁头上都会静默让某个账号提前被限流）；
   - `GatewayProvider` 每次请求前查一次并 `session.setBudget(...)`（不缓存：缓存会让预算晚一步生效）；
   - 路由页「token 预算」区 + 弹窗（0 = 不限，默认关）。

   **窗口取滚动 N 小时而不是自然日**：自然日作为限流有突刺（23:59 用爆的人 00:01 又能用满）。
   报表按自然日（展示）、限流按滚动窗口（保护）—— 口径不同是**有意**的。
9. ~~请求记录与报表~~ **已完成**（提交 84384bf + 925110c + 849b682 + 9e5382f）：
   - v13→v14 新增 `gateway_request`（17 列）+ `gateway_daily`（7 列），迁移**全程手写**
     （`AutoMigration(11,13)` 在版本升到 14 后会要求 `13.json`，见迁移 KDoc）；
   - usage 解析：`AiStreamEvent.Usage` + `OpenAiUsage`（7 例单测，认三种缓存字段形状）+
     `stream_options.include_usage`；
   - 落库：成功记用量、失败**清零**（文档 §5.7）、用户取消**不记**；
   - 报表页：今日 / 近 7 天 / 近 30 天 + 按模型排行（文档要求「不做图表」）。
   - **`gateway_daily` 至今没有写入方**：日报表直接查明细聚合（几千行毫秒级），
     日聚合表等真有性能问题再启用。
   - ⚠️ **v13→v14 迁移尚未真机验证**（迁移正确性只有真机切换才暴露）。
10. ~~P3 剩余：日志页的来源筛选~~ **已完成**（提交 76724b0 + fde3d9d）：
    `LogScreen` 加「来源」下拉（App 日志 / 请求记录），请求记录走 Room 的
    `recentRequests`。两处刻意口径差异：
    - 文档原本要四档「全部 / App / 网关 / 请求记录」，Praxis 里「网关」那两档是同一份数据
      （网关不写文本日志，请求记录就是它的结构化视图），所以**如实只做两档**；
    - 报表**排除** `isTest`，请求记录视图**包含**它 —— 用户点了「测试连接」就该在日志里看到那条。
    - 卡片只显示结构化字段，不显示原始报文（Praxis 没保存请求体/响应体）。
11. **P4 / P5**：协议编码器（Anthropic / Responses）、系统设置、代理池、云同步、备份、
    Rust 内核、签到框架、AGPL 转换。每项的依赖：代理池要 Praxis 先有全局代理设置；
    内置 21 家要等 P5 的 Rust 内核；云同步要有一个后端。

## 六、曾经偏离、已按文档改正

| 项 | 原来 | 现在 |
|---|---|---|
| 分区头 | 用 miuix SmallTitle + Card 代替 SettingsSectionHeader | 把该控件从 feature:settings 提到 ui:widgets 公开面，网关页照文档用 |
| 多选批量 | 未实现（控件是 internal） | 控件提到公开面，账号页按 9.3 实现多选 |

## 七、仍然存在的有意偏离（每条带原因）

| # | 文档要求 | 实际做法 | 原因 |
|---|---|---|---|
| 1 | 10：GatewayAccountStore 放 app/data/gateway | 放 core:data/gateway | KeystoreCipher 与两张表的 DAO 都在 core:data；放 :app 既拿不到可见性，也失去纯 JVM 单测 |
| 2 | 6.5：RouteState 只列读方法与 onFailure | 补了 markSink / markLimit / breakerUntil | 7.4 的「另加垫底」「429 冷却到重置时刻」没有写入口就实现不了；界面要显示「还有多久恢复」 |
| 3 | 6.4：gateway_retry_count（单账号内重试） | 未实现 | 7.4 每一类失败都直接给了动作，没有一类写着先原地重试一次 |
| 4 | 7.2 第 4 行把 sink 列在冷却过滤器里 | 按软降级（仍可用、排最后） | 7.3 又要求 sink 排到队尾；若在过滤阶段滤掉，那句排序规则就没有作用对象 |
| 5 | 8.4 的键名在熔断/压缩两组只写了前后缀 | 按「组前缀 + 后缀」拼全名 | 真实键名不同时会出现在导入报告的 unknownKeys 里，不会静默丢失 |
| 6 | KeystoreCipher 既有约定：加密失败存明文 | 网关凭证加密失败则整次导入失败 | 那正是兜底式设计：用户以为加密了，实际明文在库里 |

## 八、已知风险

1. **Room 迁移只验证到编译期**：v11 到 v13 一步自动迁移，但真正的 schema 校验发生在打开数据库时。需要一台装过旧版本的设备启动一次
2. **内置家不可用**：任何内置家的账号都会被跳过，用户会看到「导入完成但什么也没进来」。导入页与报告里都写了理由，但这是真实的功能缺口
3. **CI 的 observation 步骤是 continue-on-error**：测试挂了任务仍可能显示绿色。必须读汇总行（失败数），不能只看任务颜色。曾因此漏看一条真实失败
5. **汇总行不覆盖 `:app` 与 `:feature:chat`**：`Summarize JVM test results` 排在 per-class 两步之前，
   所以汇总只统计 library + JVM 模块。那两批测试的通过与否要看各自步骤的 `BUILD SUCCESSFUL` 与有没有
   `::error::` 行 —— 单看汇总行会低估覆盖（4d61367 时汇总 1230，实际另有 10 条工具测试已跑过）。

4. **CI 的测试是显式清单，不是「跑全部」**（2026-11-08 踩到）：
   `Unit tests (JVM modules)` 逐个列 `:core:X:test`，`:app` 那步是**类名白名单**。
   新模块 / 新测试类不写进去就**永远不会执行**，而任务照样绿色、汇总行数字纹丝不动。
   实测：`core:imagegen`（55 例）与 `GenerateImageToolTest`（10 例）首次提交后 CI 全绿，
   但汇总行仍是 `113 / 1175 / 9` —— 一条都没跑。已把它们登记进 `.github/workflows/build.yml`。

## 九、下一步建议

1. **Responses 编码器**（四类入口里的第三个，P3 剩余）
2. 代理池 / 云同步 / 备份（P4）—— 前两者各有一处前置依赖，见 §五-11
3. Rust 内核 + 剩下 19 家（P5）
4. 生图侧剩余：每步预览（要引擎推帧）、代理（要全局代理设置）、形态 B/C 识别

## 十、尚未在真机上验证的部分（**这一节最重要**）

CI 只证明「编译通过 + 单测通过」。下面这些**一次都没在真实设备上跑过**，按「出问题的概率」排序：

| 项 | 为什么只有真机能验 |
|---|---|
| **数据库迁移 v13 → v14** | Room 打开库时按 identity hash 校验。上次 `12 → 13` 的手写迁移漏配，就是真机启动 3 秒后崩溃才发现的 |
| Anthropic 全链路 | 编码器/组装器/provider/分派都有单测，但**从未对真实上游发过请求**。最可能的偏差是真实响应里 `content_block_start` 的 `tool_use.input` 初值形态 |
| 生图自动存相册 | MediaStore 的 `IS_PENDING` 收尾与 `RELATIVE_PATH` 只在真机生效 |
| 生图并发下载 | 信号量 + 多 `.part` 同时写 + 失败 abort，全是 IO 行为 |
| 请求记录 → 报表页 | 从未在真机上写入过一条记录，也没看过报表渲染 |
| 日志页「请求记录」视图 | 新加的分支，从未渲染过 |
| 所有新设置页 | 策略 / 容错 / 预算 / 下载源 / 画布采样 / 输出 / 诊断 —— 都只过了编译 |

**怎么验**（保留数据升级，不要卸载）：

```bash
adb install -r app-debug.apk
adb shell run-as dev.praxis.debug sqlite3 databases/praxis.db "PRAGMA user_version;"
```

期望：`user_version` 从 13 变 14、进程存活、无 `A migration from 13 to 14 was required`。

> 2026-11 期间每轮汇报里都提过这条；它至今**没有被验证过**，因为验证它需要用户的设备许可。

## 十一、不能在本仓库内完成的项（及解锁条件）

这一节是为了让「未完成」有明确边界 —— 下面每一条都**不是待办**，而是需要外部条件。

| 项 | 卡在哪 | 解锁条件 | 谁能解锁 |
|---|---|---|---|
| **Rust 内核 + 19 家上游** | 要在 Rust + NDK 里实现协议与模型清单，并随包发 `.so` | ① 交叉编译环境（cargo-ndk + NDK r27）② **AGPL-3.0 许可决策**（文档 §5.14.5 八条） | 许可决策**只能由作者/贡献者做** |
| **代理池** | OkHttp `Proxy` 与每账号绑定都依赖「全局代理设置」 | Praxis 先有全局代理设置 | 仓库内可做，但需先设计全局代理 |
| **云同步（WebDAV / S3）** | 需要凭据模型、冲突策略、内容指纹 | 一个可测的后端凭据 + 冲突规则 | 需用户提供后端 |
| **生图每步预览** | 要引擎在采样中推中间帧 + 缩略图管线 | 引擎支持推帧（形态 A 下引擎不由 Praxis 启动） | 引擎侧 |
| **生图形态 B/C 识别** | 形态 B/C 是「Praxis 自己起引擎（容器内/随包）」 | 先有 B/C 的启动实现 | 仓库内，但工作量大 |
| **生图性能分区（预热/后端选择）** | 形态 A 下启动参数由外部 App 决定 | 同上（形态 B/C） | 引擎侧 |
| **诊断的引擎日志/启动耗时** | 日志在引擎那边 | 同上 | 引擎侧 |
| **内置家 19 家的地址与模型清单** | 不凭印象写（写错的表现是 404/401，用户会以为是自己填错了 key） | P5 的清单，或用户自己填「自定义家」 | 渠道 / 用户 |

**说明**：前四轮里我多次把「生图性能分区」写进待办，后来核对文档才发现 —— 形态 A 下它
**不是没做，而是没有东西可调**。这类"看起来是待办、实际是前置缺失"的项，本节的表就是
把它们一次性钉死，避免下一个人再花时间确认一遍。
