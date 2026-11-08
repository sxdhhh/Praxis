# 网关移植 · 当前状态与偏离记录

本文记录 Agent2API 网关移植到 Praxis 的实际进度：**做成了什么、怎么验证的、哪些明确没做、哪些地方有意偏离了文档**。

规格来源是 PORTING-GATEWAY.md。凡与本文冲突之处，以那份文档为准；本文只解释「实现与它差在哪、为什么」。

## 一、一句话现状

P0 全部完成；P1 的**路由引擎、失败切换、账号池、出站 Provider、剪贴板导入**完成；P2 的**数据层与账号页**完成。

**现在只有自定义家（custom-*）能真正端到端跑通。** 内置家（openai 等 21 家）的账号会被导入跳过——
Praxis 还没有这些家的地址与协议实现，要等 P5 的 Rust 内核。这是当前最大的功能空洞。

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

最近一次全绿（提交 **58cbd54**，2026-11-08，含本文件上一版之后的全部改动）：

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

1. **内置家（21 家）**：没有地址与协议实现，导入时会被跳过并写明理由。依赖 P5 的 Rust 内核 + JNI
2. **导出**：**逻辑层已完成**（`GatewayExportPlanner` 计划 + `GatewayExportWriter` 落盘，15 条单测）；
   **界面与接线还没做** —— 现在只能靠代码调用，用户点不到。计划层已如实处理两处缺口：
   路由/重试/超时配置 Praxis 尚未持久化（note 里写明）、`apiKeys` 未包含（P6 才有网关 Key）
3. **添加账号三级流程 + H5 容器**：目前只能靠粘贴导入，不能手动新建
4. **模型页自动填网关模型清单**：现在要手动敲模型名
5. **路由页 / 模型管理页**：候选队列可视化（谁被跳过、为什么）还没有
6. **gateway_enabled 总开关**：文档 11 末尾要求「关掉 = 今天的行为」，目前没有这个闸
7. **core:ai 补三类错误**（QuotaLimited / ContentBlocked / TokenExpired）：因此 413 与内容拦截落不到正确分类
8. **token 预算限流**：7.2 只实现了每账号与每家的 RPM，token 预算需要 P3 的请求记录表
9. **P3 及之后**：协议编码器、报表、日志并入、系统设置、代理、云同步、Rust 内核、签到框架、AGPL 转换

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

## 九、下一步建议

1. 模型页自动填网关模型清单（数据已齐：gateway_model + provider 的 modelsJson + mappings 的 alias）
2. 路由页：候选队列可视化，把 RoutePlan.skipped 的原因展示出来
3. core:ai 补三类错误，让 413 与内容拦截落到正确分类
4. gateway_enabled 总开关（文档 11 要求的回滚闸）
5. 导出（账号文件 + config 片段）
