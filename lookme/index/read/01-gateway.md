# 01 · core:gateway + feature:gateway 通读与审计

> 审计者：agent-2（只读+写文档）。审计时间：2026-11-16。基线：C:/creat-agent/main/Praxis。
> 读法：第 2 节 = 这模块到底是什么；第 4 节 = 一次请求的完整数据流；第 5 节 = 问题清单（G 编号）；第 6/7 节 = 测试与文档对照。

## 1. 范围与文件清单（数量核对）

| 范围 | 目录里实际数量 | 我读完的数量 | 行数 |
|---|---|---|---|
| core/gateway/src/main/kotlin/dev/praxis/core/gateway | 49 个 .kt | 49 | 4985 |
| core/gateway/src/test/kotlin/dev/praxis/core/gateway | 48 个 .kt | 48 | 5076 |
| feature/gateway/src/main/java/dev/praxis/feature/gateway | 11 个 .kt | 11 | 3747 |
| core/gateway/build.gradle.kts | 1 | 1 | 18 |
| feature/gateway/build.gradle.kts | 1 | 1 | 27 |
| **合计** | **110** | **110（100%）** | **13808 行 .kt** |

- core/gateway/src 下共 97 个文件，**全部是 .kt**（无其它资源文件）；feature/gateway/src 下只有 `main`，**没有任何测试目录**。
- 测试方法：48 个测试类共 **396 个 @Test**。
- 逐个读完，不是抽样；分批读的顺序就是下表顺序。

为了回答「数据流」和「内置家能不能选出模型」，另**只读**了下列文件（不在交付范围，但证据要用到）：
`docs/PORTING-GATEWAY.md`（§5.1~§5.9、§5.4.1、§6、§7、§8、§9.1~§9.7、§11~§13）、
`app/src/main/java/dev/praxis/data/gateway/GatewayProvider.kt`、`BuiltinProviders.kt`、`GatewayModelStore.kt`、`GatewayUsageStore.kt`、`ProxyResolver.kt`、
`app/src/main/java/dev/praxis/ui/PraxisApp.kt`（相关行）、`app/src/main/java/dev/praxis/di/AppModule.kt`（RouteState 构造）、
`core/data/src/main/kotlin/dev/praxis/core/data/gateway/*`（GatewayAccountStore / GatewayUsageEntities / GatewayAccountMapper / GatewayModelEntity）、`core/data/.../settings/SettingsRepository.kt`（网关键）。

## 2. 模块职责（一段话）

**core:gateway 是一个纯 JVM（无 Android、无 Room、无网络）的「网关领域层」**：它把 Agent2API 网关的选路规则、失败分类、冷却/熔断运行期状态、模型与映射规则、导入导出格式、以及全部设置项的编解码，做成可单测的纯函数/纯数据类；**它自己不发请求、不落库、不画界面**。
真实链路是：`:app` 的 `GatewayProvider` 负责取账号、解凭证、按协议发流（复用 `OpenAiCompatProvider`/`AnthropicProvider`/`ResponsesProvider`），选谁先谁后由 core 的 `CandidateChain` + `RouterSession`/`RoutePlanner`/`RouteState` 决定；**feature:gateway 是 11 个纯 Compose 页面**，只消费 core 的类型与回调，本身不含业务规则（唯一例外是两处字符串格式化函数）。
一句话评价：**领域层写得很扎实、注释与单测密度都高；问题几乎全在「接线」上 —— core 里做对了的东西有相当一部分在生产路径上没人调用（G2/G3/G5/G6/G7/G15）。**

## 3. 逐文件一表

路径前缀约定：`main/` = core/gateway/src/main/kotlin/dev/praxis/core/gateway/；`test/` = core/gateway/src/test/kotlin/dev/praxis/core/gateway/；`feature/` = feature/gateway/src/main/java/dev/praxis/feature/gateway/。

### 3.1 core:gateway 主源（49）

| 文件 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| main/AiErrorMapping.kt | core:ai 的 AiErrorKind → 文档 7.4 的 FailureKind | Extension 「AiErrorKind.toFailureKind()」 | CANCELLED 也映射成 UpstreamError（靠调用方先原样抛）；RATE_LIMIT 与 QUOTA_LIMITED 合并成一类，丢掉了「限速 vs 额度耗尽」的区分（冷却来源在 RouteState 仍可区分） |
| main/AutoContinuePolicy.kt | 自动续写决策（开关/轮数/信号） | AutoContinueConfig / FinishSignal / AutoContinueDecision / AutoContinuePolicy.decide | 只被 feature:chat 使用（ChatViewModel.kt:2576）；与路由层无耦合，符合文档 7.5 |
| main/BackupManifest.kt | 整库备份清单编解码 + 恢复版本判定 | BackupManifest / BackupManifestCodec / RestoreVerdict | canRestore 有单测，但**恢复流程未实现**（feature/GatewayRoute.kt:52-57 只导出）；清单里没有 credentialsCipher 的「换设备解不开」提示字段（只在注释里） |
| main/BuiltinModelsCodec.kt | 内置家模型清单（providerId → [模型名]）JSON 编解码 + 合并 | BuiltinModelsCodec.encode/decode/merge | 只存名字：**没有 fetchedAt、没有 enabled、没有能力位**；merge 会排序去重（丢上游顺序）；消费点只在 :app（见 G1/G15） |
| main/CandidateChain.kt | 候选链生成：组 > 映射 > 原生，全链路忽略大小写；遮蔽检测 | ModelCandidate / NativeModel / ModelMapping / CandidateChainResult / CandidateChain.of / ofEntries / detectCaseCollisions | **ofEntries（带 enabled 过滤）生产零调用** → 结构化条目的启停不参与选路（G2）；detectCaseCollisions 生产零调用（只在测试里） |
| main/ConversationKey.kt | 会话粘性键：显式头优先，否则 FNV-1a 内容哈希 | ConversationKey.of / THREAD_ID_HEADER | **生产零调用**（含 AH-Thread-Id 头无人读取）→ 粘性机制空转（G6） |
| main/CooldownKey.kt | 冷却键类型 (accountId, wireModel) | CooldownKey | 无。设计正确（文档 6.5 的 #200 教训） |
| main/FailoverLoop.kt | 换号循环的纯决策：换号/续期/放弃 | FailoverBudget / FailoverState / FailoverStep / FailoverLoop.onFailure/advance | RefreshCredentials 这个 step **没有执行者**（G5）；单账号内重试（gateway_retry_count）明确未做 |
| main/FailurePolicy.kt | 失败分类 → 动作 + 冷却时长 | FailureKind / FailoverAction / FailureDecision / FailurePolicy.actionFor/decide | decide() **生产零调用**（冷却时长换算无人用）；与 RouteState 各有一份「429 兜底 10 分钟」语义（G7） |
| main/FinishSignalRules.kt | finish_reason → FinishSignal（只认 length 族） | FinishSignalRules.of | 无。被 feature:chat 使用（ChatViewModel.kt:2547） |
| main/GatewayAccountDraft.kt | 添加账号草稿的校验/归一化 + 人话错误 | GatewayAccountDraft / GatewayAccountDraftRules.validate/normalize/isHttpUrl / GatewayAccountDraftError | **hasDefaultBaseUrl 恒 false** → 内置家也被强制手填地址（地址由提供商列表预填，见 GatewayProviderChoice；校验本身不认「内置家可无地址」） |
| main/GatewayAccountFile.kt | 网关账号文件形状（§8.2） | ACCOUNT_FORMAT / GatewayAccountFile / GatewayCustomProvider | 导出时 app 侧构造的 GatewayAccountFile **不带 customProviders**（PraxisApp.kt:2296-2330）→ 回灌丢自定义家（G9） |
| main/GatewayAccountRow.kt | 账号列表行模型（id/名字/家名/状态） | GatewayAccountRow.from | 无 |
| main/GatewayAccountStatus.kt | 三态（正常/冷却/停用）推导，部分模型受限单独列 | GatewayAccountState / GatewayCooldownReason / GatewayAccountStatus / GatewayAccountStatusResolver.resolve | 判定顺序正确（停用>退避>熔断>限额）；但调用方给的 wireModels 常常是空（见 G4），「部分受限」在界面上基本不会出现 |
| main/GatewayConfigExporter.kt | 路由配置 → 网关原生 camelCase config 片段（只写非空键） | GatewayConfigExporter.encode | 无。往返有测（GatewayConfigRoundTripTest） |
| main/GatewayConfigImporter.kt | 网关 config.json → GatewayRoutingConfig（A/C 两类 + invalid/unknown 分类） | GatewayRoutingConfig / GatewayConfigImportResult / GatewayConfigImporter.parse | B 类键（promptMode/modelsDev/clientIdentification/retention*）未映射，全落 unknownKeys；「保留天数」用的是 Praxis 自己的键（见 G10 的说明） |
| main/GatewayCredential.kt | 凭证 sealed（ApiKey/OAuth）+ JSON 编解码 + 账号文件→凭证 | GatewayCredential / GatewayCredentialCodec / GatewayAccountFile.toCredential | OAuth 只建模不消费（导入被跳过、发送只要 ApiKey：GatewayProvider.kt:253 直接 `continue`） |
| main/GatewayDailyKey.kt | 日聚合键（本地时区 yyyy-MM-dd） | GatewayDailyKey.of / daysBefore | **只有注释引用**：gateway_daily 表是死表，upsertDaily/dailySince 无调用者（G8） |
| main/GatewayExporter.kt | 账号文件导出（含凭证/打码）+ 文件名规则 | GatewayExporter.accountFile / accountFileName / REDACTED | 同 GatewayAccountFile：customProviders 不会被填 |
| main/GatewayExportPlan.kt | 导出计划编排（accounts/*.json + gateway-config.json + notes） | GatewayExportFile / GatewayExportPlan / GatewayExportPlanner.build | 不产出文档 §8.5 的第三件产物 praxis-gateway-backup.json（只在 notes 里承认）；不产出 customProviders（G9） |
| main/GatewayExportWriter.kt | 把计划落盘（含路径越界防护） | GatewayExportResult / GatewayExportWriter.write | 无。真读写有测（临时目录） |
| main/GatewayGate.kt | 总开关三态（SingleProfile/Gateway/GatewayDisabled） | OutboundRoute / GatewayGate.decide | 无。被 SettingsBackedProvider.kt:85 消费 |
| main/GatewayImporter.kt | 账号文件/批量形状解析 + 预检报告 + 导入计划 | GatewayImportPlan / ParsedExport / GatewayImporter.parse/parseAll/prepare/report | 不校验 baseUrl 合法性；custom 家无地址时账号仍进 accepted（G9 的第二个面） |
| main/GatewayImportReport.kt | 四段报告（新增/更新/跳过/失败） | GatewayImportReport / Item / total | 无 |
| main/GatewayLoginMethods.kt | 登录方式生成（直填 Key / 打开登录页）+ 从粘贴内容抽凭据 | GatewayLoginType / GatewayLoginMethod / GatewayLoginMethods.forProvider/extractCredential | extractCredential 用 indexOf 扫 api_key=/code=/token=，对任意文本都可能误命中（低危，未确认上游真实回调形态） |
| main/GatewayModelCatalog.kt | 对外模型清单（原生 + 映射别名，大小写不敏感去重） | GatewayModelCatalog.visibleNames | 被 PraxisApp.kt:340 使用，但入参 nativeModels **不含内置家**（G1） |
| main/GatewayModelCatalogCache.kt | 快照新鲜度 + 时间文案 + 写不写（失败保留旧清单） | GatewayModelCatalogCache.isFresh/ageText/decideWrite/DEFAULT_TTL_MS | 只对「自定义家」成立：modelsFetchedAt 是 gateway_provider 的列；内置家没有时间戳（G15） |
| main/GatewayModelOptions.kt | 结构化条目 + 目录名合并（结构化优先） | GatewayModelOptions.merge | 无 |
| main/GatewayModelRow.kt | 模型管理页行模型 + 新增条目默认值常量 | GatewayModelRow / GatewayModelEntryDefaults | 能力位全 null 是刻意的；但 gateway_model 表里有 supportsImages 列，实际永远写 null（GatewayModelMapper 无写入点） |
| main/GatewayModelRules.kt | modelRules 五段（disabled/mappings/custom/capabilities/seeded）+ codec | GatewayModelRules / GatewayModelRulesCodec / GatewayModelCapability.supportsImages | 生产只消费 mappings（toRoutingMappings）；disabled / custom / capabilities / seeded **解析后无人读**（GatewayModelRules.isDisabled 零生产调用）→ 文档 §5.4 的「逐模型启停/能力位覆盖」没落地（G2/G3） |
| main/GatewayProviderDraft.kt | 自定义家草稿校验/归一化 | GatewayProviderDraft / GatewayProviderDraftRules / BUILTIN_IDS | BUILTIN_IDS 只有 {anthropic, openai}，而 app 内置表有 20+ 家 → 可建出与内置家同 id 的自定义家（G11） |
| main/GatewayQueue.kt | RoutePlan → 一条队列（位次/跳过原因/垫底）+ 文案 | GatewayQueueEntry / GatewayQueue.of/reasonText | 无 |
| main/GatewayReport.kt | 报表窗口/汇总/格式化 + 请求记录视图模型 | GatewayReportWindow / GatewayUsageSummary / GatewayUsageBucket / GatewayReportFormat / GatewayRequestRecord | GatewayReportWindow（days 语义）**生产未使用**：app 里另写 1/7/30（G8） |
| main/GatewayRetention.kt | 请求记录保留期切点/解析 | GatewayRetention.cutoffMillis/parseDays | 无。PraxisApp.kt:735 启动清理在用 |
| main/GatewayRouteGroup.kt | 路由组（对外名 → 跨家成员）+ 校验 + codec | GatewayRouteGroup / RouteGroupMember / GatewayRouteGroupRules / GatewayRouteGroupCodec | 无。CandidateChain 里「组优先于映射」有测 |
| main/GatewayRoutingPolicy.kt | 策略（顺序/轮询）+ 粘性 + 熔断四参 + token 预算 + codec | GatewayRoutingPolicy / RoutingStrategy / GatewayRoutingPolicyCodec / toBreakerConfig/toTokenBudget | stickyEnabled 默认 true 但会话粘性没接线（G6）；breakerSuccessThreshold 只记录不自动恢复（KDoc 自认） |
| main/GatewaySettingsExport.kt | Praxis 设置 → 网关 config 片段（与默认相同就不写） | GatewaySettingsExport.toRoutingConfig / strategyToken / stickyToken | 无。往返有测（GatewaySettingsExportTest） |
| main/GatewayStatusText.kt | 状态灯文案/冷却原因/倒计时（向上取整） | GatewayStatusText.label/reasonText/countdownText/limitedModelsText/sinkText | 无 |
| main/GatewaySystemSettings.kt | 系统设置（超时/换号上限/debug/排队/自动续写/图片压缩）+ codec | GatewaySystemSettings / GatewaySystemSettingsRules / GatewaySystemSettingsCodec | 类 KDoc 说「只放三项」，实际已放六组（文档漂移，代码是新的） |
| main/GatewayTokenBudget.kt | token 预算（滚动窗口）判定 | GatewayTokenBudget.isExhausted / windowStartMillis / GatewayTokenBudgetRules.validate | validate 生产零调用（UI 用滑块兜住）；「每家预算」未实现（文档 §7.2 第 6 行只做了每账号） |
| main/GatewayUsageDraft.kt | 请求记录草稿 + 失败清零规则 | GatewayUsageDraft / GatewayUsageRules.build/tokensFor/STATUS_* | 无 |
| main/ModelEntry.kt | 结构化模型条目（三态能力位）+ 老模型池迁移 | ModelEntry / ModelKind / ModelSource / ModelEntryMigration.Migrate | **ModelEntryMigration 生产零调用**；ModelEntry 也不进选路（G2） |
| main/ProviderProtocols.kt | 家 id → 协议（OpenAI/Anthropic/Responses）+ 鉴权头 + 路径 | ProviderProtocol / ProviderProtocols.of/authHeaders/path | 生产实际用 app 的 BuiltinProviders.protocol 优先（GatewayProvider.kt:268）；本文件的 of() 只剩按 id 猜的兜底角色，且 ANTHROPIC_HOSTS 只有 anthropic/claude，与内置表 20+ 家不一致 |
| main/ProxySettings.kt | 代理节点 + 账号级绑定 + 校验 + codec | ProxyNode / ProxySettings / ProxyRules.egressFor/pruneBinding / ProxySettingsCodec | 只做「该走哪个出口」的判定；真正的 OkHttp Proxy/Authenticator 在 app 的 ProxyResolver（不在本模块，符合分层） |
| main/RoutePlanner.kt | 过滤（8 类理由）+ 排序（priority/组内/轮询/粘性/垫底）纯函数 | AccountView / ProviderUsage / SkipReason / PlannedAttempt / SkippedAttempt / RoutePlan / RoutePlanner.plan | 三条过滤在生产恒不生效：supportsImages 恒 null、runningConcurrent/rpm 恒 0、providerUsages 恒空（G3/G4） |
| main/RouteReplayLog.kt | 最近 50 条路由决策的环形缓冲（内存） | RouteReplayLog / RouteReplayEntry | 无。app 侧 record 只记「成功」与「异常」两种，不记「换号中途失败」的中间态 |
| main/RouterSession.kt | 请求路由状态机：next/onSuccess/onFailure/outcome，tried 集合防死循环 | RouteOutcome / RouterSession / needsRelogin / stickyAfterSuccess | 粘性入参无来源（G6）；RefreshCredentials 无执行者、needsRelogin 实际永不置位（G5） |
| main/RouteState.kt | 运行期四表：退避/垫底/熔断/限额（注入时钟） | RouteState / BreakerConfig / markBackoff/markSink/markLimit/onSuccess/onFailure | **markSink 生产零调用** → 「垫底」机制永不触发（G13）；markLimit 的键与 429 语义正确 |
| main/UpstreamErrorClassifier.kt | HTTP 状态码 + 正文标记 + finish_reason → AiErrorKind | UpstreamErrorClassifier.classify | 无。被 OpenAiCompatProvider.kt:152/548、AnthropicProvider.kt:105 消费；中文标记表是本仓库自己加的（好） |

### 3.2 core:gateway 测试（48）—— 覆盖分析见 §6，这里只列「测什么」

| 文件 | 测了什么 | 用例数 |
|---|---|---|
| test/AiErrorMappingTest.kt | AiErrorKind 全量映射 + 未来新增 kind 会红的兜底断言 | 6 |
| test/AutoContinuePolicyTest.kt | 默认关、Disabled 优先、LengthLimit/StreamCut 续写、轮数上限 | 10 |
| test/BackupManifestTest.kt | 文件名可排序、清单往返、坏清单拒绝、版本过新拒绝 | 6 |
| test/BuiltinModelsCodecTest.kt | 往返排序去重、坏内容失败、合并只动一家、空清单保留旧值 | 5 |
| test/CandidateChainTest.kt | 原生/映射/多条映射顺序/默认家/未解析/停用/映射优先/大小写/遮蔽 | 16 |
| test/ConversationKeyTest.kt | 显式头优先、头名大小写、内容哈希稳定、无键返回 null、前缀不撞 | 7 |
| test/FailoverLoopTest.kt | 7.4 六类动作、换号计数、上限放弃、续期只记一次 | 13 |
| test/FailurePolicyTest.kt | 冷却到上游时刻/兜底 10m/不产生负冷却、只有两类冷却账号、CooldownKey 区分 | 10 |
| test/FinishSignalRulesTest.kt | 缺字段不算腰斩、length 族、其它值不猜 | 3 |
| test/GatewayAccountDraftTest.kt | 五类校验错误、归一化去斜杠、名字兜底、hasDefaultBaseUrl 恒 false | 10 |
| test/GatewayAccountRowTest.kt | 行字段、状态来自运行期、停用优先、模型未知不判定 | 4 |
| test/GatewayAccountStatusTest.kt | 三态、停用优先、退避/熔断/限额、部分受限、垫底、到期恢复 | 10 |
| test/GatewayConfigExporterTest.kt | 只写非空键、false 要写、状态码逗号串、键名 camelCase | 7 |
| test/GatewayConfigImporterTest.kt | A 类映射、缺键保持 null、serverOnly/unknown/invalid 分类、appliedKeys | 9 |
| test/GatewayConfigRoundTripTest.kt | 全字段往返逐字段相等、未设置键不写、空列表不写 | 6 |
| test/GatewayCredentialTest.kt | ApiKey/OAuth 判定、空凭证报错、编解码往返、坏数据失败 | 7 |
| test/GatewayDailyKeyTest.kt | 时区差一天、跨零点、N 天回推、跨月跨年 | 5 |
| test/GatewayExportPlanTest.kt | 文件名编号、非法字符净化、打码/-secrets、config 合并、空池/空配置的 notes | 10 |
| test/GatewayExportWriterTest.kt | 嵌套目录真写、根目录创建、越界拒绝、notes 透传、重复导出 | 5 |
| test/GatewayExporterTest.kt | 含凭证往返、仅配置打码+清空令牌、桌面键不回灌、文件名 -secrets | 4 |
| test/GatewayGateTest.kt | 四种组合落到三个产出 | 4 |
| test/GatewayImporterBulkTest.kt | 批量形状解析、OAuth+未知家两理由同时说、缺 id 指出第几条、customProviders 启停过滤 | 6 |
| test/GatewayImporterTest.kt | 四段报告归属、同 id 更新、custom 放行、重复提示、prepare/report 同规则 | 11 |
| test/GatewayLoginMethodsTest.kt | 直填 Key 永远第一、没地址不给登录页、从回调抽 api_key/code/access_token、只取到 & | 8 |
| test/GatewayModelCatalogCacheTest.kt | 从没拉过/新鲜/TTL/时钟回拨/时间文案/失败保留/空清单保留 | 8 |
| test/GatewayModelCatalogTest.kt | 跨家去重、别名进清单、停用映射不进、大小写去重、空名忽略 | 6 |
| test/GatewayModelOptionsTest.kt | 结构化优先、大小写去重保留先出现、空值丢弃 | 5 |
| test/GatewayModelRulesTest.kt | 解析样例、映射转换、不替候选链过滤、seeded 保留、显式默认值保留、能力位三态 | 10 |
| test/GatewayProviderDraftTest.kt | 标识校验、与内置家重名拦、地址前缀、归一化、每种错误有人话 | 9 |
| test/GatewayQueueTest.kt | 位次、跳过排后、垫底保留、空计划、每种原因有文案 | 5 |
| test/GatewayReportFormatTest.kt | 成功率未知/一位小数/100%、token 缩写、汇总行、窗口 | 6 |
| test/GatewayRetentionTest.kt | 切点=保留天数前零点、当天早晚同切点、跨月、夹取、默认 7 天 | 6 |
| test/GatewayRouteGroupTest.kt | 组名命中忽略大小写、非组返回 null、往返、校验六种错、候选链里组优先 | 11 |
| test/GatewayRoutingPolicyTest.kt | 顺序/轮询转位/取模/单账号、粘性开关与 cleared、编解码、熔断四参往返与坏值 | 15 |
| test/GatewaySettingsExportTest.kt | 网关词表、毫秒→秒、各设置落位、未做的留 null、往返、空设置不写键 | 8 |
| test/GatewayStatusTextTest.kt | 三态文案、三种冷却原因、向上取整、不足一分钟、超一小时、受限模型、垫底 | 10 |
| test/GatewaySystemSettingsTest.kt | 默认值=移植前、排队/续写夹取、旧 JSON 缺键、类型错失败、反序列化也归一化 | 14 |
| test/GatewayTokenBudgetTest.kt | 没设=关、达到即用尽、无记录=0、只看自己、窗口折算、校验 | 6 |
| test/GatewayUsageRulesTest.kt | 成功保留、失败清零、清零不动其它、状态码常量 | 4 |
| test/ModelEntryMigrationTest.kt | 等长同序、档案字段、能力位 null、空段保位、不合并、不 trim | 8 |
| test/ProviderProtocolsTest.kt | anthropic/claude→Messages、其余 Chat、responses→Responses、鉴权头/路径不同 | 6 |
| test/ProxySettingsTest.kt | 未开启/未绑定直连、绑定生效、悬空绑定直连、校验、归一化、往返、prune | 12 |
| test/RoutePlannerBudgetTest.kt | 没预算不跳过、用尽跳过并给原因、他账号不受牵连、未达上限可用 | 4 |
| test/RoutePlannerTest.kt | 8 类过滤、停用优先于限流、优先级排序、垫底、粘性命中/回落、家级配额 | 18 |
| test/RouteReplayLogTest.kt | 最近在前、超容量丢旧、容量 1、默认 50、clear、保留跳过原因 | 6 |
| test/RouteStateTest.kt | 退避 15m 起翻倍封顶、到期 null、垫底 30m、限额隔离、熔断阈值/到期/关闭、成功清三类、不清限额 | 13 |
| test/RouterSessionTest.kt | 首选成功记粘性、401 先续期、第二次标 needsRelogin、换号、限额按 (账号,模型)、413 放弃、无候选、上限放弃、粘性、成功后清状态 | 11 |
| test/UpstreamErrorClassifierTest.kt | 429 带/不带配额标记、200+正文配额、finish_reason 拦截、401 过期 vs 密钥错、413、上下文超限、5xx/408、200 提到 content policy 不算、中文配额、同码分流 | 13 |

### 3.3 feature:gateway（11）

| 文件 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| feature/GatewayAccountsScreen.kt | 账号页：拖动改优先级、状态灯、批量多选（仅「清空运行状态」）、旧的添加账号弹窗 | GatewayAccountsScreen(rows,onMove,onOpenAccount,onClearRuntime,onAddAccount?,onTestConnection?,nowMillis) / StatusBadge / AddAccountDialog | 旧弹窗保留但 addVisible 恒 false（:91/226-234）——死代码；空态文案「还没有界面可以添加账号」（:157）与「添加账号」入口（:102-120）自相矛盾；入口描述「内置家清单要等 Rust 内核」（:112）已过时 |
| feature/GatewayAddAccountScreens.kt | 三级流程：① 提供商列表（带搜索）② 登录方式 ③ 直填 Key 弹窗 / 网页授权弹窗 + H5 容器 | GatewayProviderChoice / GatewayAddProviderScreen / GatewayAddLoginScreen / ApiKeyAccountDialog / WebAuthDialog / AddField | `AddField(secret=true)` 的 secret 参数**未使用**（:376-392，无 visualTransformation）→ API Key/密码明文显示（G14）；① 无「自定义家为空」时的建家引导（只有一句文案） |
| feature/GatewayImportScreen.kt | 导入导出页：文件/剪贴板导入、仅配置/含凭证导出、四段报告对话框 | GatewayImportScreen(...) / formatReport(report) | 文档 §9.7 的第三个入口「备份包」未做（页面也没有假按钮，符合铁律）；报告只展示前 10 条明细（:286-289） |
| feature/GatewayModelsScreen.kt | 模型管理页：逐条启停/删除/手动添加/连通性测试 | GatewayModelsScreen(rows,onToggle,onDelete,onAdd,onTest?,error?) / GatewayModelTestReport / AddModelDialog | 没有「拉取模型清单」入口（只能手填，:205 文案自认）；「测试」走 onTest 回调，而 app 侧的 probe 实例没传 builtinModels（G1 连带） |
| feature/GatewayProvidersScreen.kt | 自定义提供商页 + 内置家分区（可拉模型清单并写入） | GatewayProviderRowUi / GatewayBuiltinProviderRowUi / GatewayProviderModelsFetch / GatewayProvidersScreen(...) | 内置家行**没有清单时间/新鲜度**（:153-207），而自定义家有（:229-230）→ 内置家拉过没有、什么时候拉的看不出来（G15）；说明文案「内置家（openai / anthropic）已有地址」（:133-135）与实际 20+ 家不符 |
| feature/GatewayProxyScreen.kt | 代理页：出口节点 CRUD + 每账号绑定 | GatewayProxyScreen(settings,accounts,onChange) / AddProxyNodeDialog / BindProxyDialog | 密码字段明文（ProxyField 无掩码）；删节点用「点整行」而非 ⋯ 菜单（与文档 §9.3 的 ⋯ 菜单约定不同，同页内也反直觉） |
| feature/GatewayReportScreen.kt | 报表页：三段窗口汇总 + 按模型排行 + 记录保留天数 | GatewayReportWindowUi / GatewayReportScreen(...) / RetentionDialog | 没有缓存命中率、没有按家/Key 筛选（文档 §5.7 列了，属未做）；「保留天数」用滑块（1~365） |
| feature/GatewayRoute.kt | 网关概览页（入口导航 + 总开关 + 备份 + 后续阶段清单） | GatewayRoute(accountCount,gatewayEnabled,onBackupDatabase,backupMessage) / PLAN_LINES | PLAN_LINES（:313-319）说「P5 内置 21 家要等 Rust 内核」，但 app 已有 BuiltinProviders 20+ 家（G12）；备份只有导出没有恢复 |
| feature/GatewayRoutingScreen.kt | 路由页：策略/粘性、路由组、回放、熔断四参、token 预算、候选队列 | GatewayRoutingScreen(modelName,entries,accountNames,policy,policyBroken,onPolicyChange,routeGroups,onAdd/RemoveRouteGroup,replayEntriesProvider) / FaultParamsDialog / BudgetDialog / AddRouteGroupDialog | 类 KDoc（:44-52）说策略/容错「还没做」，实际已做（文档漂移）；回放用 replayEntriesProvider() 每次重组现取（无 Flow，能理解但会让重组变重） |
| feature/GatewaySystemScreen.kt | 系统设置页：超时/换号上限/排队/图片压缩/自动续写/脱敏/debug | GatewaySystemScreen(settings,onChange,sanitize,onSanitizeChange) | 类 KDoc（:30-47）说图片压缩/脱敏/自动续写「不放进来」，实际都在（文档漂移）；debug 开关描述「不含对话内容」而 core 的 GatewaySystemSettings KDoc 写「脱敏后的请求体」，措辞不一致 |
| feature/GatewayWidgets.kt | 统一卡片外壳 | GatewayCard(content) | 无 |

### 3.4 构建脚本（2）

| 文件 | 职责 | 关键内容 | 疑点 |
|---|---|---|---|
| core/gateway/build.gradle.kts | 纯 JVM 模块（praxis.kotlin.jvm + kotlinx.serialization） | api(:core:ai)、implementation(kotlinx.serialization.json)、testImplementation(junit) | 无 Android/Room 依赖 —— 与文档 §10 分层一致；也正因此所有 Android 相关（解密、OkHttp、Room）都被推到 :app 与 core:data |
| feature/gateway/build.gradle.kts | Android library + Compose | api(:ui:widgets/:ui:navigation/:ui:design)、implementation(:core:model, :core:gateway)、material-icons-extended | 依赖方向正确（feature 不依赖 core:data/ Room）；**没有 testImplementation**（因为这个模块根本没有测试目录） |

## 4. 关键机制深挖（数据流）

### M1 一次对话请求：从「选提供商」到「吐 SSE」

**入口判定**（哪条链路）：模型档案 `kind = Gateway` 且总开关打开 → `GatewayGate.decide`（core main/GatewayGate.kt:46-51）在 `:app` 的 `SettingsBackedProvider.kt:85-86` 处被调用，产出 `OutboundRoute.Gateway` → 交给 `dev.praxis.data.gateway.GatewayProvider`（:app）。开关关闭时是 `GatewayDisabled`，**明确报错、不回落到单配置**（这是文档 §11 的回滚要求，已实现）。

**逐段数据流**（行号指 `app/.../data/gateway/GatewayProvider.kt`，除注明外）：

1. **候选链**（:176-186）：`CandidateChain.of(requestedModel, nativeModels = store.nativeModels() + builtinModels(), mappings = modelRules.toRoutingMappings(), defaultProvider = null, groups = groups())`。顺序是「路由组 > 映射 > 原生」；都忽略大小写。空链直接抛 `INVALID_REQUEST`「模型 X 没有对应任何上游」（:188-196）。
2. **账号快照**（:199-200）：`store.routingViews()`（每账号解密一次判 hasCredentials）+ `store.providers()`（自定义家的地址/额外头/urlSuffix）。
3. **建状态机**（:202-210）：`RouterSession(candidates, accounts = { views }, state = RouteState, needsImages, budget = FailoverBudget(maxSwitches), policy)`；`needsImages = messages.any { images.isNotEmpty() }`。
4. **预算注入**（:215-219）：只有 `policy().toTokenBudget().enabled` 时才 `setBudget(budget, accountSpend(windowStart))`（用量来自 gateway_request 聚合）。
5. **循环取下一个组合**（:247-248）：`session.next()` → `RoutePlanner.plan()`（过滤+排序）→ 跳过已 tried 的 (账号,上游模型) → 返回第一个。
6. **解出这一次要发的请求**（:251-315）：账号视图 → `store.credentialOf` 解凭证（只要 ApiKey，:253 其它类型直接 continue）→ 地址三级优先 `baseUrlOverride > gateway_provider.baseUrl > providerBaseUrl(内置家表)`（:254-258）→ 协议 `BuiltinProviders.protocol(providerId)`（:268，**不是** core 的 `ProviderProtocols.of`）→ 出口 `clientFor(accountId)`（:271，ProxyResolver 一处解析）→ quirks 头 / urlSuffix（:275-287）→ 按协议 new 出 `OpenAiCompatProvider` / `AnthropicProvider` / `ResponsesProvider`（:288-315）。
7. **发流**（:318-323）：`provider.stream(request.copy(model = attempt.wireModel)).collect { emit(it) }`，**首个事件一发出就把 emitted 置 true** —— 之后不再换号（:346）。**SSE 的解析不在 core:gateway**，而是 core:ai + data:ai 的 provider（OpenAiStreamAssembler / AnthropicStreamAssembler）。
8. **成功**（:324-335）：`session.onSuccess(attempt)` → `state.onSuccess`（清退避/垫底/熔断计数）→ 记一条 usage（status 200）→ 记一条回放（「成功」）→ return。
9. **失败**（:336-347）：「CANCELLED 原样抛；否则 `session.onFailure(attempt, kind = e.error.kind.toFailureKind(), quotaResetAtMillis = e.error.retryAfterMillis)`；已 emitted 则直接抛（不换号）」。
10. **试完所有候选**（:350-355）：抛 `NETWORK`「网关没有可用的账号（这次请求已经试过所有候选）」。
11. **兜底 catch**（:356-390）：非 CANCELLED 都记一条失败记录（status = `STATUS_NO_RESPONSE` = 0）+ 记一条回放（从 `session.outcome()` 取 NoCandidate/Exhausted/GaveUp 的文案）。

**谁负责失败切换**：分工是三层的，不是一处 ——

| 层 | 类型 | 干什么 |
|---|---|---|
| 决策 | `FailoverLoop.onFailure`（core main/FailoverLoop.kt:55-98） | 按 `FailurePolicy.actionFor(kind)` 回答「换号 / 续期 / 放弃」；`FailoverBudget.maxSwitches` 封顶 |
| 施加与记账 | `RouterSession.onFailure`（core main/RouterSession.kt:180-216） | 写 `RouteState`（退避/熔断/429 限额）、维护 `tried` 集合与 `reloginAccounts`、把 GiveUp 固化成 `RouteOutcome.GaveUp` |
| 执行 | `GatewayProvider` 的 while 循环（app GatewayProvider.kt:247-348） | 真的换到下一个组合去发；**已吐字后拒绝换号** |

换句话说：**core 里没有任何网络代码，换号是 :app 在循环里「再问一次 next()」实现的**；next() 每次重新跑一遍 planner，所以冷却/熔断对「换谁」的影响是即时生效的。

### M2 选路：planner 的过滤与排序

`RoutePlanner.plan`（core main/RoutePlanner.kt:129-221）先把「候选 × 该家的账号」做成笛卡尔积，再逐条判 `blockingReason`（:224-264），顺序固定：

`enabled → hasCredentials → supportsImages(仅 needsImages 且明确 false) → 冷却(backoff/breaker/limit) → maxConcurrent → 每账号 RPM → token 预算 → 家级 RPM`

存在的进 `attempts`（带 sink 标记）；被拦的进 `skipped`（带 `SkipReason`）。之后排序四步（Kotlin `sortedBy` 稳定，所以是叠加而不是重排）：

1. `priority 升序，其次 accounts 传入顺序`（:191-193）；
2. 轮询：整队转 `rotationSeed % size` 位（:196-199，仅 RoundRobin）；
3. 粘性命中者提到最前（:203-209），若粘性账号不可用则 `stickyCleared = true`（:219）；
4. 垫底（sink）排到最后（:212）。

`RouterSession.next()`（core main/RouterSession.kt:123-165）在此基础上做两件 planner 不知道的事：① 用 `tried` 集合排除本轮已经试过的 (账号,上游模型) —— 这是「401/内容拦截不冷却账号」时避免原地死循环的唯一保险；② 每次取用都 `rotationSeed += 1`，所以轮询是「每个组合转一位」而不是「每个会话转一位」。放弃/试完由 `lastOutcome` 固化成 `RouteOutcome`，供界面与回放使用。

### M3 失败分类链：一个 HTTP 响应怎么变成「罚不罚号」

```
HTTP status + body(+finish_reason)
  → UpstreamErrorClassifier.classify   (core main/UpstreamErrorClassifier.kt:82-117)
     判定优先级：finish_reason 拦截 → 正文配额标记 → 令牌过期标记 → 413/体积标记
                 → 4xx 正文拦截标记 → 状态码兜底
  → AiErrorKind                        (core:ai)
  → AiErrorKind.toFailureKind()        (core main/AiErrorMapping.kt:14-43)
  → FailureKind → FailurePolicy.actionFor / FailoverLoop.onFailure
  → RouteState.onFailure(账号级：退避+熔断计数)
  → 若 kind == QuotaLimited: state.markLimit(CooldownKey(account, wireModel), quotaResetAtMillis)
  → FailoverStep（SwitchAccount / RefreshCredentials / GiveUp / Done）
  → GatewayProvider 的 while 循环：再问一次 next()，或抛出
```

三处容易踩的细节（代码里都写了理由，我核对为真）：

- **冷却键必须是上游真名**：`markLimit` 用 `attempt.wireModel`（RouterSession.kt:191-196），不是用户请求名 —— 对应网关 issue #200。
- **不罚账号的四类**：`ContentBlocked / PayloadTooLarge / CredentialExpired / InvalidRequest` 在 `RouteState.onFailure` 里是 `Unit`（RouteState.kt:150-164）—— 表错一行就会「罚错号」。
- **限额不清**：`onSuccess` 只清退避/垫底/熔断，不清 429 限额（RouteState.kt:131-136），因为重置时刻是上游给的。

### M4 内置家的模型清单 vs 自定义家：这是「内置家选不出模型」的根因层

| 维度 | 内置家（BuiltinModelsCodec） | 自定义家（GatewayAccountStore.nativeModels） |
|---|---|---|
| 载体 | DataStore 键（JSON 对象 `providerId → [模型名]`），core main/BuiltinModelsCodec.kt:33-42 | Room `gateway_provider.modelsJson`（**换行分隔文本**），core/data GatewayAccountStore.kt:269-275 |
| 谁写 | `:app` 的 onApplyGatewayModels：`merge` 后 `setGatewayBuiltinModelsJson`（PraxisApp.kt:1872-1876） | `updateProviderModels`（GatewayAccountStore.kt:236-250）→ `modelsJson` + `modelsFetchedAt` |
| 也有导入路径 | 无（导入只处理 custom-*） | 导入：账号文件里 `customProviders[].models[]`（按 `enabled` 过滤后）→ GatewayImporter.kt:150-160 |
| 拉取时间 / 新鲜度 | **没有**（无 fetchedAt 字段） | 有（`modelsFetchedAt` 列 + GatewayModelCatalogCache.isFresh） |
| 逐模型启停 | 没有 | 没有（modelsJson 只存名字；导入时已按 enabled 过滤掉） |
| 能力位/上下文/推理档 | 没有 | 没有（`mappingsJson` 存在但**从不被读**） |
| 谁消费 | `GatewayProvider.builtinModels`（PraxisApp.kt:1122-1131 / 1368-1377）→ 只进**真实请求**的候选链 | `store.nativeModels()` → 进**真实请求**（GatewayProvider.kt:182）、**模型选择器**（PraxisApp.kt:340-352）、**候选队列预览**（PraxisApp.kt:441-449） |

结论（这就是用户关心的「内置家能不能选出模型」）：

- **能发出去**：只要内置家拉到过清单（存在 DataStore），真实请求的候选链会包含它 —— `GatewayProvider.kt:182` 确实把 `builtinModels()` 并上去了。
- **但选不出来**：模型页的候选（`gatewayModelOptions`）与路由页的队列预览**都只用了 `store.nativeModels()`**，没有并内置家（PraxisApp.kt:342 / :444）。用户在内置家上建了账号、拉到清单，模型页里依然看不到那些模型 → 验收线「拉清单 → 建账号 → 选模型」在**选模型**这一步断掉（G1）。
- **测试也会误报**：模型测试用的是另一个实例 `gatewayProbeProvider`（PraxisApp.kt:790-821），它**根本没传 `builtinModels`** → 对内置家模型点「测试」会得到「模型 X 没有对应任何上游」，与生产行为不一致（G1 的第二面，也是文档 §5.4.1 第 16 条「测试必须走真实链路」的偏离）。

### M5 导入链路（网关 → Praxis）

```
文件/剪贴板文本
  → GatewayImporter.parseAll(text)                // 先按单账号文件，再按 {accounts:[...]} 批量形状
     单文件: format == agent2api-account 且 id/provider 非空
     批量: accounts[] + customProviders[]（models[].enabled 过滤）
  → GatewayImporter.prepare(files, existingIds, knownProviders)
     分流: 无可用凭证(OAuth/空) → skipped；非 custom-* 且未知家 → skipped；
           同 provider+baseUrl 第二次 → 记「可能是重复账号」但仍 accepted；同 id → updated
  → :app 回调里（PraxisApp 的 onConfirmGatewayImport）调 GatewayAccountStore.applyImport(plan)
     a. 逐条 toCredential() + GatewayCredentialCodec.encode + encrypt；
        加密失败 → 整次导入失败，一条都不写（有意偏离 KeystoreCipher 的「退回明文」约定）
     b. 保留用户既有选择：enabled/priority/maxConcurrent/createdAt 沿用旧行
     c. 自定义家：toEntity() 无地址 → 返回 null → 进 providersWithoutBaseUrl，不落库
```

可注意的两点：报告四段是**落库前**给用户看的（GatewayImportScreen 先弹「确认导入」再写），符合「写完了再报用户就没得选了」的取舍；但**没地址的 custom 家被跳过时，挂在它下面的账号仍然 accepted 并落库**（GatewayAccountStore.kt:100 先写账号，:105-117 才处理 provider）→ 见 G9。

### M6 导出链路（Praxis → 网关）

```
GatewayExportPlanner.build(accounts, routing, modelRules?, includeSecrets, date)
  ├─ accounts/i.json ← GatewayExporter.accountFile(account, includeSecrets)
  │    includeSecrets=false → apiKey="***"、accessToken/refreshToken=null；文件名强制 -secrets 只在 true 时
  ├─ gateway-config.json ← GatewayConfigExporter.encode(routing) + {"modelRules": <原文>}
  │    routing 由 GatewaySettingsExport.toRoutingConfig(policy, system) 装配：只写「与默认不同」的键
  └─ notes（未包含项：apiKeys / praxis-gateway-backup.json / 明文凭证警告）
GatewayExportWriter.write(root, plan)  → 真写盘，越界路径抛 IllegalArgumentException
```

链路本身干净、往返有测；缺口是**内容不完整**：无 `customProviders`、无第三件产物 `praxis-gateway-backup.json`、apiKeys 永远不导出（G9）。

### M7 请求记录 → 报表 → token 预算

```
GatewayProvider.record(success, status, error, isTest)   // 每次请求结束都记一条
  → GatewayUsageRules.build(draft, success)              // 失败三 token 清零
  → recordUsage → GatewayUsageStore.record → Room gateway_request
报表: GatewayUsageStore.summarySince(now, days, zone)
  since = LocalDate(now, zone).minusDays(days-1).atStartOfDay(zone)   // 本地时区日界线，符合 #329
  → dao.summarySince / byModelSince  （isTest = 0 过滤）
  → GatewayUsageSummary → GatewayReportFormat.requestsText（成功率为空时显示—）
预算: tokensByAccountSince(windowStart) → RouterSession.setBudget → RoutePlanner 判 BudgetExhausted
保留: GatewayRetention.cutoffMillis → purgeRequestsBefore（启动时清）
```

口径上有意分成三种（都对）：报表排除 isTest；日志页「请求记录」包含 isTest；预算也用 isTest=0。**没做**的是日聚合表（G8）与「上游 HTTP 码进库」（G10）。

### M8 三处「看着像小工具、其实是规则」的实现

- **GatewayModelCatalogCache**（core main/GatewayModelCatalogCache.kt）：`isFresh` 时钟回拨算新鲜（不停用快照）；`decideWrite` 失败与「成功但空清单」都保留旧清单。它只对自定义家有意义（modelsFetchedAt 是 provider 表的列）。
- **RouteReplayLog**（core main/RouteReplayLog.kt）：内存环形 50 条 + `synchronized`；路由页用 `replayEntriesProvider()` 每次组合现取（没有 Flow，所以刷新靠重组）。
- **GatewayAccountStatusResolver**（core main/GatewayAccountStatus.kt:65-112）：停用 > 退避 > 熔断 > 限额；**「全部模型都被限」才算账号级冷却**，部分受限只列模型名 —— 因为限额键是 (账号, 模型)，这是对的；但调用方传的 wireModels 常常是空（见 G4），于是「部分受限」这个精心设计的提示在实际界面上几乎不出现。

## 5. 缺口 / 疑点清单

每条格式：**编号｜一句话** → 证据 → 影响 → 建议动作。按「会不会让功能真的不能用」排序。

---

### G1（严重）内置家的模型进不了「模型选择器」与「候选队列」，模型测试也会误报

- **证据**：真实请求把内置家并上了（app GatewayProvider.kt:182 `store.nativeModels() + builtinModels()`），但同一份数据在另外三处**没有并**：模型页候选 PraxisApp.kt:340-352、候选队列 PraxisApp.kt:442-449、模型测试实例 PraxisApp.kt:790-821（该构造点连 `builtinModels` 参数都没传）。
- **影响**：内置家拉完清单后，模型页仍然只列自定义家的模型 → 用户选不到 → 验收线「拉清单→建账号→选模型→真发出请求」卡在第三步；即使绕过选择器，模型测试会报「没有对应任何上游」，让用户以为清单/账号坏了。
- **建议**：在 :app 抽一个 `nativeModelsForRouting(): List<NativeModel> = store.nativeModels() + builtinModels()`，四处共用；模型测试实例补上 `builtinModels = { ... }`。这是最小改动、收益最大的一条。

### G2（严重）结构化模型条目（gateway_model / ModelEntry / 迁移）完全不参与选路 → 「逐模型启停」形同虚设

- **证据**：`CandidateChain.ofEntries`（core main/CandidateChain.kt:126-139，带 `enabled` 过滤）在生产**零调用**（全仓 grep 只在自身与测试里）；`GatewayProvider.kt:178-186` 用的是 `CandidateChain.of`；`ModelEntryMigration` 同样零调用。`GatewayModelStore.visibleModelIds` 只喂选择器。
- **影响**：① 模型管理页把某条关掉 → 只是从选择器消失，仍然**可以被请求**（文档 §13 明确要求「关掉的模型…也不能被请求」）；② 用户在模型页手动加的条目，若该名字不在 provider 的 modelsJson 里，选了也发不出去（候选链查不到）；③ 老配置迁移这件事从未执行过。
- **建议**：路由侧改为「entries（enabled）+ nativeModels + builtinModels」并集，或让 `nativeModels()` 也读 gateway_model 且应用 enabled；再把 `ofEntries` 接进去。
- **附带**：`GatewayModelRules.isDisabled`（core main/GatewayModelRules.kt:41-43）用 `==` 精确比较 provider/id，与文档 §5.4「全链路 eq_ignore_ascii_case」不一致（虽然它本身也零调用）。

### G3（严重）图片能力位从未进入选路视图 → 文档 §7.2 第 3 条与 #408 的防护实际不存在

- **证据**：`AccountView.supportsImages` 默认 null（core main/RoutePlanner.kt:25）；`GatewayAccountStore.routingViews()`（core/data GatewayAccountStore.kt:277-279）与 `toAccountView`（GatewayAccountMapper.kt:18-36）都不传它；modelRules 里解析出来的 `GatewayModelCapability.supportsImages()` 生产零调用。
- **影响**：带图请求对「明确声明不支持图片」的账号**不会跳过**（方向上是放行，故不会误拦，但文档要求的能力过滤等于没做；#408「一次白撞 9 次」的防护不成立）。
- **建议**：把 modelRules.capabilities（以及 gateway_model.supportsImages）按 (provider,model) 汇总成一个 lookup，在 :app 构造 routingViews 时按候选模型填进 AccountView；注意这是 per-model 而非 per-account 的，若不做要说清「暂不做」。

### G4（严重）并发 / 每账号 RPM / 家级配额三条过滤在真实链路恒为「不限」

- **证据**：planner 实现了三条判定（core main/RoutePlanner.kt:245-261），但输入永远是空/0：`routingViews()` 只传 `hasCredentials`（GatewayAccountStore.kt:277-279，runningConcurrent/requestsThisMinute/requestLimitPerMinute 全走默认值）；`RouterSession` 的 `providerUsages` 参数在 :app 三处构造点（聊天 / 标题生成 / 模型测试）都没传（GatewayProvider.kt:202-210）；账号的 `maxConcurrent` 列没有任何 UI 入口（addAccount 固定 0，GatewayAccountStore.kt:160）。
- **影响**：文档 §7.2 第 5/6 行在设备上等于没实现；用户看不到「并发已满 / 限流已满 / 家级配额满」这三种跳过原因（文案写了、永远不出现）。
- **建议**：要么接上（RequestThrottle 已在 core:ai，可以复用其计数），要么在路由页如实标注「并发与 RPM 过滤尚未接线」，避免「写了但空转」。

### G5（严重）「凭证过期先续期一次」没有执行者，needsRelogin 在生产路径永远为空

- **证据**：`FailoverStep.RefreshCredentials` 由 `FailoverLoop` 返回（core main/FailoverLoop.kt:71-76），`RouterSession.onFailure` 只把它记进 `failover.refreshed`（RouterSession.kt:206），而 :app 的循环**不处理这个 step**（GatewayProvider.kt:336-347 只看 `emitted`）；同时 `tried` 集合已排除同一 (账号, 模型)（RouterSession.kt:96/146-163），所以同一账号不可能在一轮请求里失败第二次 → `reloginAccounts`（RouterSession.kt:202-204）永远不会被写入。
- **影响**：文档 §7.4 第 2 行只实现了一半（有「换号」，没有「先续期」）；`needsRelogin` 这个给界面用的信号是死信号；若是可续期的 OAuth 账号，行为会退化成「直接换号」。
- **建议**：要么在 :app 实现 refresh 分支（现在 OAuth 未导入，可先明确降级为「直接换号并标 needsRelogin」），要么把 `RefreshCredentials` 从返回类型里去掉 —— 现在这种「返回了但没人做」最糟。

### G6（严重）会话粘性整条没有接线（ConversationKey 与 setStickyAccount 生产零调用）

- **证据**：`ConversationKey` 全仓 0 处生产调用；`RouterSession.setStickyAccount` 只有测试调用；`RouterSession.stickyAfterSuccess` 只写不读。`GatewayRoutingPolicy.stickyEnabled` 默认 true（core main/GatewayRoutingPolicy.kt:32）但 `stickyAccountId` 恒为 null，所以 planner 里粘性那段（RoutePlanner.kt:203-209）永远走「无粘性」分支。
- **影响**：路由页的「粘性」开关是个**真开关但没有效果**（违反铁律 1「每个设置项必须有消费方」）；文档 §7.3 的「同一会话固定走同一账号」不成立。ConversationKey 的 7 个单测测的是死代码。
- **建议**：在 :app 的一处把「会话键 → 账号」存起来（内存 Map + TTL 即可），请求前 `session.setStickyAccount(map[key])`，成功后写回 `stickyAfterSuccess`；否则把开关从页面上摘掉。

### G7（中）`FailurePolicy.decide` 零调用：冷却时长换算没有消费者，语义与 RouteState 重复

- **证据**：全仓零调用（只有 FailurePolicyTest）。`FailureDecision.cooldownMillis` 的两种算法（上游重置时刻换算 / 兜底 10 分钟）在 `RouteState.markLimit`（RouteState.kt:95-99）里又写了一遍，真正生效的是后者。
- **影响**：同一规则两处实现，改一处不改另一处就会漂移；`FailurePolicy` 那张「逐行可断言的表」实际只被 `actionFor` 用到。
- **建议**：让 `RouterSession.onFailure` 用 `FailurePolicy.decide(kind, now, quotaReset).cooldownMillis` 去调 `markLimit/markBackoff`，把时长算法收敛到一处；或删掉 decide 的 cooldown 语义。

### G8（中）报表：日聚合表是死表，全表扫；`GatewayReportWindow` 未被使用

- **证据**：`GatewayDailyEntity/upsertDaily/dailySince`（core/data GatewayUsageEntities.kt:68-104）全仓无调用者；报表直接查明细（同文件 :106-128 的注释也承认）；`GatewayReportWindow`（core main/GatewayReport.kt:9）只有测试用，:app 里另写死 `"今日" to 1L / 7 / 30`（PraxisApp.kt:963-967）。
- **影响**：文档 §5.7/§6.1 的「日聚合避免扫全表」未落地；窗口定义有两份（core 与 app），改一处会不一致。当前数据量下性能无忧，属**技术债**而非 bug。
- **建议**：删掉死表（一次迁移）或在写入时同步 upsertDaily；窗口定义改成消费 `GatewayReportWindow`。

### G9（严重）导出-导入回路不完整：customProviders 双向丢失，且无地址的自定义家会让账号变「半条」

- **证据（导出侧）**：:app 构造 `GatewayAccountFile` 时不填 `customProviders`（PraxisApp.kt:2296-2330），`GatewayExportPlanner.build` 也没有这个参数（core main/GatewayExportPlan.kt:80-118）→ 导出的账号文件里 `customProviders` 为空，文档 §8.1/§8.2 的「自定义家随账号带出」不成立；`gateway-config.json` 里也没有 `customProviders`（文档 §8.5 说它含这一项）。
- **证据（导入侧）**：`GatewayAccountStore.applyImport` 先写账号（:100），再处理 provider，无地址的 provider 只进 `providersWithoutBaseUrl`（:105-117、:314-324），但**账号已经落库**；请求时地址解析三段全落空 → `GatewayProvider.kt:258` 的 `continue` 静默跳过 → 最终报「网关没有可用的账号」，用户完全看不出是「那家没地址」。
- **证据（第三件产物）**：文档 §8.5 要求 `praxis-gateway-backup.json`，代码只在 notes 里写「未产出」（GatewayExportPlan.kt:112）。
- **影响**：把 Praxis 的导出拿到网关回灌，自定义家会丢（或地址变空）；本机导入一份不带地址的 custom 家账号，会得到一个「在账号列表里、但发不出去、且原因不可见」的账号。
- **建议**：导出时把 provider 表投影成 `customProviders`（地址+模型清单，映射从 mappingsJson 读）；导入时把「provider 无地址」的账号改为 skipped 或至少在报告里写明「该账号所属家缺地址，导入后不可用」。

### G10（中）失败记录的上游状态码恒为 0 → 报表无法区分 429/401/5xx

- **证据**：`GatewayProvider.kt:360-365` 记失败时 `status = GatewayUsageRules.STATUS_NO_RESPONSE`（0），而 `AiException` 里的 HTTP 码没有透出来；实体字段注释（core/data GatewayUsageEntities.kt:33-34）说「0 = 没拿到响应；其余为上游 HTTP 码」。
- **影响**：文档 §6.1「status 为上游 HTTP 码」未实现；报表的「失败」是个不分类的总数，排障只能看 error 文本。
- **建议**：让 core:ai 的 AiError 带上 httpStatus（Unconfirmed：不确定当前 AiError 是否有该字段），或在 GatewayProvider 捕获处把 status 写进 draft。

### G11（中）内置家与自定义家的 id 冲突校验过窄，且地址优先级两处方向相反

- **证据**：`GatewayProviderDraftRules.BUILTIN_IDS = setOf("anthropic","openai")`（core main/GatewayProviderDraft.kt:35），而 :app 的 `BuiltinProviders.TABLE` 有 20+ 家（BuiltinProviders.kt:78+）→ 用户可以用 `deepseek`/`glm` 等 id 建自定义家而不被拦；地址解析 `GatewayProvider.kt:254-258` 的顺序是 `账号覆盖 > 自定义家表 > 内置表`（即**自定义优先**），而 PraxisApp.kt:1112-1117 的同名注释与 GatewayProviderDraftRules 的 KDoc 都写着「内置地址优先」。
- **影响**：用户建一个 id=`deepseek` 的自定义家，会把真正的 DeepSeek 官方地址顶掉（或反过来，取决于哪条路径），行为与文档/注释相反，且没有任何提示。
- **建议**：把内置家 id 表作为单一来源（:app 的 BuiltinProviders.ids()）传进 core 的校验，或把校验搬到 :app；同时把两处优先级统一并写死在一个函数里。

### G12（中）四处「僵尸文案/KDoc」与实际实现相反，会误导用户与后续维护者

- feature/GatewayAccountsScreen.kt:112「内置家清单要等 Rust 内核」、:157「还没有界面可以添加账号」（同页 :102 就有入口）；
- feature/GatewayRoute.kt:313-319 `PLAN_LINES`「P5 内置 21 家要等它」，而 :app 已有 20+ 家内置表与地址；
- feature/GatewayRoutingScreen.kt:44-52 KDoc 说「策略与容错两区还没做」，同文件 :106-303 已完整实现；
- feature/GatewaySystemScreen.kt:30-47 KDoc 说图片压缩/脱敏/自动续写「不放进来」，:158-328 就是它们。
- **影响**：用户按页面文字判断功能状态会得出相反结论；违反「如实标注」的初衷（本意是好的，只是没跟上代码）。
- **建议**：把这几处更新为当前事实；`PLAN_LINES` 改成「P5 剩余：签到框架 / Rust 内核的剩余能力」。

### G13（中）垫底（sink）机制在真实链路永不触发

- **证据**：`RouteState.markSink` 只有测试调用（全仓 grep）；文档 §7.4 第 1 行「**还有额度时另加垫底**」对应的写入口没有调用者。planner 读 `sinkUntil` 的分支（RoutePlanner.kt:178/212）因此永远为 false。
- **影响**：文档里「额度受限 → 标限额 + 换号 + 另加垫底」只做了前两项；界面上「垫底（仍可用，排在最后）」这条文案基本不会出现。
- **建议**：在 `RouterSession.onFailure` 的 QuotaLimited 分支里，按「该账号还有其它模型可用」判据调 `markSink`；或明确删掉 sink 概念并同步文档/UI。

### G14（中）API Key 与代理密码在输入框里是明文（secret 参数写了没生效）

- **证据**：feature/GatewayAddAccountScreens.kt:376-392 `AddField(label, value, secret = …)` 的 `secret` 参数体里**没用到**（TextField 无 `visualTransformation`）；调用点 :236 `AddField("API Key", apiKey, secret = true)`、:324 粘贴凭据同样明文；feature/GatewayProxyScreen.kt:212 `ProxyField("密码（可空）", password)` 也明文。
- **影响**：肩窥/截屏泄露；与文档 §8.7「明文只存在于导入那一瞬间与内存」的意图不一致。
- **建议**：给 TextField 传 `visualTransformation = PasswordVisualTransformation()`（miuix 的 TextField 支持与否未确认），或至少加一个「显示/隐藏」按钮。

### G15（中）内置家的清单没有「拉取时间/快照」概念，§5.4.1 第 17 条只对自定义家成立

- **证据**：`BuiltinModelsCodec` 的 payload 只有 `provider → [名字]`（core main/BuiltinModelsCodec.kt:30-42），`GatewayBuiltinProviderRowUi` 也没有 `modelsFetchedAt`（feature/GatewayProvidersScreen.kt:76-82）；对比自定义家行会显示 `GatewayModelCatalogCache.ageText`（:229-230），内置家行只显示模型数（:160-167）。
- **影响**：用户不知道内置家的清单是刚拉的还是一年前的；`isFresh` 无法用于内置家，「快照」这件事缺一半。
- **建议**：把 payload 升级为 `provider → {models:[], fetchedAt: long}`（decode 兼容旧的纯数组），并在内置家行显示 ageText。

### G16（中）测试盲区：feature:gateway 零测试，:app 胶水与几条关键分支无自动化

- **证据**：feature/gateway/src 只有 `main`（无 test 目录，build.gradle.kts 也无 testImplementation）；`GatewayProvider` 在 :app 没有对应单测（只有 :app 的 WiringGuardTest 做字符串包含断言）；`RouterSession` 的 `providerUsages` / `tokenBudget` 生效路径 / `rotationSeed 在 session 层的转位` 均无测试。
- **影响**：G1/G5/G9/G10 这类「只在接线层才暴露」的问题，396 个单测一个也拦不住（它们全绿）。
- **建议**：把 :app 的 GatewayProvider 循环抽成可测的纯函数（输入：候选/账号/一个 `(PlannedAttempt) -> Result/Error` 的发送器），在 core 或 :app 的 JVM 测试里验「换号次数、已吐字不换号、isTest 不落报表、地址优先级」。

### G17（低）自定义家的 `mappingsJson` 是只存不读的死字段

- **证据**：写入三处（GatewayAccountStore.kt:222/321），读取 0 处（全仓 grep 只有 KDoc 与 entity 定义）。
- **影响**：文档 §5.3 的记录形状含 `mappings`，导入时那段信息落地了却永远不生效；用户在网关里配的「家内映射」搬到 Praxis 后静默消失（而报告里不会提）。
- **建议**：要么解析进 `GatewayModelRules.mappings` 合并逻辑，要么在导入报告里写明「自定义家的 mappings 未导入」。

### 未确认项（不敢下结论，标出来）

- **U1**：`GatewayProvider.kt:253` 只接受 `GatewayCredential.ApiKey`，OAuth 账号走到这里是 `continue` —— 我确认代码如此，但**未确认**是否有别的路径（如将来的 token 刷新任务）会改写账户凭证类型。
- **U2**：`AiError` 是否带 httpStatus 字段（影响 G10 的改法）—— 我只读了 core:gateway 与 :app 的用法，没读 core:ai 的 AiError 定义。
- **U3**：miuix 的 `TextField` 是否支持 `visualTransformation`（影响 G14 的实现方式）。
- **U4**：内置家真实清单能否拉到（取决于各家 /models 是否可用与鉴权方式）—— 属运行时事实，本机无法验证。

## 6. 测试覆盖分析（重点问题 4）

### 6.1 覆盖面：领域层很密，接线层为零

| 层 | 测试 | 评价 |
|---|---|---|
| core:gateway 纯逻辑（48 个类，396 例） | 覆盖到「每张表逐行」「每个状态迁移」「每个编解码往返」 | 质量高：有用例专门钉「默认关/默认 3 次」「不罚错号」「缺键放行」「文件名可排序」「越界路径被拒」等语义，而不是只测 happy path |
| core:data 落库层 | 有（GatewayAccountStoreTest / Mapper 测试，不在本报告范围） | 与本报告交叉的部分：加解密失败全有或全无、保留用户选择 |
| feature:gateway（11 个 Compose 页面） | **0** | 全模块无测试目录；页面里的两处逻辑（`formatReport`、路由成员行解析 `家/模型`）只有手测 |
| :app 胶水（GatewayProvider 换号循环、候选链装配、导出参数装配） | **0**（仅 WiringGuardTest 字符串断言） | G1/G5/G9/G10/G13 全部落在这个盲区 |

### 6.2 具体没测到的行为（按风险排）

1. **换号循环本身**：没有任何测试验证「a1 401 → 换 a2 成功」「已 emit 后失败不再换号」「试完所有候选的报错文案」。RouterSessionTest 只测到状态机的返回值，没测 :app 的循环。
2. **模型测试走同一条链路**：文档 §5.4.1 第 16 条的核心要求，无测试；而 G1 说明它实际**不是**同一条链路。
3. **地址优先级**（账号覆盖 > 自定义家 > 内置家）：无测试；G11 说明注释与代码方向相反。
4. **粘性端到端**：ConversationKey 有 7 个测试，但它在生产零调用（测的是死代码）；RouterSession 的粘性只测了 setStickyAccount 直传。
5. **轮询在会话层**：planner 的转位测了（GatewayRoutingPolicyTest 3 例），RouterSession 每次 next() 递增 seed 的行为没测。
6. **粘性 vs 轮询的叠加顺序**：planner 里「先转位、再把粘性提到最前」，即粘性覆盖轮询 —— 这是有意的取舍，但没有用例钉住，改动时容易静默反转。
7. **priority 相同 + 组内顺序**：`groupOrder` 用 accounts 传入索引做二级排序（RoutePlanner.kt:187-193），无用例。
8. **token 预算端到端**：RoutePlannerBudgetTest 测了 planner 的跳过，没测 RouterSession.setBudget 的注入时机（每请求一次）与「无记录=0」。
9. **导入的 baseUrl 缺失**：GatewayImporterTest 覆盖了四种分流，但没覆盖「custom 家无地址」这一种（G9 的成因），也没覆盖「账号 baseUrl 非法」。
10. **导出文件名冲突**：两个账号名字净化后相同（如 `a/b` 与 `a:b`）会靠序号区分，无用例。
11. **config.json 的 B 类键**：只测了「完全不认识的键进 unknown」，没测 promptMode/modelsDev/retention 这几类**已知但未移植**的键会落在哪一栏（现在落 unknown，与「服务端专属逐条列出」的承诺混在一起）。
12. **人工看得见的字符串**：GatewayStatusText / GatewayReportFormat 有测（这点很好），但 feature 层的 `formatReport` 四段文案、路由页的队列行拼装无测。

## 7. 对照 docs/PORTING-GATEWAY.md：要求了但代码里没有 / 半成品

### 7.1 §5.4.1 模型层差距清单 20 条（这是文档点名「差距最集中」的一块）

| # | 文档要求 | 现状 | 证据 |
|---|---|---|---|
| 1 | 结构化条目（含能力位/上下文/推理档） | ✅ 类型与表都有，⚠️ 不参与选路 | core main/ModelEntry.kt；core/data GatewayModelEntity.kt；G2 |
| 2 | 逐模型启停 | ⚠️ 只影响选择器，不影响请求 | G2 |
| 3 | 逐模型思考等级 | ⚠️ 只在 ModelMapping.reasoning 里透传，无「家侧翻译」 | core main/CandidateChain.kt:8；文档 §5.4.1 说由「承载家的 reasoning_patch」翻译，本仓库没有 |
| 4 | 模型类型 chat/image | ✅ 类型有（ModelKind），⚠️ 无消费 | core main/ModelEntry.kt:4-7 |
| 5 | 倍率/积分 credits | ⚠️ 字段有、永远 null | 文档自己把这条列为「建议不做」 |
| 6 | 能力位六项 | ⚠️ 字段有、选路只用 supportsImages 且恒 null | G3 |
| 7 | 输入模态 | ⚠️ 字段有（inputModalities），无消费 | core main/ModelEntry.kt:44 |
| 8 | 上下文/输出上限 | ⚠️ 字段有、无来源 | ModelEntry.maxInputTokens/maxOutputTokens |
| 9 | models.dev 元数据 | ❌ 未做 | 全仓无 models.dev 实现 |
| 10 | 思考等级候选/默认/固定/来源 | ⚠️ 字段有、无来源 | ModelEntry.reasoningEfforts 等 |
| 11 | 能力位用户覆盖 | ⚠️ 能解析（modelRules.capabilities）但无人读 | G3 |
| 12 | 模型映射（一对多跨家） | ✅ 做了且顺序=/配置顺序 | CandidateChainTest 3 例 + GatewayRouteGroupTest |
| 13 | 自定义登记（custom[]） | ⚠️ 能解析、无消费 | GatewayModelRules.custom 零读取 |
| 14 | 悬空映射识别（dangling） | ❌ 未做 | 无对应类型；只有 unresolvedMappings（缺 provider 的映射） |
| 15 | 每 Key 模型白名单 | ❌ 未做（文档建议 P6） | — |
| 16 | 模型测试（走真实链路） | ⚠️ 有入口，但实例漏 builtinModels、且不带钉账号 | feature/GatewayModelsScreen.kt:69；PraxisApp.kt:790-821；G1 |
| 17 | 清单刷新：快照 + 缓存 + 失败保留 | ✅ 自定义家完整；⚠️ 内置家无快照时间 | core main/GatewayModelCatalogCache.kt；G15 |
| 18 | 备援名（payload.rs 扩池） | ❌ 未做 | — |
| 19 | 未知模型建议（suggest_from） | ❌ 未做 | — |
| 20 | 上游 token 计数（count_tokens） | ❌ 未做（用本地估算） | 文档建议不做 |

### 7.2 §6 数据模型

| 文档 | 现状 |
|---|---|
| gateway_account（含 expiringAt/createdAt） | ✅ 表与字段齐全（core/data GatewayAccountEntity.kt） |
| gateway_provider（modelsJson/mappingsJson） | ✅ 表在，⚠️ mappingsJson 只存不读（G17） |
| gateway_request（17 列） | ✅ 表齐全，⚠️ status 恒 0（G10）、firstResponseMs/client/credit 永不写 |
| gateway_daily（日聚合） | ⚠️ 表在、零读写（G8） |
| ModelEntry + 迁移 | ⚠️ 类型有、迁移零调用（G2） |
| DataStore 键（19 个 gateway_*） | ✅ 大部分有（enabled/policy/system/route_groups/proxy/model_rules/retention/builtin_models/sanitize）；文档里的 `gateway_retry_count`（单账号内重试）、`gateway_failover_backoff`、`gateway_sink`、`gateway_no_retry_codes`、`gateway_queue_max_waits`、`gateway_timeout_headers/body` **没有对应实现**（导入时会落 unknown 或 invalid） |
| RouteState 六方法 | ✅ 全有（外加 markSink/markLimit/breakerUntil），⚠️ markSink 无调用（G13） |

### 7.3 §8 导出 / §9.7 导入导出页

| 文档要求 | 现状 |
|---|---|
| `gateway-config.json` 含 routing + modelRules + customProviders + apiKeys | ⚠️ 只有前两项（G9） |
| `accounts/*.json` 含 customProviders | ⚠️ 恒为空（G9） |
| `praxis-gateway-backup.json`（Praxis 自己的完整档案） | ❌ 未产出（notes 里承认） |
| 仅配置打码 `***` / 含凭证明文 + `-secrets` + 二次确认 | ✅ 全部实现（导出侧 + 页面二次确认 + notes 警告） |
| 双向可逆（P0 就要用真实文件跑通） | ⚠️ 自往返有测（GatewayConfigRoundTripTest/GatewayExporterTest），**与真实 Agent2API 的双向未验证**（本机无法验证，未确认） |
| C 类键逐条列出 | ✅ GatewayConfigImporter.serverOnlyKeys，且有测 |
| §9.7 三个入口（文件/剪贴板/备份包） | ⚠️ 前两个有，备份包未做（界面上没有假按钮） |

### 7.4 §7 报表 / §5.7 统计

| 文档要求 | 现状 |
|---|---|
| 日界线按本地时区 | ✅ 两处都显式取 zone（GatewayUsageStore.summarySince / GatewayRetention） |
| 失败请求 token 清零 | ✅ GatewayUsageRules.build + 单测 |
| 测试请求不入报表 | ✅ isTest=0 过滤；日志页反向保留（有意） |
| 今日/7 天/30 天 | ✅ 有，⚠️ 窗口定义在 :app 另写一份（G8） |
| 缓存命中率、按家/Key/客户端筛选、365 天热力图、可终止进行中请求、原始报文 | ❌ 均未做（日报/热力图文档说「不做图表」，其余属 §5.7 能力清单） |
| 数据保留期 | ✅ GatewayRetention + 启动清理 + 页面可调 1~365 天 |
| 「不做图表」 | ✅ 遵守（报表页只有数字与列表） |

### 7.5 其它与文档的偏离（已确认且我认为**合理**，记录备查）

- **H5 容器做成弹窗而不是路由**（feature/GatewayAddAccountScreens.kt:271-280）：文档 §9.9 列的是 `PraxisRoute.SettingsGatewayAuth`。理由（回到这一层继续粘贴、避免丢输入）成立。
- **sink 按「软降级」而不是过滤**（core main/RoutePlanner.kt:112-119）：文档 §7.2 与 §7.3 自相矛盾，代码选 §7.3 并写明理由。
- **映射优先于原生 / 组优先于映射**（CandidateChain.kt:60-70 与 :90-92）：文档没写，代码定优先级并写进 KDoc。
- **冷启动不预置「网关」档案**：文档 §9.1 ③ 要求预置，代码里我没找到预置逻辑（**未确认**，:app/core:data 的档案初始化不在我读的范围内）。

## 8. 建议的下一步（按性价比排序）

1. **修 G1（半天到一天）**：抽 `nativeModelsForRouting()` 给四处共用，并给 probe 实例补 `builtinModels`。这一条直接决定「内置家能不能走完验收线」。
2. **修 G9 的导入侧（半天）**：「家没地址」的账号不要落库，或至少在报告里点名；导出侧补 `customProviders`（provider 表投影）。这决定「导入再导出幂等」这条验收能不能过。
3. **决策 G5/G6/G13/G4（一次坐下来说清）**：「续期、粘性、垫底、并发/RPM」这四件事，要么接线，要么把 UI 开关/文案摘掉。现在的状态是「有类型、有单测、有界面、无效果」，最伤信任（也违反铁律 1）。
4. **修 G2（1~2 天）**：让 gateway_model 的 enabled 参与选路 —— 否则「模型管理」页告诉用户的话是假的。
5. **补 G16 的第一块（1 天）**：把 :app 的换号循环抽成可测形态，先钉住三条：401 换号、已吐字不换号、isTest 不落报表。
6. **清理 G12 的僵尸文案（1 小时）**：纯文字，但它是「如实汇报」这件事的门面。

---

（本文件是只读审计产物；除本文件外未修改仓库任何内容，未运行 gradle/测试，未连设备。）