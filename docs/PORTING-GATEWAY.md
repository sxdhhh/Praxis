# Agent2API 网关 → Praxis 完整移植方案

> 版本：v2（取代 v1）。v1 只覆盖了「模型配置 + 路由 + 导入导出」三块，**遗漏了网关其余 17 个功能组**。
> 本版按**网关的真实路由表**（222 条）与 **Praxis 的真实页面**（4 Tab + 19 设置页 + 26 屏级文件）逐组对齐。

---

## 0. 依据与边界

### 0.1 网关功能的客观清单

直接读 `desktop-tauri/src-tauri/server/src/server/http.rs`（1518 行）解析出来的路由表，
**222 条唯一路由**，按前缀分组后是 20 个功能组：

| # | 功能组 | 路由数 | 代表端点 |
|---|---|---|---|
| 1 | 网关协议面 `/v1/*` | 9 | chat/completions、responses、messages、count_tokens、models、images/* |
| 2 | 账号池 `/api/accounts*` | 3 | 路由表只登记 3 条，**实际 20+ 子动作由 `accounts_entry` 统一分发** |
| 3 | 自定义提供商 | 5 | CRUD、fetch-models、models |
| 4 | 模型管理 `/api/models*` | 12 | manage、mappings、custom、capabilities、state、metadata、test、refresh |
| 5 | 路由 `/api/routing*` | 12 | 策略、队列、工作区、路由组、预览、粘性、flow、trace、回放 |
| 6 | 网关 Key | 2 | CRUD（allowedProviders / allowedModels） |
| 7 | 请求统计 `/api/stats*` | 12 | summary、requests、filters、raw、terminate、compact、retention、usage |
| 8 | 日志 `/api/logs*` | 5 | query、stats、download、clear |
| 9 | 代理 `/api/proxies*` | 9 | 池 CRUD、启停、测试、Clash 同步、设置 |
| 10 | Agent 接管 `/api/agents*` | 9 | profiles、apply、preview、import、restore、resources |
| 11 | 工具服务 | 23 | providers、api-keys、servers、vision、install-skill/mcp、web-search |
| 12 | AI 助手 `/api/assistant*` | 9 | chat、config、sessions、fork、title、images |
| 13 | 签到体系 | 18 | 签到中心、排期、记录、活跃、保活、旅行、auto-checkin |
| 14 | 插件 | 12 | list、install、enable、settings、update、runtime/ui |
| 15 | 云同步 | 6 | 设置、上传、下载、远端、测试、导入密钥 |
| 16 | 备份 | 5 | export、import、upload、download、progress |
| 17 | 面板安全 | 32 | 面板登录/改密/验证码 + 会话 OAuth/短信/回调 + `/auth/*` |
| 18 | 系统设置与杂项 | 35 | config、sanitize、prompt、image-compress、captcha、cors、debug、queue、retry、timeouts、scheduled-tasks、zcode/*、health… |
| 19 | 升级 / 更新 | 3 | update、upgrade、upgrade/run |
| 20 | 导入 `/api/import/*` | 1 | 从 cc-switch 扫描 |

**合计 222 条**（上表相加 = 222，逐条来自 `http.rs` 的字面量路由）。

> 一个必须知道的细节：**路由表不等于功能数**。账号（20+）、签到、面板会话这几组的子动作
> 由统一入口分发（如 `accounts_entry`），路由表里只有 1~3 条。所以「按路由数估工作量」会低估这几块。
### 0.2 Praxis 的页面清单（客观）

| 层级 | 内容 |
|---|---|
| 主 Tab（4 个，Pager） | **对话** / **终端** / **记忆** / **设置** |
| 设置二级页（19 个 Route） | 模型、功能模型、系统提示词、角色卡、角色编辑、提示词标签、外观、工具、权限、记忆、工作流、语音、Linux 环境、终端设置、包管理、兼容插件条目、日志、关于、开源许可 |
| 屏级文件 | `ChatScreen`（133 KB）、`ChatSessionsPage`、`TerminalScreen`（66 KB）、`LinuxEnvScreen`（40 KB）、`ModelScreen`（69 KB）等 26 个 |

### 0.3 本次读到的范围

- **网关**：`http.rs` 全路由表、`config/types.rs`（60+ 键）、`api_keys`、`model_rules`、`custom_providers`、
  `account_transfer/export`、`upstream/*`（路由与失败切换）、以及此前 14 轮修复中确认过的实现细节。
- **Praxis**：`core/ai` 16 个文件全部、`core/model/settings` 全部、`SettingsRepository`（1446 行）、
  `OpenAiCompatProvider` / `SettingsBackedProvider` / `OkHttpModelCatalogFetcher`、
  `AppModule` / `NetworkModule`、`feature/settings/*`、`PraxisRoute` / `MainShellPage`、`PraxisApp` 导出落地段。
- **未读**：Praxis 的 `core/linux`/`terminal`/`plugin`/`tool` 内部；网关的 `providers/*`（**在插件仓库，本仓没有**）。

---

## 1. 总体结论（已按你的九条决定定稿）

### 决定一：**不新增 Tab**，入口放在设置页

设置首页加一个条目「网关」（`SettingsEntry.Gateway` → `PraxisRoute.SettingsGateway`），
点进去是一个二级页组（与「模型」「工具」「Linux 环境」同一层级），不再动 `MainTab`。

**为什么这个更好**：底栏那四个是「对话 / 终端 / 记忆 / 设置」，四件不同的事；
网关是**配置与运维**，本质属于设置域。再插一个 Tab 会让底栏变 5 个，
而大多数用户配好之后很少再点它。

### 决定二：模型配置里**预置一套「网关」配置**

`ModelProfile` 增加 `kind` 字段（`single` / `gateway`），并**默认预置一条 kind = gateway、名字叫「网关」的配置**。
选中它时，模型页的行为与普通配置完全不同：

| 区块 | 普通配置（single） | 网关配置（gateway） |
|---|---|---|
| 服务商 | 预置提供商列表 | **整块隐藏** |
| 接口地址 | 手填 baseUrl | **整块隐藏** |
| API Key | 手填密钥 | **整块隐藏** |
| 模型 | 拉取 / 手填模型 ID | **自动填**：来自网关的对外模型清单 |
| 其他 | 参数 / 上下文 / 思考 / 自定义头 / 请求队列 | 保留（它们对网关同样有意义） |
| 新增 | — | **一张「网关入口」卡片**（点进去 = 设置 → 网关） |

也就是说：**选「网关」这套配置时，用户看不到也不需要填地址与密钥** ——
那些由网关的账号池管理。他要做的只是点网关入口把账号配好，再回到模型页选模型。

「网关入口」卡片只有三行，不是表单：

```
网关入口
  已配置 5 个账号 · 3 个提供商 · 12 个模型        [进入]
  未配置时：「还没有账号，点这里配置」
```

**为什么不让用户在这里填 baseUrl/key**：网关模式下这两样属于**账号**（一个网关可以有多个账号），
放在模型配置页填就变成「一套配置一个账号」，退化回现有单配置模式，等于白移植。

### 决定三：日志**并入 Praxis 现有日志页**

不新做「网关日志」页。`SettingsLog` 加一个**来源筛选**：`全部 / App / 网关 / 请求记录`。
网关请求明细本来就要进 Room（报表数据源），日志页只是换个视图读同一份数据。

### 决定四：UI **一律复用 Praxis 现有控件与样式**

不写自定义控件、不自定颜色/圆角/动效。白名单与禁止项见 **§9.0**。

### 决定五：AI 助手**不移植**，只搬「图片生成 / 编辑」

网关的 `/api/assistant/*`（chat / sessions / fork / title / config）与 Praxis 的对话页**功能重叠**，
搬过来只会得到两套会话体系。所以：

| 网关能力 | 处理 |
|---|---|
| assistant chat / sessions / fork / title | ❌ 不移植（Praxis 对话页已有，且更完整：变体、分支、Markdown） |
| assistant config | ❌ 不移植（Praxis 的 `FunctionSlot` + 系统提示词已覆盖） |
| **`/v1/images/generations`** | ✅ **移植** |
| **`/v1/images/edits`**（参考图编辑） | ✅ **移植** |
| `/v1/images/files/{id}`（生成结果下载） | ✅ 移植（落本地缓存 + 相册保存） |

图片能力挂在哪：对话页的「附件 / 生成」入口 + 网关 Tab 的模型页（图片模型的能力位见 §5.4.1）。

### 决定六：添加账号走**三级页面**，授权用弹窗，H5 用现成 Compose 容器

```
① 设置 → 网关 → 账号 → [+ 添加账号]
      ↓ 二级：提供商列表（很多家，可搜索，带图标与「可用能力」标记）
   选一家
      ↓ 三级：该家的登录方式列表
   选一种
      ↓ 弹窗：授权窗（Miuix Dialog，不是新页面）
        · OAuth 型 → 按钮「打开授权页」→ 用 App 内 H5 容器打开
        · API 型 → 表单（MiuixTextField，含手机号验证码等 prompts）
      ↓ 成功后回填账号 → 返回账号列表并高亮新账号
```

三条硬要求：
1. **授权窗必须是弹窗**（`MiuixDialog` / `SuperDialog`），与网关原版一致：挂在三级页之上，取消即回三级页；
2. **「浏览器打开」那种，点按钮用 App 自己的 H5 容器**，不要跳系统浏览器（用户明确要求）；
3. **H5 容器用现成的 Compose 库**，不要自己写 `AndroidView { WebView }` 包一层（选型见 §9.10）。

### 决定七：**已选路线 2** —— provider 实现编成 Rust `.so` 走 JNI，Praxis 转为 AGPL-3.0

查了 `plugin_host/compatibility.rs:12`：`OFFICIAL_PACKS = ["agent2api-providers", "agent2api-web-providers"]`。
网关（Agent2API）与插件仓库（Agent2Providers）都是 **AGPL-3.0**，Praxis 是 **MIT**。你已决定接受 AGPL：把 21 家实现作为 Rust `cdylib` 链进 App、通过 8 个 JNI 函数调用，**不做插件进程、不做 RPC、不做 pack/签名**。代价是 Praxis 转 AGPL-3.0（执行清单见 §5.14.5）。详见 §5.14。
P5 起做 `rust/praxis-providers`（cdylib + JNI）与 `core:provider`（Kotlin 侧 JNI 封装），一次性拿到 21 家。

### 决定八：取色走**现有莫奈**，不自建

Praxis 已经具备：`ColorSource.Wallpaper`（= 莫奈动态取色，走 miuix 的 `platformDynamicColors`）、
`ColorSource.Custom`（品牌色种子）、9 种 `PaletteStyle`、两代 `ColorSpecVersion`，
依赖里也有 `materialKolor 5.0.0`。所以网关页面**不需要任何取色代码** ——
只要不硬编码颜色、一律用 `MiuixTheme.colorScheme`，就自动跟随系统壁纸。

### 决定九：**该有动画的必须有动画**

Praxis 的设计约定是「折叠与弹层必须有动效」。网关页里必须做动画的地方：

| 场景 | 用什么 |
|---|---|
| 三级页面跳转 | Praxis 现有 `NavDisplay` + `NavTransitions`（跟随全局 page transition） |
| 授权窗进出 | `MiuixDialog` / `MiuixAnimatedDialog`（常驻挂载 + `visible` 参数） |
| 展开账号详情 / 高级设置 | `FoldableContent`（expandVertically + fadeIn） |
| 列表项增删（账号、映射） | `animateItem()` / 现有列表动画 |
| 状态灯切换（正常→冷却） | 颜色过渡用 `animateColor`（**已显式加了 `compose.animation` 依赖**） |
| 导入进度 | `ProgressButton` / `MiuixBlockingLoadingDialog` |

**禁止**：`if (visible) { Dialog() }`（退场动画来不及播）。
## 2. 功能对照总表（20 组 × Praxis 落点）

图例：**移植** = 完整实现；**降级** = 部分能力（说明里写清）；**不移植** = 语义不存在于手机端。

| # | 网关功能组 | Praxis 落点 | 结论 | 阶段 |
|---|---|---|---|---|
| 1 | `/v1/*` 协议面（Chat / Responses / Anthropic / Images / models） | `core:ai` + `core:gateway`；**不对外开端口**（P6 可选） | 移植 | P1/P3 |
| 2 | 账号池 | 设置 → 网关 → 账号页 + Room 表 | 移植（**仅 API Key 型**） | P1 |
| 3 | 自定义提供商 | 设置 → 网关 → 提供商页 + 设置键 | 移植 | P1 |
| 4 | 模型管理（映射 / 能力位 / 启停 / 测试） | 设置 → 网关 → 模型页；对话页模型选择器联动 | 移植（**需先把模型条目从字符串升级为结构化记录**，见 §5.4.1） | P2 |
| 5 | 路由（策略 / 队列 / 组 / 粘性 / 回放） | 设置 → 网关 → 路由页（4 个子页） | 移植（回放降级为最近 N 条） | P2 |
| 6 | 网关 Key | 设置 → 网关 → Key 页（仅 P6 对外时需要） | 降级（默认隐藏） | P6 |
| 7 | 请求统计 / 报表 | 设置 → 网关 → 报表页（Room 聚合） | 移植 | P3 |
| 8 | 日志 | 并入现有「日志」页，加来源筛选（不做独立页） | 移植 | P3 |
| 9 | 代理池 | 设置 → 网关 → 代理页；OkHttp `Proxy` + 每账号绑定 | 移植 | P3 |
| 10 | Agent 接管 | **不移植**（Praxis 自己就是客户端，接管本机 agent 配置没有对应场景） | 不移植 | — |
| 11 | 工具服务（web search / vision / MCP / Skill） | 设置 → 工具；复用 `core:tool` | 移植（MCP/Skill 只做 API 型） | P4 |
| 12 | AI 助手（会话 / 图片） | Praxis 对话页本身就是 AI 助手 → **合并**，只搬「配置」与「图片生成」 | 降级合并 | P4 |
| 13 | 签到 / 活动 / 旅行 | 设置 → 网关 → 签到页（四分组 + 台账 + 排期 + 补签） | **移植（框架全量）**，逐家领取按需实现 | P5 |
| 14 | 插件（provider 包） | **不做插件进程**：21 家实现编成 `libpraxis_providers.so`，JNI 直接调（§5.14） | **移植（路线 2）**，Praxis 转 AGPL-3.0 | P5 |
| 15 | 云同步（WebDAV / S3） | 设置 → 同步（网关配置也纳入同步范围） | 移植 | P4 |
| 16 | 备份 / 恢复 | 设置 → 备份（整库 + 网关配置） | 移植 | P4 |
| 17 | 面板安全（登录 / 密码 / 验证码） | **不移植**（App 没有第二个人登录） | 不移植 | — |
| 18 | 系统设置（超时 / 重试 / 队列 / 图片压缩 / 脱敏 / 提示词 / CORS…） | 设置 → 网关 → 系统（逐项挑能用的） | 移植（逐项过滤） | P2/P4 |
| 19 | 升级 / 更新 | **不移植**（Praxis 有自己的 CI 与 App 更新） | 不移植 | — |
| 20 | 从 cc-switch 扫描导入 | 导入来源之一（复用 §9 的导入通道） | 移植 | P1 |

**统计**：移植 **15** 组、降级 **2** 组（网关 Key / AI 助手）、不移植 **3** 组（Agent 接管 / 面板安全 / 升级更新）。下面逐组给设计与理由。

---

## 3. Praxis 页面现状（决定每块功能往哪放）

### 3.1 主壳与 Tab

`MainShellPage` 用 `MainTab` 枚举 + Pager 渲染四个 Tab，底栏在 Tab 间高度恒定；
`when (MainTab.of(page))` 是穷举的 —— **新增 Tab 忘写文案会编译失败**（这条约定对我们有利）。

| Tab | 内容 | 与网关的关系 |
|---|---|---|
| 对话 | `ChatTab` → `ChatScreen`（133 KB）+ 侧栏 `ChatSessionsPage` | **消费方**：模型选择器要改成「网关对外名」 |
| 终端 | `TerminalScreen`（66 KB，真 PTY） | 无关 |
| 记忆 | 记忆库 | 无关（网关不碰记忆） |
| 设置 | 19 个二级页 | **承载方**：模型配置要支持「网关档案」 |

### 3.2 设置页的 19 个二级页（现状）

| Route | 页面 | 与本次移植的关系 |
|---|---|---|
| SettingsModel | `ModelScreen`（69 KB，10 个 Section） | **要改**：加 `kind` 分叉 —— 选「网关」档案时，服务商/接口地址/API Key 三块换成一张「网关入口」卡片，模型自动填 |
| SettingsFunctionModel | 功能位（对话/摘要/标题/记忆各自的模型） | 网关模式下功能位指向「对外模型名」 |
| SettingsSystemPrompt | 系统提示词（三层 + 模板） | 复用，不动 |
| SettingsCharacters / CharacterEdit / PromptTags | 角色卡与提示词标签 | 复用，不动 |
| SettingsAppearance | 主题 / 动效 | 复用，不动 |
| SettingsTools / SettingsPermission | 工具与权限分档 | **要改**：接入网关的工具服务（web search / vision） |
| SettingsMemory | 记忆库设置 | 不动 |
| SettingsWorkflow | 工作流 | 不动 |
| SettingsSpeech | 语音播报 | 不动 |
| SettingsLinux / SettingsTerminal / SettingsPackages | 容器与终端 | 不动 |
| SettingsCompatPluginEntries | 兼容插件条目 | 不动（与网关插件不是一回事） |
| SettingsLog | 日志页（24 KB） | **要改**：加「网关」来源筛选 |
| SettingsAbout / OpenSourceLicense | 关于 | 加「网关档案导出」入口更自然 |

### 3.3 已有的可复用资产

| 资产 | 位置 | 用途 |
|---|---|---|
| 导出到 `exports/` | `PraxisApp.kt:1044-1068` | 网关配置导出直接复用 |
| 多配置档案 | `SettingsRepository` 的 `model_profile_*` + `p:<id>:*` | 网关档案复用同一机制 |
| 表格 + ⋯ 菜单 + 批量操作 | `MiuixDraggableList` + `MiuixSelectableRow` + `MiuixAnimatedPopup`（见 §9.3） | 账号池页直接用这套 |
| 分段/滑块/开关 | `SettingsSectionHeader` + Miuix 组件 | 路由策略页 |
| 日志列表 | `LogScreen` | 网关来源的日志（同一页，换筛选） |

---

## 4. 「设置 → 网关」的页面树

```
设置
└── 网关                     PraxisRoute.SettingsGateway
    ├── 概览                 今日请求 / 成功率 / 账号状态灯 / 最近失败
    ├── 账号                 列表 + 拖动排序 + 批量 + ⋯（测试 / 清空运行态 / 导出）
    ├── 路由                 策略 / 容错 / 候选队列
    ├── 模型                 映射 / 自定义 / 能力位 / 启停 / 测试
    ├── 报表                 今日 / 近 7 天 / 近 30 天
    ├── 代理                 出口池 + 每账号绑定
    ├── 签到                 四分组 + 台账 + 排期（P5）
    └── 导入导出             文件 / 剪贴板 / 备份包
```

**日志不在这里** —— 它并入「设置 → 日志」（决定三）。

**入口位置**：`SettingsHomeScreen` 的 `SettingsCard` 里，紧挨着 `SettingsEntry.Model` 之后
（模型配置页会指向它，两者要相邻）。

## 5. 逐组设计（20 组）

### 5.1 组一：协议面 `/v1/*`

**网关端点**：`/v1/chat/completions`、`/v1/responses`、`/v1/messages`、`/v1/messages/count_tokens`、
`/v1/models`、`/v1/images/generations`、`/v1/images/edits`、`/v1/images/files/{id}`。

**能力**：四类入口（OpenAI Chat / OpenAI Responses / Anthropic Messages / Images）+ 模型目录。
网关内部的统一形态是「Chat」，其余协议在出入口做转换。

**Praxis 落点**：`core:ai` 加编码器与组装器；**App 内不监听端口**（P6 才考虑）。

| 协议 | 出站编码 | 入站组装 | 阶段 |
|---|---|---|---|
| OpenAI Chat | 已有（`OpenAiCompatProvider.buildRequestBody`）→ 抽到 `core:ai` | 已有 `OpenAiStreamAssembler` | P1 |
| Anthropic Messages | 新增 `AnthropicRequestBuilder` | 新增 `AnthropicStreamAssembler` | P3 |
| Responses | 新增 `ResponsesRequestBuilder` | 新增 `ResponsesStreamAssembler` | P3 |
| Images | 新增 `ImagesClient` | 非流式，直接解析 | P4 |

**移植要点（网关踩过的坑）**

1. **Anthropic 流式 usage 必须齐**：`message_start` 带 `input_tokens` 与 `cache_read_input_tokens`，
   `message_delta` 带 `output_tokens`。少一个客户端缓存率恒 0（网关 issue #424）。
2. **工具声明按名去重**：多条 `additional_tools` 注入会把重名送给上游 → 400（#513）。
3. **工具参数是分片到达的**：按 `index` 累积到 `finish_reason` 才发 `ToolCalls`（两种协议都一样）。
4. **带图请求先按能力位过滤**：明确声明不支持的账号直接跳过（#408：一次请求白撞 9 次）。

### 5.2 组二：账号池

**网关端点**（6 条主路由 + 子路径）：`/api/accounts`（CRUD）、`/batch`、`/export`、`/import`、
`/refresh`、`/refresh-expiring`、`/usage`、`/growth`、`/sorting`、`/move`、`/connections`、
`/rate-limits/clear`、`/route-state/clear`、`/checkin`、`/subscriptions`。

**能力**：多账号 CRUD、批量（启停/代理/并发/限制器/刷新/删除）、导入导出、余额查询、
排序（优先级）、凭证刷新、限流标记清理、**运行态清理**（退避/垫底/熔断）。

**Praxis 落点**：设置 → 网关 → 账号页；Room 表 `gateway_account`（见 §6.1）。

**移植边界**

| 能力 | 一期 | 说明 |
|---|---|---|
| CRUD + 启停 + 排序 + 批量 | ✅ | 批量操作要**逐条结果**（refreshed / failed / skipped），不能一句「成功 10 个」 |
| 凭证刷新 | ✅ 仅 API Key 型 | OAuth 型的续期实现在插件仓库 |
| 余额查询 | ⚠️ 仅自定义家 | 内置家的余额接口在插件里 |
| 运行态清理 | ✅ | 对应网关 #293：清 backoff/sink/breaker，**不清 429 限额** |
| 签到 / 活动 / 订阅 | ❌ 账号页不放 | 签到有自己的入口（组十三，P5 实现） |

### 5.3 组三：自定义提供商

**网关端点**：`/api/custom-providers`（GET/POST）、`/fetch-models`、`/models`、`/update`、`/remove`。

**记录形状**（与网关逐字一致）：

```json
{ "id": "custom-3f2a91b04c7e", "name": "我的网关", "baseUrl": "https://api.example.com/v1",
  "models":   [ { "id": "gpt-x", "enabled": true, "reasoning": "" } ],
  "mappings": [ { "alias": "fast", "target": "gpt-x", "enabled": true } ] }
```

**Praxis 落点**：设置 → 网关 → 账号页顶部的「提供商」区块，或独立子页；
数据直接复用 `ModelProfile` 的 baseUrl/模型池语义（它本来就干这事）。

**要点**：`fetch-models` 复用 `OkHttpModelCatalogFetcher` 与 `ModelCatalog`；
baseUrl 规范化沿用 `ChatEndpoint.resolve` 的四条规则（那条逻辑已经把三种写法都覆盖了）。

### 5.4 组四：模型管理

**网关端点**：`/api/models/manage`、`/mappings`、`/mappings/remove`、`/custom`、`/custom/remove`、
`/capabilities`、`/state`、`/metadata(+refresh)`、`/refresh`、`/test`、`/compact-routes`。

**能力**：模型清单（按家）、映射（别名→上游，可多对一）、自定义登记、能力位覆盖（图片/工具…）、
逐条启停、元数据（上下文长度/价格）、单个模型测试、压缩路由（上下文压缩请求指定模型）。

**Praxis 落点**：设置 → 网关 → 模型页；对话页的模型选择器消费「对外名」。

**要点**

- **映射大小写**：网关全链路 `eq_ignore_ascii_case`。别名与 id 只差大小写会互相遮蔽 → 配置时就要拦并说明。
- **能力位是三态**：支持 / 不支持 / 未声明。**未声明要放行**（缺键放行，明确不支持才跳过）——
  否则「目录没写」会被当成「不支持」，把本来能成的请求挡掉（#408 的反面）。
- **模型测试走真实链路**：会真打上游、烧额度。UI 上必须提示，且测试请求**不计入报表**。

#### 5.4.1 模型层差距清单（网关有 / Praxis 没有）

这一节单独列，因为**它是整份文档里差距最集中的一块**：
Praxis 的「模型」只是一个字符串（`ModelSettings.modelList: List<String>`），
而网关的「模型」是**一条结构化记录**。

> **架构前置条件**：不先把 Praxis 的模型条目从 `List<String>` 升级成结构化对象，
> 下面 20 条里至少 15 条无从谈起。这一步应在 P2 开头做，且要写迁移
> （老配置的 `modelList` / `modelIndex` → 新结构，索引语义保留）。

**A. 模型条目本身**

| # | 能力 | 网关怎么做 | Praxis 现状 |
|---|---|---|---|
| 1 | 结构化条目 | `{id, name, provider, providerLabel, source, enabled, aliases[], kind, credits, capabilities…, maxInputTokens, maxOutputTokens, reasoning*}` | 一个字符串 |
| 2 | 逐模型启停 | `RuleEntry{provider?, id, enabled, reasoning}`，按「家 × 模型」 | 无 |
| 3 | 逐模型思考等级 | `mappings[].reasoning`，发送时由承载家的 `reasoning_patch` 翻译成本家字段 | 全局 `thinkingEffort` 四档（关/低/中/高） |
| 4 | 模型类型 | `kind: chat / image`（`images::model_is_image`） | 无 |
| 5 | 模型倍率 / 积分 | `credits`（面板展示，按模型） | 无 |

**B. 能力位与元数据**

| # | 能力 | 网关 | Praxis |
|---|---|---|---|
| 6 | 能力位六项 | `supportsToolCall` / `supportsImages` / `supportsVideo` / `supportsReasoning` / `supportsImageGeneration` / `supportsImageEditing` | 无 |
| 7 | 输入模态 | `input_modalities: [text, image, video…]` | 无 |
| 8 | 上下文 / 输出上限 | `max_input_tokens` / `max_output_tokens`（目录或 models.dev 提供） | 单个手填 `contextLimit` |
| 9 | models.dev 元数据 | `context` / `output` / `reasoning` / `tool_call` / `images` / `video` / `levels[]`，按「家清单 > models.dev > 用户覆盖」填充 | 无 |
| 10 | 思考等级候选 | `reasoning_efforts[]` / `reasoning_default_effort` / `reasoning_fixed_effort` / `reasoning_source` | 固定四档，不知道某家支持哪些 |
| 11 | 能力位用户覆盖 | `CapabilityOverride{provider, id, values}` | 无 |

**C. 映射与登记**

| # | 能力 | 网关 | Praxis |
|---|---|---|---|
| 12 | 模型映射 | `Mapping{alias, target, provider, reasoning, enabled}`，一个对外名可挂多条（跨家） | 无 |
| 13 | 自定义登记 | `CustomModel{provider, id}`（上游没广告但能路由） | 无 |
| 14 | 悬空映射识别 | `dangling`（映射指向的模型/家已不存在，界面单独一栏可删） | 无 |
| 15 | 每 Key 模型白名单 | `apiKeys[].allowedModels` | 无 |

**D. 发现与诊断**

| # | 能力 | 网关 | Praxis |
|---|---|---|---|
| 16 | 模型测试 | `POST /api/models/test`（795 行，走真实转发链路，钉家钉账号，支持三协议） | 无 |
| 17 | 清单刷新 | 按家拉 `/models` + 快照 + 缓存 + 失败诊断，刷新失败保留旧清单 | `ModelCatalog` 拉一次即用，无快照 |
| 18 | 备援名 | 上游清单里没有、但请求名可被映射到已知真名（`payload.rs` 的备援扩池） | 无 |
| 19 | 未知模型建议 | `suggest_from`：按编辑距离 + 公共前缀给「你是不是想找 X」 | 无 |
| 20 | 上游 token 计数 | `POST /v1/messages/count_tokens`（可走上游真实计数） | 本地 `TokenEstimator` 估算 |

**优先级建议**

| 必须做（否则网关模式不可用） | 可延后 | 建议不做 |
|---|---|---|
| 1 结构化条目、2 逐模型启停、6/7 能力位、12 映射、16 模型测试、17 清单刷新 | 3 逐模型思考等级、8/9 元数据、14 悬空映射、19 未知模型建议 | 5 倍率、15 Key 白名单（P6 才需要）、20 上游计数（本地估算够用） |

**为什么「16 模型测试」必须做**：门户里的模型清单永远可能不准（上游下架、账号无权、家没开）。
用户遇到 404 时的第一反应是「是不是模型名错了」，没有测试按钮就只能靠猜。
网关那颗按钮走的是**真实转发链路**（同一个 `UpstreamService::forward`），所以它测出来的结论与生产一致 ——
Praxis 移植时也要走 `GatewayProvider`，不要另写一条「内部探测」。
### 5.5 组五：路由

**网关端点**：`/api/routing`、`/queue`、`/workspace`、`/groups`、`/preview`、`/profile`、
`/sticky`、`/sticky/clear`、`/flow`、`/trace`、`/trace/request`、`/replays`。

**能力**：策略（顺序/轮询）、会话粘性、失败退避、垫底、熔断；候选队列可视化（谁被跳过、为什么）；
路由组（跨家组合成一个对外名）；粘性清理；流量动画；请求 trace；历史回放。

**Praxis 落点**：设置 → 网关 → 路由页（4 个子页：策略 / 队列 / 路由组 / 回放）。

**移植优先级**：策略与队列可视化 P2；路由组 P2 后半；flow/trace 动画 P5（纯观赏）；回放降级为最近 50 条。

### 5.6 组六：网关 Key

**网关端点**：`/api/keys`、`/api/keys/{id}`（含 `allowedProviders` / `allowedModels`）。

**Praxis 落点**：**默认不显示**。只有 P6 决定「手机也能对外当网关」时才需要。
客户端内路由不需要 Key —— 那是给外部调用方鉴权用的。

### 5.7 组七：请求统计 / 报表

**网关端点**：`/api/stats/summary`、`/requests`、`/requests/filters`、`/requests/raw`、
`/requests/terminate`、`/requests/compact`、`/requests/clear-preview`、`/api/retention`、`/api/usage`。

**能力**：请求量 / 成功率 / token / 缓存命中率 / 365 天热力图；按模型/提供商/Key/客户端筛选；
进行中请求可终止；原始报文（debug）；数据保留期与压缩。

**Praxis 落点**：设置 → 网关 → 报表页；Room 表 `gateway_request` + 日聚合表。

**要点**

- **日界线按本地时区**（网关 #329 的真实事故：容器没设 TZ，UTC+8 用户的「今天」实际是昨天 08:00 到今天 08:00）。
  App 里 `LocalDate` 直接可用，但**聚合键要显式用本地时区生成**，别用 UTC。
- **失败请求 token 清零**：上游错误时 usage 常缺失，留着半截会让「失败暴增」看起来像「用量暴增」。
- **测试请求不入报表**（`is_test` 标记），否则人工样本会把趋势读歪。

### 5.8 组八：日志

**网关端点**：`/api/logs`、`/logs/stats`、`/logs/download`、`/logs/{*rest}`。

**Praxis 落点**：复用现有 `SettingsLog` 页，加一个来源筛选（App 日志 / 网关日志）。
网关日志的「请求明细」与报表的「请求列表」是两份东西：日志是文本流，报表是结构化记录 —— 别合并。

### 5.9 组九：代理池

**网关端点**：`/api/proxies`、`/pool`、`/pool/enable`、`/pool/remove`、`/pool/sync-clash`、
`/pool/test`、`/pool/update`、`/settings`、`/test`。

**能力**：命名出口节点 CRUD、批量测速、Clash 订阅同步、**每账号绑定出口**（轮询档）。

**Praxis 落点**：设置 → 网关 → 代理页；实现挂到 OkHttp（`Proxy` + `ProxyAuthenticator`），
选择粒度是「每账号」——与网关一致（`base_url_override` 与 proxy 都是账号级）。

**要点**：出口与账号绑定后，**余额查询、拉模型清单、转发必须走同一个出口**，
否则会出现「清单能拉、请求 403」这种最难查的问题（网关在 `credential_of_record` 里专门统一过口径）。

### 5.10 组十：Agent 接管（不移植）

**网关端点**：`/api/agents`、`/apply`、`/import`、`/preview`、`/profiles`、`/restore`、`/{id}/resources(+apply/preview)`。

**它做什么**：把网关的地址与 Key 写进**本机已安装的 agent 客户端配置**（Claude Code、Codex、Cursor…），
支持预览写入、备份还原。

**为什么手机端不移植**：Praxis 自己就是那个「客户端」，没有「接管另一个客户端」的场景；
Android 上也装不了 Claude Code / Codex 这类 CLI。

**替代**：如果将来要做「把 Praxis 的模型配置推给别人」，走 §9 的导出即可。

### 5.11 组十一：工具服务（web search / vision / MCP / Skill）

**网关端点**：`/api/tools/providers`、`/providers/{provider}/key`、`/providers/{provider}/test`、
`/api-keys`、`/provider-selection`、`/servers`、`/servers/{id}`、`/install-skill`、`/install-mcp`、
`/uninstall-*`、`/install-targets`、`/install-info`、`/vision/config(+test)`、`/mcp-bridge/vision.js`、
`/api/web-search(+search/fetch)`、`/skill/web-search(+search/fetch)`。

**能力**：四家搜索提供商（密钥/选择/连通性测试）、视觉分析（绑模型）、
把 Skill/MCP 配置**安装到本机客户端**、以及给下游 Agent 用的 `/skill/web-search` 端点。

**Praxis 落点**：设置 → 工具（现有页）；`core:tool` 已有工具注册与权限分档。

**移植范围**

| 能力 | 结论 |
|---|---|
| 搜索提供商配置 + 测试 | ✅ 移植（对应 Praxis 的工具提供方） |
| 视觉分析（截图理解） | ✅ 移植，Praxis 有 `ScreenCapture`，天然契合 |
| `/skill/web-search` 端点 | ❌ 不移植（那是给外部 Agent 调的 HTTP 端点） |
| 安装 Skill/MCP 到本机客户端 | ❌ 不移植（手机上没有那些客户端） |

### 5.12 组十二：AI 助手 —— **只移植图片生成 / 编辑**

**网关端点**：`/api/assistant/chat`、`/config`、`/sessions(+{id})`、`/fork`、`/title`、
`/images/generations`、`/images/edits`、`/images/files/{id}`。

**判定**：对话与会话管理**整组不移植**（Praxis 对话页已覆盖且更完整：消息变体、分支、Markdown、
工具调用展示、附件）。配置部分也跳过（`FunctionSlot` 比它更细）。

**只搬这三条图片链**：

| 端点 | 用途 | Praxis 落点 |
|---|---|---|
| `POST /v1/images/generations` | 文生图 | 对话页「生成图片」入口 / 网关模型页的图片模型 |
| `POST /v1/images/edits` | 参考图编辑 | 同上（带参考图） |
| `GET /v1/images/files/{id}` | 取回生成结果 | 落 `cacheDir` + 可保存到相册（Praxis 已有 `ImageSaverPlugin` 同款能力） |

**实现要点**

1. 非流式：图片接口返回 JSON（不是 SSE），直接解析，不要套 `SseParser`。
2. 结果可能是 `b64_json` 或 URL 两种形态，都要处理；URL 形态要复用同一出口代理（§5.9）。
3. 模型能力位 `supportsImageGeneration / supportsImageEditing` 决定该模型能否出现在图片入口（§5.4.1）。
4. 生成的图存 `cacheDir`，**不进对话历史正文**（否则会话库会被 base64 撑爆）。

### 5.13 组十三：签到体系（**移植**：框架全量 + 按家实现）

**网关端点**（18 条）：`/api/checkin`、`/checkin/status`、`/checkin/claim-and-report`、
`/checkin-center`、`/checkin-records(+/{*rest})`、`/checkin-schedules(+/{*rest})`、
`/checkin-activity`、`/checkin-keepalive`、`/auto-checkin(+run)`、`/travel-schedule(+run)`、`/activity/{ambassador,banner}`。

**先纠正一个判断**：这一组的**框架全部在主仓**，只有「每家的领取 HTTP 调用」在插件仓库。
可移植的正是最有价值的那部分：

| 主仓里的能力 | 文件 | 移植价值 |
|---|---|---|
| 多条目排期（时段 + 逐家时刻覆盖） | `checkin_schedules.rs`（623 行） | ★★★ |
| **逐账号随机落点**（窗口内错峰） | `checkin_schedules/account_plan.rs`（275 行） | ★★★ |
| 补签轮（时刻 / 间隔 / 每账号上限） | 同上 + `auto_checkin/retry.rs` | ★★★ |
| 定时循环（30s tick、总开关、启动补跑） | `auto_checkin/{scheduler,execution}.rs` | ★★★ |
| 明细台账（账号 × 天 × 来源 × 结果） | `checkin_records.rs`（469 行） | ★★★ |
| 签到中心四分组（每日/新手/套餐/成长） | `api/checkin_center.rs` | ★★☆ |
| 目标集合判定（可用 ∩ 可签 ∩ 范围 − 排除名单） | `billing/checkin::resolve_checkin_targets` | ★★★ |
| 旅行/成长任务调度 | `growth_scheduler.rs` | ★★☆ |
| 每家的领取实现 | **插件仓库** | 只能逐家写 |

#### 数据模型（照搬网关的形状）

```kotlin
data class CheckinSchedule(
    val id: String, val label: String = "", val enabled: Boolean = false,
    val time: String,                 // "00:01" 北京时间
    val timeEnd: String,              // 窗口结束；空 = 单点
    val providers: List<String>,
    val providerTimes: Map<String,String> = emptyMap(),  // 逐家时刻覆盖
    val retryTime: String = "00:10",  // 补签轮；空 = 关闭
    val retryGapMinutes: Int = 10,    // 中性未领取的重试间隔
    val retryLimit: Int = 3,          // 每账号每天重试上限
    val lastFiredDate: String? = null,
    val lastFiredProviders: Map<String,String> = emptyMap(),
    val lastRetriedProviders: Map<String,String> = emptyMap(),
    val accountPlan: AccountPlan = AccountPlan(),
    val lastResult: JsonObject? = null,
)

data class AccountPlan(
    val date: String = "",
    val accounts: Map<String, PlannedAccount> = emptyMap(),  // accountId -> 落点
    val windows: Map<String, Pair<Long,Long>> = emptyMap(),  // provider -> (起,止)
)
data class PlannedAccount(val provider: String, val at: Long, val attemptedAt: Long? = null)
```

**这张 `AccountPlan` 是签到调度最巧的一块**：它把「哪家、哪个账号、几点几分」
在当天**随机**铺进窗口，重启/休眠后从墙上时钟重判、只跑已到点且没跑过的。
移植时原样照抄（纯函数，`due(now)` / `pendingFor` / `nextAt` 都能单测）。

#### 台账（对应网关 `checkin_records` 表）

```kotlin
@Entity(tableName = "checkin_record")
data class CheckinRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String, val provider: String,
    val day: String,                  // 北京时间的 yyyy-MM-dd
    val source: String,               // schedule | makeup | startup | manual | single | activity
    val outcome: String,              // success | already | pending | captcha | failed
    val message: String?, val benefit: String?,
    val at: Long,
)
```

来源枚举与结果判据**必须与调度侧共用同一份函数**：网关在这里踩过坑 ——
「今天已完成」的口径若台账与调度各写一份，迟早分叉，表现为「记录说签过、调度又去签」。

#### 签到的五类结果（决定后续动作）

| outcome | 含义 | 后续 |
|---|---|---|
| `success` | 领到了 | 记 `checkinAt`，本轮结束 |
| `already` | 今天已领过（上游明确告知） | 视为成功终态，**不重试** |
| `pending` | 窗口未开 / 上游还没下发 | 进**补签轮**，按 gap 与 limit 重试 |
| `captcha` | 需要人工验证（滑块/短信） | 发通知让人来处理，**不自动重试** |
| `failed` | 真失败（401 / 400 / 网络） | 按失败类型：401 标 `needsRelogin`，其余记错误 |

#### 在 Android 上必须改的一件事：调度器怎么活

网关是常驻进程，30 秒 tick 就行。**手机不行**（Doze / LMK / 厂商省电）。所以：

| 场景 | 方案 |
|---|---|
| App 在前台 | 现有 30s tick 照跑 |
| App 在后台 | `WorkManager` 周期性任务（最小 15 分钟）+ 精确闹钟（`AlarmManager.setExactAndAllowWhileIdle`）落在**条目时刻**上 |
| 打开 App | **补跑**：`AccountPlan.due(now)` 把今天已过点未执行的补上（网关的 startup 补签同款） |
| 关掉所有权限 | 明确降级：只在前台跑，并在页面顶部说明「后台签到需要通知/精确闹钟权限」 |

> 这三条要写进产品文案。**不要做成「看起来在后台签到、其实没跑」** ——
> 那比不做更糟，用户会以为额度领到了。

#### 每家怎么实现（这是唯一需要逐家写的部分）

定义一个 port，与 Praxis 现有的端口模式一致：

```kotlin
interface CheckinProvider {
    val providerId: String
    /** 声明自己能签什么（对应网关插件的 daily/plans/onboarding/growth 四位） */
    val capabilities: CheckinCapabilities
    /** 签一个账号；返回 success / already / pending / captcha / failed */
    suspend fun checkin(account: ProviderAccount): CheckinOutcome
    /** 可选：套餐明细 / 新手任务 / 成长任务 */
    suspend fun plans(account: ProviderAccount): List<PlanItem> = emptyList()
    suspend fun growth(account: ProviderAccount): GrowthSnapshot? = null
}
```

**一期落地建议（按性价比排序）**

| 家 | 形态 | 一期 |
|---|---|---|
| 自定义提供商（API Key） | 用户自己填的端点，多数是 `/user/balance` 或自家签到接口 | ⚠️ 无法通用，只能提供「自定义签到脚本」入口（见下） |
| 通用「HTTP 签到任务」 | 用户配 URL + method + headers + body 模板 + 成功判定正则 | ★★★ **一期主力**：一份实现覆盖所有 REST 型 |
| 各家官方 API | 需要逆向 | 后期逐家 |

> 这条很关键：**先做「通用 HTTP 签到任务」**（用户在 Praxis 里填一个请求模板，
> 用哪个账号的凭证、成功怎么判定），就能立刻覆盖一大批自建/中转服务；
> 而逐家逆向可以慢慢来。这与网关「provider 实现放插件」的思路一致，只是插件形态换成了「用户可配的任务」。

#### 签到中心 UI（四分组照搬）

| 分组 | 内容 | 依赖 |
|---|---|---|
| 每日签到 | 按提供商分组的账号列表，显示今日状态/连登天数，支持单账号「立即签到」 | 通用 |
| 新手任务 | 一次性福利（首登奖励等） | provider 申报 |
| 套餐明细 | 活动套餐与领取窗口 | provider 申报 |
| 成长任务 | 连登/活跃度/旅行 | provider 申报 |

#### 必做的两个开关（都是网关 issue 的产物）

1. **账号级排除名单**（#447）：某些账号天生签不了（例如企业号统一报 400），
   应能把它排除出「定时 + 全部签到」，但**不影响单账号手动签到**。
2. **签到结果推送**（#462）：一次批量/定时跑完推一条通知（成功几个、失败几个、首个失败原因）。
   Praxis 已有通知权限体系，直接复用。

#### 与其它组的关系

- **需要账号池**（组二）提供凭证 → 排在 P1 之后；
- **需要通知**（Praxis 已有）做结果推送；
- **不需要**网关要路由/协议面。

**阶段**：P5（框架 + 通用 HTTP 任务 + 每日签到分组 + 台账 + 排除名单 + 推送），逐家实现按需追加。
### 5.14 组十四：provider 实现 —— **已选定路线 2：Rust 内核编 `.so` + JNI**

> **决策**：Praxis 接受 **AGPL-3.0**，把 21 家 provider 实现作为 Rust `cdylib` 链进 App，
> 通过窄 JNI 调用。**不做插件进程、不做 RPC、不做 pack/签名。**

#### 5.14.1 为什么是它（三条理由）

| 理由 | 说明 |
|---|---|
| 一次性拿到 21 家 | 不用逐家重写（那要 3~5 天/家，21 家不现实） |
| 协议永远对齐 | 直接依赖官方 `provider-protocol` / `provider-sdk`；上游改协议只需升 tag |
| 比插件形态少一大坨 | 帧协议（`rpc.rs` 1965 行）、22 个 RPC 方法、`pack.json`、ed25519 验签、版本目录回滚、子进程生命周期、`target_triple` —— **全部不需要** |

代价：**Praxis 整体转为 AGPL-3.0**（执行清单见 §5.14.5），以及 15~20 MB/ABI 的体积。

#### 5.14.2 目标架构

```
Praxis (Kotlin)
  └── core:provider/GatewayNative          ← JNI 声明 + JSON 编解码 + 协程桥
         │  (JNI)
  └── libpraxis_providers.so               ← jniLibs/arm64-v8a/（Rust cdylib）
         ├── agent2api-provider-protocol   ← 契约类型（git tag 依赖）
         ├── agent2api-provider-sdk        ← Provider trait
         └── agent2api-providers::providers::registry()   ← 21 家的实现
```

**和网关的插件宿主相比**：宿主侧那 1965 行的 `rpc.rs` 被「JNI 直接调函数」替代；
插件侧的 `main.rs`（`serve()` 主循环）不参与编译，只借用它的 `providers/` 模块。

#### 5.14.3 必须改上游的一点（fork 后打补丁）

`crates/providers` 现在**只有 bin 目标**：

```toml
[[bin]]
name = "agent2api-providers"
path = "src/main.rs"
```

要当库用，fork 里加一个 lib 目标：

```toml
[lib]
name = "agent2api_providers"
path = "src/lib.rs"
crate-type = ["rlib"]
```

`src/lib.rs` 只需把 `main.rs` 里的 `mod` 声明搬过去并 `pub` 出去（`providers::registry()` 就是入口）：

```rust
pub mod providers;      // registry() -> Vec<Box<dyn Provider>>
pub mod http; pub mod sse; pub mod crypto; /* … 其余同 main.rs 的模块表 */
```

> `main.rs` 保持原样继续产出插件二进制 —— **fork 同时支持两种形态**，不破坏上游用途。
> 注意 crate 的 `#![deny(clippy::unwrap_used, …)]` 是写在 `main.rs` 的，lib 里要自己再写一遍（照抄即可）。

#### 5.14.4 JNI 边界（只暴露 8 个函数，不要照搬 22 个 RPC 方法）

| JNI 函数 | 签名（JSON in/out） | 用途 |
|---|---|---|
| `nativeInit` | `(dataDir, proxy, hostVersion) -> bool` | 建**常驻 tokio runtime** + 注册表；只调一次 |
| `nativeProviders` | `() -> String` | `[{id,label,capabilities}]` |
| `nativeModelsList` | `(providerId, accountJson) -> String` | 拉模型目录 |
| `nativeModelsRefresh` | `(providerId, accountJson) -> String` | 强制刷新目录 |
| `nativeBuildChatRequest` | `(providerId, requestJson) -> String` | 构造上游请求（URL/头/体） |
| `nativeClassify` | `(providerId, status, headersJson, body) -> String` | 错误分类 |
| `nativeToken` | `(providerId, accountJson, op) -> String` | `refresh` / `expiring` 两用 |
| `nativeChat` | `(providerId, requestJson, callback) -> handle` | **流式**：Rust 逐帧回调 Kotlin |

**为什么能砍到 8 个**：网关那 22 个里有一半是「桌面网关的业务概念」（`ADMIN_ACTION`、`CLAIM_STATUS`、`ACCOUNT_VIEW`、`USAGE_QUERY` 等）；
Praxis 的账号/余额/签到有自己的模型，只需要 provider 的**协议层**能力。
先做这 8 个，其余按需再加。

**流式的桥接（唯一有难度的部分）**

```kotlin
// Kotlin 侧：callbackFlow 包一层
fun chat(providerId: String, req: String): Flow<Frame> = callbackFlow {
    val handle = GatewayNative.chat(providerId, req, object : FrameSink {
        override fun onFrame(bytes: ByteArray, isEnd: Boolean) {
            trySend(Frame(bytes, isEnd)); if (isEnd) close()
        }
    })
    awaitClose { GatewayNative.cancel(handle) }
}
```

Rust 侧要点：`JavaVM::attach_current_thread` + `GlobalRef`（必须 `delete_local_ref`/`delete_global_ref`，否则泄漏）；
回调在 tokio 任务里、不是 JNI 调用线程，**不要**在回调里抛 Java 异常。

#### 5.14.5 AGPL 执行清单（**必做，不是可选**）

| # | 动作 | 为什么 |
|---|---|---|
| 1 | `LICENSE` 换成 **AGPL-3.0** 全文 | 链接 AGPL 代码后，Praxis 必须整体以 AGPL 发布 |
| 2 | `README` / App「关于」/「开源许可」页写明 Praxis 现为 AGPL-3.0 | 用户与分发渠道需要知道 |
| 3 | 每次发版**提供对应源码**：同一 tag 的仓库 + Release 挂 `Source code` | AGPL §4/§6 的分发义务 |
| 4 | 保留 Agent2API / Agent2Providers 的版权与许可声明（`NOTICE` / `licenses/`） | 原作者署名 |
| 5 | App 内加「查看源码」入口（链接到对应 tag） | 最省事的合规做法 |
| 6 | 若 P6 做内嵌 HTTP 网关对外提供服务 → 按 AGPL §13 向**网络用户**提供源码 | 网络交互条款 |
| 7 | 检查 Praxis 现有依赖是否与 AGPL 兼容 | MIT / Apache-2.0 都兼容（单向）；只需确认没有「禁止用于 AGPL」的条款 |
| 8 | Play Store 上架：描述里给源码链接 | 平台允许 AGPL，但会核 |

> ⚠️ 换许可这件事**不可逆**（已发布的 AGPL 版本收不回来）。建议在动手前先跟贡献者确认 ——
> 如果 Praxis 有多位贡献者，需要他们同意换许可。

#### 5.14.6 交叉编译与产物体积

| 项 | 做法 |
|---|---|
| 工具链 | `cargo-ndk`（NDK r27+）+ target `aarch64-linux-android` |
| 产物名 | **必须叫 `lib*.so`** 才能进 `jniLibs`（如 `libpraxis_providers.so`） |
| APK 设置 | `android:extractNativeLibs="true"`（AGP：`useLegacyPackaging = true`） |
| ABI | 只发 `arm64-v8a`（minSdk 28 上占绝大多数）；需要再补 `armeabi-v7a` |
| 体积 | 估计 15~20 MB/ABI（官方插件二进制未压缩约 13 MB + JNI 层）；用 AAB 分发可按 ABI 拆分 |
| 已知坑 | `ring`/`rustls` 需要 NDK clang（`CC_aarch64_linux_android`）；git 依赖要 `CARGO_NET_GIT_FETCH_WITH_CLI=true` 或 vendor |

#### 5.14.7 与其它组的衔接

| 组 | 关系 |
|---|---|
| 账号池（组二） | provider 只提供「怎么调上游」；账号存储/加密/排队仍在 Kotlin |
| 模型管理（组四） | 能力位来自 `nativeProviders()` 的 `capabilities`，与用户覆盖合并 |
| 签到（组十三） | 一期的「通用 HTTP 签到任务」不变；后续可换成 provider 的 checkin 能力 |
| 插件页（组十四本身） | **不需要了** —— 没有可装卸的插件；改成「provider 版本」只读信息 |
### 5.15 组十五：云同步

**网关端点**：`/api/cloud-sync`、`/upload`、`/download`、`/remote`、`/test`、`/import-key`。

**能力**：WebDAV / S3 双后端；上传整份快照；远端列表；下载并导入；密钥导入。

**Praxis 落点**：设置 → 同步（新增）。范围 = 网关档案（账号/映射/路由）+ 对话数据（可选）。

**要点**（网关 #502 的真实事故）：**内容没变就不要传**。快照要做内容指纹，
否则空闲时每 16 秒重传 3.5 MB（一天 20 GB）。

### 5.16 组十六：备份 / 恢复

**网关端点**：`/api/backup/export`、`/import`、`/upload`、`/download`、`/progress`。

**Praxis 落点**：设置 → 备份。范围 = Room 整库（会话/记忆/网关请求日志）+ DataStore 配置。

**要点**：导入是**破坏性操作**，必须：先备份当前 → 二次确认 → 进度 → 完成后重启数据层。
网关的「导入前自动备份 + 进度回报」这套值得照搬。

### 5.17 组十七：面板安全（不移植）

**网关端点**（19 条）：`/api/panel/login|logout|refresh|status|setup|account|change-password|captcha|cookie-probe`、
`/api/session/*`（登录方法、OAuth、短信、回调、刷新、登出）、`/auth/login|logout|callback`。

**为什么不做**：那是「浏览器访问一个共享面板」的鉴权体系。手机 App 只有一个人用，
且系统层已有锁屏与生物识别 —— 再造一套面板密码只是增加摩擦。

### 5.18 组十八：系统设置（逐项过滤）

**网关端点**（18 条）：`/api/config`、`/sanitize`、`/prompt`、`/image-compress`、`/captcha`、`/cors`、
`/debug`、`/developer-role-compat`、`/cline/headers`、`/queue`、`/retry`、`/timeouts`、
`/keepalive-strip`、`/auto-continue`、`/client-identification`、`/model-access`、`/sidebar-layout`、
`/storage`、`/system-info`、`/health`。

| 项 | Praxis | 结论 |
|---|---|---|
| 超时（connect / headers / streamIdle / body） | OkHttp | ✅ 移植（现在 `NetworkModule` 写死 15/120） |
| 重试（次数 / 换号数 / 间隔 / 不重试状态码） | `core:gateway` | ✅ 移植 |
| 请求队列（最多等待 / 等待秒数） | 现有 `RequestThrottle` 扩展 | ✅ 移植 |
| 图片压缩（开关 / 长边 / 质量 / 阈值） | 附件处理 | ✅ 移植 |
| 脱敏指纹（sanitize rules） | `core:ai` 出站前改写 | ✅ 移植（默认关闭，规则可编辑） |
| 提示词模式（replace / append…） | 系统提示词 | ⚠️ 部分（Praxis 已有三层提示词，语义不完全对等） |
| 自动续写（autoContinue + 轮数） | 对话页 | ✅ 移植（失败续写的产品体验价值高） |
| 客户端识别（内置/自定义规则） | 报表维度 | ⚠️ 可选（手机端来源单一，价值低） |
| debug 模式（原始报文） | 日志页 | ✅ 移植（脱敏后展示） |
| CORS / captcha / sidebar-layout / storage / system-info / health | — | ❌ 不移植（服务端专属） |

### 5.19 组十九：升级 / 更新（不移植）

**网关端点**：`/api/update/{*rest}`、`/api/upgrade`、`/api/upgrade/run`。

那是网关自己的「容器内一键更新二进制」。Praxis 走自己的 CI + App 更新，两套不要混。

### 5.20 组二十：从 cc-switch 扫描导入

**网关端点**：`/api/import/cc-switch`（GET）。

**能力**：扫描本机 cc-switch 的配置，转成网关的账号。

**Praxis**：**手机上没有 cc-switch**，但这条给了个好范式——「从别的工具导入」。
Praxis 的导入来源应改为：**Agent2API 导出**（主力）、**剪贴板 JSON**、**文件选择器**。

---

## 6. 数据模型（全量）

### 6.1 Room 新表

```kotlin
// 账号池（对应网关 account_store）
@Entity(tableName = "gateway_account")
data class GatewayAccountEntity(
    @PrimaryKey val id: String,        // 沿用网关 id，保证导出可回灌
    val providerId: String,            // 内置家 id 或 custom-xxxxxxxxxxxx
    val name: String,
    val baseUrlOverride: String?,
    val enabled: Boolean, val priority: Int, val maxConcurrent: Int,
    val credentialsCipher: String,     // 加密 JSON：{ apiKey } | { accessToken } …
    val expiringAt: Long?, val createdAt: Long,
)

// 自定义提供商（对应网关 customProviders）
@Entity(tableName = "gateway_provider")
data class GatewayProviderEntity(
    @PrimaryKey val id: String, val name: String, val baseUrl: String,
    val modelsJson: String, val mappingsJson: String, val createdAt: Long,
)

// 请求记录（对应网关 requests 表；P3）
@Entity(tableName = "gateway_request")
data class GatewayRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long, val model: String, val upstreamModel: String?,
    val providerId: String?, val accountId: String?,
    val status: Int, val error: String?, val attempts: Int,
    val promptTokens: Int, val completionTokens: Int,
    val cacheReadTokens: Int, val credit: Double?,
    val durationMs: Long, val firstResponseMs: Long?,
    val client: String?, val isTest: Boolean,
)

// 日聚合（报表用；P3，避免每次扫全表）
@Entity(tableName = "gateway_daily", primaryKeys = ["day","model","providerId"])
data class GatewayDailyEntity(
    val day: String,           // 本地时区的 yyyy-MM-dd
    val model: String, val providerId: String,
    val requests: Int, val failures: Int, val tokens: Long, val cacheRead: Long,
)
```

### 6.2 签到相关表（P5）

```kotlin
// 明细台账（对应网关 checkin_records，schema v11）
@Entity(tableName = "checkin_record")
data class CheckinRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String, val provider: String,
    val day: String,            // 北京时间 yyyy-MM-dd
    val source: String,         // schedule | makeup | startup | manual | single | activity
    val outcome: String,        // success | already | pending | captcha | failed
    val message: String?, val benefit: String?, val at: Long,
)
```

条目（`CheckinSchedule`）与每日落点（`AccountPlan`）放 DataStore 的 JSON 键里，
与网关一致（它们是**配置 + 当天状态**，不是流水）；只有台账进 Room。

### 6.3 模型条目（结构化，P2 的前置改造）

```kotlin
// 对应网关 providers/catalog/view.rs 的一行
data class ModelEntry(
    val provider: String, val id: String, val name: String = id,
    val kind: ModelKind = ModelKind.Chat,        // chat | image
    val source: ModelSource,                     // native | manual | modelsDev
    val enabled: Boolean = true,
    val credits: Double? = null,
    // 能力位（三态：null = 未声明 → 放行）
    val supportsToolCall: Boolean? = null,
    val supportsImages: Boolean? = null,
    val supportsVideo: Boolean? = null,
    val supportsReasoning: Boolean? = null,
    val supportsImageGeneration: Boolean? = null,
    val supportsImageEditing: Boolean? = null,
    val inputModalities: List<String> = emptyList(),
    val maxInputTokens: Int? = null, val maxOutputTokens: Int? = null,
    // 思考：候选等级 / 默认 / 固定 / 来源
    val reasoningEfforts: List<String> = emptyList(),
    val reasoningDefault: String? = null,
    val reasoningFixed: String? = null,
    val reasoningSource: String? = null,
)
```

**迁移**：老配置的 `modelList: List<String>` → `List<ModelEntry>`（`enabled = true`，能力位全 null），
`modelIndex` 语义保留（指向新列表的下标）。这一步不做，§5.4.1 里 15 条都挂不上。

### 6.4 DataStore 新键（网关档案）

```
gateway_enabled            bool      总开关（关掉 = 今天的行为）
gateway_routing_strategy   string    order | roundRobin | weighted
gateway_routing_sticky     string    auto | off | on
gateway_failover_backoff   bool
gateway_sink               bool
gateway_breaker_enabled    bool
gateway_breaker_threshold  int
gateway_breaker_timeout    int (s)
gateway_breaker_success    int
gateway_retry_count        int       单账号内重试
gateway_retry_switch       int       换号次数上限
gateway_retry_interval     int (s)
gateway_no_retry_codes     string    "400,402,413"
gateway_timeout_connect    int (s)   覆盖 NetworkModule 的 15s
gateway_timeout_stream     int (s)   覆盖 120s
gateway_queue_max_waits    int
gateway_queue_wait_seconds int
gateway_image_compress     bool + maxEdge/quality/thresholdKb
gateway_sanitize_enabled   bool + rules JSON
gateway_auto_continue      bool + maxRounds
```

**命名原则**：能对应上网关 `config.json` 的键，用**同样的名字**（`routingStrategy` → `gateway_routing_strategy`），
这样导入导出时是机械映射，不需要维护第二张对照表。

### 6.5 运行期状态（纯内存）

```kotlin
class RouteState(private val clock: () -> Long) {
    // 三张表 + 一张限额表；键全部是 (accountId) 或 CooldownKey(accountId, wireModel)
    fun backoffUntil(id: String): Long?
    fun markBackoff(id: String): Long          // 15m × 2^n，上限 1h
    fun sinkUntil(id: String): Long?           // TTL 30m
    fun limitUntil(key: CooldownKey): Long?    // 429：到上游给的时刻，认不出则 10m
    fun breakerOpen(id: String): Boolean
    fun onSuccess(id: String)                  // 清 backoff + sink + 熔断计数
    fun onFailure(id: String, kind: FailureKind)
}
```

⚠️ `CooldownKey(accountId, wireModel)` 必须是**独立类型**，不能是两个 String 参数 ——
网关 issue #200 就是因为在某个分支传了请求名而不是上游真名，冷却写错键位，
账号看起来「没限额」却被反复选中。类型能防住这种错。

---

## 7. 路由引擎（完整状态机）

### 7.1 候选链生成

```
对外 model（客户端请求的那个名字）
  ├─ 原生承载：某家清单里 id == model      → (provider, model, reasoning=null)
  └─ 映射承载：modelRules.mappings 里 alias == model
        → 每条 mapping：(mapping.provider ?: 默认, mapping.target, mapping.reasoning)
```

**同一对外名可以有多条映射**（跨家）。顺序 = 配置顺序。
（网关 #303 的诉求是「同家多条也成候选链」，主仓目前只取第一条 —— 移植时可以直接做成链。）

### 7.2 过滤（顺序有讲究，先便宜的判定）

| # | 过滤 | 依据 |
|---|---|---|
| 1 | 账号启用 | `enabled` |
| 2 | 凭证存在 | `credentialsCipher` 能解出 |
| 3 | 能力位 | 带图请求 → 跳过 `supportsImages == false` 的（**缺键放行**） |
| 4 | 冷却 | backoff / sink / breaker / 429 限额（键 = accountId + wireModel） |
| 5 | 并发 | `maxConcurrent` |
| 6 | 限流器 | 每账号/每家的 RPM 与 token 预算 |

### 7.3 排序

```
priority 升序 → 组内顺序 → 会话粘性命中者提前 → sink（垫底）排到队尾
```

**粘性**：`conversationKey = 显式头（如 AH-Thread-Id）优先，否则 content hash`。
命中且该账号可用 → 直接用；不可用 → 回落到正常排序，**并清掉这条粘性**（否则下次还钉死）。

### 7.4 失败分类 → 动作（这张表是网关 upstream 的核心）

| 失败 | 判定依据 | 动作 | 冷却 |
|---|---|---|---|
| 额度受限 | 429 / `insufficient quota` / 正文配额标记 | 标记该账号该模型 + 换号；**还有额度时另加垫底** | 到上游给的重置时刻，认不出 10m |
| 凭证过期 | 401 / token 失效文案 | 尝试续期一次 → 仍失败标 `needsRelogin` + 换号 | 不冷却账号 |
| 内容拦截 | 200 但正文是拒绝标记，或 finish_reason 命中 | **不罚账号**，换家 | 无 |
| 体积超限 | 413 / 折叠后的判据 | **不换号**（同家再撞），剥图重试或直接报错 | 无 |
| 上游 5xx / 网络 | 其余 | 退避 + 换号 | 15m 起翻倍 |
| 请求本身非法 | 400 且不在白名单 | 不换号、不重试，直接报给用户 | 无 |

### 7.5 与「自动续写」的关系

网关有 `autoContinue`：回答被腰斩（finish_reason=length 或上游断流）时，网关自己再问一轮把话说完。
Praxis 的对话层已有「继续」按钮，但**没有自动**。移植时放在路由层之上（`GatewayProvider` 的外层），
默认关闭、轮数上限 3 —— 自动续写要花钱，不能擅自开。

---

## 8. 配置导入 / 导出（与网关全量兼容）

### 8.1 网关实际导出的东西

| 载体 | 从哪拿 | 形状 | 一期 |
|---|---|---|---|
| **账号文件** | 面板「导出账号」（单个/整组） | 每账号一个 JSON，`format: agent2api-account` | ✅ |
| **自定义提供商** | 随账号文件带出 / `/api/custom-providers` | `customProviders: [...]` | ✅ |
| **模型规则** | `config.json` 的 `modelRules` | `{disabled,mappings,custom,capabilities,seeded}` | ✅ |
| **网关 Key** | `config.json` 的 `apiKeys` | `[{id,name,key,enabled,createdAt,allowedProviders,allowedModels}]` | 只导出回写 |
| **整份配置** | `/api/config`、云同步快照、备份包 | 扁平 JSON（60+ 键） | ✅ 挑键导入 |
| **备份包** | `/api/backup/export` | 整库（含账号表） | P4 可做 |

### 8.2 账号文件（真实形状，字段名已核对）

```json
{
  "format": "agent2api-account",
  "id": "custom-acct-28f993d4dbb3",
  "provider": "custom-3f2a91b04c7e",
  "name": "我的中转-1",
  "apiKey": "sk-xxxx",                     // 或 accessToken / refreshToken（OAuth 型）
  "baseUrl": "https://api.example.com/v1",  // 账号级覆盖；缺省跟随提供商
  "customProviders": [ { ... } ]           // 自定义家才带
}
```

凭证键名（网关 `account_identity` 与 `CREDENTIAL_KEYS` 的三项）：`accessToken` / `refreshToken` / `apiKey`。

**映射表**

| 网关字段 | Praxis | 规则 |
|---|---|---|
| `id` | `GatewayAccountEntity.id` | 原样保留（导出可回灌同一 id） |
| `provider` | `providerId` | 内置家直用；`custom-*` 先落 `gateway_provider` |
| `name` | `name` | 文件名备注优先 |
| `apiKey` | `credentialsCipher.apiKey` | 立即加密 |
| `baseUrl` | `baseUrlOverride` | 空 = 跟随提供商 |
| `accessToken` / `refreshToken` | — | **一期拒绝**，导入报告列为 skipped（理由：需 OAuth 续期，实现在插件仓库） |
| `desktop` / `pluginSecret` / `pluginPublic` | — | 丢弃 |

### 8.3 `modelRules`

```json
{
  "disabled":     [ { "id": "gpt-x", "provider": "openai", "enabled": false, "reasoning": null } ],
  "mappings":     [ { "alias": "fast", "target": "gpt-x", "provider": "openai", "reasoning": null, "enabled": true } ],
  "custom":       [ { "provider": "openai", "id": "gpt-y" } ],
  "capabilities": [ { "provider": "openai", "id": "gpt-x", "values": { "supportsImages": true } } ],
  "seeded":       [ "openai/gpt-x" ]
}
```

Praxis 侧四个 data class 与它逐字段同名（§6），**直接 `Json.decodeFromString` 即可**。
`seeded` 是网关的内部去重标记，导入时可忽略但**导出时要保留**（否则回灌会重复播种）。

### 8.4 `config.json` 全键处置表

**A. 直接移植（同名搬进 DataStore）**

| 键 | 说明 |
|---|---|
| `routingStrategy` / `routingSticky` | 选路策略 / 会话粘性 |
| `routingFailoverBackoff` / `routingSink` | 失败退避 / 额度垫底 |
| `routingBreakerEnabled` / `Threshold` / `TimeoutSeconds` / `SuccessThreshold` | 熔断四参 |
| `retryCount` / `retryCrossProviderCount` / `retryIntervalSeconds` | 重试三参（注意键名是 CrossProvider 不是 AccountSwitch） |
| `noRetryStatusCodes` | 不重试、直接换号的状态码 |
| `connectTimeoutSeconds` / `headersTimeoutSeconds` / `streamIdleTimeoutSeconds` / `bodyTimeoutSeconds` | 四个超时 |
| `queueMaxWaits` / `queueWaitSeconds` | 排队 |
| `imageCompressEnabled` / `MaxEdge` / `Quality` / `ThresholdKb` | 图片压缩 |
| `autoContinue` / `autoContinueMaxRounds` | 自动续写 |
| `sanitizeBlacklistFingerprints` + 规则表 | 脱敏改写（默认关） |
| `debugMode` | 原始报文记录（脱敏后展示） |

**B. 部分移植（需要改造才能用）**

| 键 | 障碍 | 建议 |
|---|---|---|
| `promptMode` / `promptText` / `promptFile` / `promptGateway*` | 网关是「替换/追加 system 首句」，Praxis 是三层提示词 + 模板 | 只映射到「追加一条 system」；不覆盖 Praxis 的模板机制 |
| `modelsDevEnabled` | 网关用 models.dev 补能力位/上下文 | Praxis 可用同源数据补「模型元数据页」 |
| `clientIdentification` 规则 | 手机关联来源单一 | 可做，但优先级低 |
| `logRetentionDays` / `requestLogRetentionDays` / `dailyAggregateRetentionDays` / `*Count` | 手机存储更紧 | 直接搬，但默认值调小（如 7 天） |

**C. 不导入（服务端语义，手机端不存在）**

| 键 | 原因 |
|---|---|
| `scheduledTasks`（credentialMaintenance / modelRefresh / updateCheck / logsAutoRefresh…） | 服务器定时任务；手机上对应的是 App 自己的刷新策略 |
| `githubToken` / `updateProxy` / `updateBeta` | 网关自己的软件更新 |
| `captchaEnabled` / `captchaDesktop` / `captchaWeb` | 面板登录人机验证 |
| `corsEnabled` | 浏览器跨域（App 不发 CORS 预检） |
| `logDir` / `requestStatsDir` / `debugDir` | 服务端路径 |
| `zcodeCaptchaPool*` | ZCode 专用（且该家实现在插件） |
| `providerRoute` / `developerRoleCompat` / `clineUpstreamHeaders` / `stripNewlineKeepalive` | 网关对特定客户端的兼容补丁，Praxis 不做这些上游 |

> 原则：**C 类必须在导入报告里逐条列出**「以下 N 项是服务端专属，未导入」，
> 而不是静默丢弃。用户看到「导入完成」却少了配置，是最容易引发不信任的体验。

### 8.5 导出

两种粒度 + 两种格式：

```
exports/
  gateway-config.json              # 网关原生 config 片段（routing/modelRules/customProviders/apiKeys）
  accounts/                        # 网关原生账号文件（format: agent2api-account）
    openai-工作号-20261107-001.json
  praxis-gateway-backup.json       # Praxis 自己的完整档案（含超时/队列/压缩等网关没有的项）
```

| 粒度 | 内容 | 前置 |
|---|---|---|
| 仅配置（默认） | 无凭证；`apiKey` 字段写成 `"***"` | 无 |
| 含凭证 | 明文 `apiKey` / `accessToken` | 二次确认 + 文件名强制 `-secrets` |

**双向可逆的验收**：Praxis 导出的 `gateway-config.json` + `accounts/*.json` 要能被 Agent2API 原样导入；
反之亦然。这条必须在 P0 就用真实文件跑通。

### 8.6 幂等与冲突

| 场景 | 处理 |
|---|---|
| 同 `id` 已存在 | 覆盖（updated） |
| 同 provider + baseUrl 但 id 不同 | 新建，并提示「可能是重复账号」 |
| provider 未知且非 custom-* | skipped，理由「Praxis 不认识这家」 |
| 凭证类型不支持 | skipped，理由「一期仅 API Key 型」 |
| JSON 损坏 / 缺 format | failed，带文件名 |

报告固定四段：`新增 / 更新 / 跳过 / 失败`，每段可展开看明细。

### 8.7 安全

1. 凭证**入库即加密**（复用 Praxis 现有 Keystore AES/GCM）；
2. 明文只存在于导入的那一瞬间与内存；不写日志（现有 OkHttp 拦截器只打 BASIC，**不要为调试改成 BODY**）；
3. 导出目录 `getExternalFilesDir/exports/`——卸载即清，但同机有存储权限的应用可见，文案要说清；
4. 含凭证的文件禁止走「分享」到聊天工具（只允许「保存到本机」），减少误传。

---

## 9. UI 规格（只用 Praxis 现有控件）

### 9.0 硬约束：不许自写控件

Praxis 的 `ui:widgets` 有 24 个封装件、`ui:design` 有 9 个布局件。**够用，不要新造。**

**可直接用的封装件**（真实文件，全在 `ui/widgets/`）

| 用途 | 控件 |
|---|---|
| 卡片 | `MiuixCards.kt`（及 Miuix 的 `Card`） |
| 按钮 | `MiuixButtons.kt`（`Button` / `TextButton`）、`ProgressButton.kt`（带进度，导入/测试时用） |
| 输入 | `MiuixTextField.kt` |
| 数值 | `MiuixNumPickerWidget.kt`（超时/次数/窗口这种数值项） |
| 单选/多选 | `MiuixSelectableRow.kt`、`MiuixDropDownItem.kt`、`MiuixSmallTitleDropDown.kt` |
| 列表拖动排序 | `MiuixDraggableList.kt`（账号优先级排序直接用这个） |
| 状态标记 | `MiuixBadge.kt`（正常/冷却/禁用 三态灯） |
| 折叠 | `FoldableContent.kt`（expandVertically + fadeIn） |
| 弹窗 | `MiuixDialog.kt`、`MiuixAnimatedDialog.kt` |
| 浮层 | `MiuixAnimatedPopup.kt` |
| 加载阻塞 | `MiuixBlockingLoadingDialog.kt`（导入/导出进度） |
| 入口行 | `MiuixNavigationItemWidget.kt` |
| 滚动跟随 | `AutoFollowScroll.kt`（`ui/design/component`） |
| 页面骨架 | `PraxisPage.kt`、`LowAppScaffold.kt`、`AppScaffold.kt` |

**一条替换规则**（易错，先说）：

| 别用 | 用 |
|---|---|
| miuix `Switch` | **`LiquidToggle`**（液态玻璃开关，已由 `MiuixBaseWidget` 包好 —— 直接用项目里的 `Switch` 封装即可） |

按钮**不用**液态玻璃控件，就是 miuix 的 `Button` / `TextButton`（见 §9.13 的说明）。

**分区写法**（与 `ModelScreen` 完全一致，照抄结构即可）：

```kotlin
LazyColumn {
    item { SettingsSectionHeader(icon = Icons.Outlined.X, title = "…", subtitle = "…") }
    item { Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { /* 内容 */ } }
    // 下一个分区同理
}
```

**禁止项**（会立刻在深色模式 / 字号缩放 / 动效节奏上与其它页不一致）

| 禁止 | 用什么代替 |
|---|---|
| 自写 `Canvas` 画控件 | 用上表控件；确需绘制（如图表）先看 `report-charts` 有没有现成的 |
| 自定颜色 / 圆角 / 阴影 | 用 `MiuixTheme.colorScheme` 与组件默认样式 |
| 自写动画 | 用 `ui/design/effect` 与 `FoldableContent` / `MiuixAnimatedDialog` |
| 自写弹窗/浮层 | `MiuixDialog` / `MiuixAnimatedDialog` / `MiuixAnimatedPopup` |
| 弹层用 `if (visible) { ... }` | 组件常驻挂载、用 `visible` 参数（否则退场动画来不及播） |

### 9.1 设置入口 + 模型配置改造（这两处是最小改动、最高收益）

**① 设置首页加入口**

```kotlin
// SettingsEntry.kt 加一项
data object Gateway : SettingsEntry("gateway", "网关", R.string…, PraxisRoute.SettingsGateway)

// SettingsHomeScreen.kt 的 SettingsCard 里，紧跟 SettingsEntry.Model
SettingsCard(SettingsEntry.Gateway) { navigator.push(it.route) }
```

**② 模型配置页按 kind 分叉**（`ModelScreen.kt`）

```kotlin
when (profile.kind) {
    ModelProfileKind.Single -> { 服务商 / 接口地址 / API Key / 模型 / …（现状不动） }
    ModelProfileKind.Gateway -> {
        // 三块（服务商 / 接口地址 / API Key）整块不渲染
        item { GatewayEntryCard(accountCount, providerCount, modelCount,
                                onEnter = { navigator.push(PraxisRoute.SettingsGateway) }) }
        item { ModelPickerSection(models = gatewayModels) }   // 自动填，用户只选
        // 参数 / 上下文 / 思考 / 自定义头 / 请求队列 照常渲染
    }
}
```

**③ 预置「网关」配置**：`SettingsRepository` 首次创建档案时，除默认 single 档案外，
再写一条 `gateway` 档案（名字「网关」）。**不设为默认激活** —— 免得用户在没配账号时
发现所有对话都发不出去。用户点「网关」卡片进设置配好账号后再切过去。

### 9.2 概览页

| 控件 | 用途 |
|---|---|
| `SettingsSectionHeader` + `Card` | 「账号」「今日流量」两个分区 |
| `MiuixBadge` | 账号状态（正常 / 冷却 / 禁用）计数 |
| `MiuixNavigationItemWidget` | 最近失败 3~5 行，点进对应账号 |

不做图表。要趋势去报表页。

### 9.3 账号页

| 控件 | 用途 |
|---|---|
| `MiuixDraggableList` | 账号列表 + 拖动改优先级（对应网关的 priority） |
| `MiuixSelectableRow` | 多选进入批量模式 |
| `MiuixBadge` | 状态灯 + 冷却倒计时 |
| `MiuixAnimatedPopup` | 行尾 ⋯ 菜单（测试 / 清空运行状态 / 导出 / 删除） |
| `MiuixDialog` | 批量确认、删除确认 |

**批量结果必须逐条回报**：`ProgressButton` 显示「已处理 3/8」，结束后用 `MiuixDialog` 列出
`刷新 5 · 跳过 2（不支持 OAuth）· 失败 1（xxx）`。

### 9.4 路由页

| 分区 | 控件 |
|---|---|
| 策略 | `MiuixSelectableRow`（顺序/轮询）+ 粘性三态 |
| 容错 | `Switch` ×3 + `MiuixNumPickerWidget`（阈值/超时/成功次数） |
| 候选队列 | `Card` 内逐行（位次 · 账号 · 可用/跳过原因），不做动画 |

### 9.5 模型页

映射表用 `Card` 内逐行（别名 → 上游 · 开关 · 删除），不加表格控件；
「测试」按钮用 `ProgressButton`，点之前 `MiuixDialog` 提示「会真实请求并消耗额度」。

### 9.6 报表页

复用 Praxis 现有列表与时间范围控件；**不新写图表**，先看 `report` 相关既有实现能否满足。

### 9.7 导入导出页

| 控件 | 用途 |
|---|---|
| `MiuixNavigationItemWidget` | 「选择文件」「从剪贴板」「备份包」三个入口 |
| `MiuixBlockingLoadingDialog` | 解析 / 导入进度 |
| `MiuixDialog` | 预检报告（新增/更新/跳过/失败四段）与含凭证导出的二次确认 |

### 9.8 日志（并入现有页，不做新页）

`SettingsLog` 的筛选区加一档来源：`全部 / App / 网关 / 请求记录`。
数据层不动，只是同一份 Room 数据换个 view。
### 9.9 添加账号：三级页面 + 授权弹窗

```
设置 → 网关 → 账号                       [ + 添加账号 ]
   ↓ ① 提供商列表      PraxisRoute.SettingsGatewayAdd
   ↓ ② 登录方式        PraxisRoute.SettingsGatewayAddLogin(providerId)
   ↓ ③ 授权弹窗        （不是路由，是该页上的 MiuixDialog）
   ↓ ④ H5 容器         PraxisRoute.SettingsGatewayAuth(url, providerId)
```

**① 提供商列表**

| 项 | 做法 |
|---|---|
| 数据 | 网关的提供商目录（内置家 + 两个插件包声明的家），按「可用/不可用」分组 |
| 搜索 | 顶部 `MiuixTextField` + 现成的 filter 记忆（Praxis 已有同类实现） |
| 行 | `MiuixSelectableRow`：图标 + 名称 + 能力徽标（对话/签到/余额） |
| 分组标题 | `SettingsSectionHeader` |

**② 登录方式页**

数据来自网关的 `loginMethods`（`LoginMethodMetadata`）：

```kotlin
data class LoginMethod(val methodIndex: Int, val type: LoginMethodType,  // oauth | api
                       val label: String, val instructions: String?,
                       val prompts: List<LoginPrompt>,
                       val localOnly: Boolean, val embeddedOnly: Boolean,
                       val manualCallback: Boolean, val callbackInput: CallbackInput) // url | code
```

| 类型 | UI |
|---|---|
| `oauth` | 一行「{label}」+ 右侧箭头；点了弹**授权窗** |
| `api` | 一行「{label}」，点了弹**表单窗**（按 `prompts` 渲染 `MiuixTextField`，手机号/验证码/API Key） |

**③ 授权弹窗**（`MiuixDialog`，与网关原版同构）

```
┌ 授权 ────────────────────────────┐
│ 说明文字（instructions）           │
│                                  │
│ [ 打开授权页 ]                    │  ← oauth：进 H5 容器
│ [ 复制链接 ]                      │  ← 可选
│                                  │
│ 已登录？粘贴回调 URL / 验证码       │  ← manualCallback / callbackInput=code
│ [ 取消 ]           [ 完成授权 ]    │
└──────────────────────────────────┘
```

**必须遵守的行为**（网关踩过的）：
1. `embeddedOnly = true` 时不提供「用系统浏览器」选项（那些家登录页在本机回环，系统浏览器打不开）；
2. `manualCallback = true` 时保留「粘贴回调 URL / 验证码」入口 —— 这是唯一能在容器外完成的方式；
3. 轮询 `login/wait` 的间隔取返回的 `pollIntervalMs`，别写死；
4. 取消要真的取消（调 `login/cancel`），否则上游会话会挂着。

**④ 授权结果**：成功 → 关闭弹窗 → Toast → 回账号列表并高亮新账号（用 `animateItem()` 让它出现）。

### 9.10 H5 容器：用现成 Compose 库，不自写

**候选（都已发布在 Maven Central，Android 可用）**

| 库 | 坐标 | 形态 | 评价 |
|---|---|---|---|
| compose-webview（jeziellago） | `dev.jeziellago:compose-webview` | Android 专用 | **首选**：集成最轻，直接 `ComposeWebView(url=…, onCreated=…, onPageStarted=…)` |
| compose-webview-multiplatform（KevinnZou） | `io.github.kevinnzou:compose-webview-multiplatform` | KMP（Android 实现来自 Accompanist 的 fork） | 备选：Praxis 是 Android-only，用 KMP 版多一层无关代码 |
| compose-webview（parkwoocheol） | `io.github.parkwoocheol:compose-webview` | KMP | 备选，API 风格类似 |

**选型建议**：先试 `jeziellago`（最轻）；若与 Compose 1.12 / Kotlin 2.4 不兼容，换 KevinnZou。
**都不行才**退到 `androidx.webkit:webkit` + `AndroidView { WebView }` —— 那是最后手段，且要评审。

**这一层要自己写的只有「壳」**（不是 WebView 本体），放在 `ui/widgets/PraxisWebViewSheet.kt`：

| 壳要处理 | 为什么 |
|---|---|
| 加载进度条 / 错误页 / 重试 | 登录页白屏时用户不知道该等还是该退 |
| 返回键（先 `canGoBack()`，否则关容器） | 登录流程里返回很常见 |
| 拦截 `allowedHosts` 上的回调 URL | OAuth 完成时靠它拿到 code |
| 处理 `target=_blank` | 部分厂商登录会开新窗口 |
| Cookie / DOM storage / 第三方 Cookie | 微信/QQ 登录依赖 |
| 自定义 UA（可选） | 有些站会拦 WebView UA |

> 这些是「容器行为」，不是「控件绘制」—— 用库的 API 就能做，**不需要自己画任何东西**。

### 9.11 控件不够时的决策顺序

```
1. Praxis 的 ui:widgets（24 个封装件）—— 首选
2. miuix 原组件（项目已在用 110 个，见 §9.0：preference.* / overlay.* / blur.* / window.WindowDialog …）
3. androidx compose material3（已依赖，material3 1.4.0）—— 兜底
4. 自己写 —— **需要评审**，且必须用主题色/间距常量，不许写死颜色与圆角
```

**已知够用的几组**（不用去 MD3）：
`preference.ArrowPreference`（带箭头的设置行）、`preference.CheckboxPreference`、
`preference.SliderPreference`、`preference.OverlaySpinnerPreference`（下拉选择）；
`overlay.OverlayListPopup` / `popup.OverlayDropdownPopup`（⋯ 菜单）；
`window.WindowDialog`（弹窗）；`basic.PullToRefresh`（列表刷新）；
`blur.Backdrop` / `ProgressiveBlur`（磨砂，如果需要）。

### 9.12 取色与动画

**取色**：不写任何取色代码。`ColorSource.Wallpaper` 已是莫奈（miuix `platformDynamicColors`），
依赖里有 `materialKolor 5.0.0`。新页面只要全部用 `MiuixTheme.colorScheme.*`，就自动跟随壁纸/品牌色；
**任何写死 `Color(0x…)` 的地方都算 bug。**

**动画清单**（缺一不可）：

| 场景 | 实现 |
|---|---|
| 三级页面跳转 | 现有 `NavDisplay` 的 `NavTransitions`（不要自定义转场） |
| 授权窗 / 表单窗 | `MiuixDialog`（进出场自带）或 `MiuixAnimatedDialog`（常驻挂载 + `visible`） |
| 展开详情 / 高级项 | `FoldableContent` |
| 列表增删（账号、映射） | `Modifier.animateItem()` |
| 状态灯颜色（正常→冷却） | `animateColor`（`compose.animation` 已是显式依赖） |
| 导入/测试进度 | `ProgressButton` / `MiuixBlockingLoadingDialog` |
| 图片生成结果出现 | `Crossfade` 或现有加载占位 |

### 9.13 开关用液态玻璃，按钮用 miuix

**这条只针对开关**。项目里从 `Kyant0/AndroidLiquidGlass` 移植了一套液态玻璃控件（Apache-2.0，开源许可页已登记），
其中**只有开关**被接进了通用组件：

| 控件 | 位置 | 现状 |
|---|---|---|
| **`LiquidToggle`** | `ui/design/glass/kyant/LiquidToggle.kt`（297 行） | **已生效**：`MiuixBaseWidget` 把 miuix `Switch` 换成了它；`SwitchRow`、角色卡、权限页都在用 |
| `LiquidGlassButton` | 同目录（206 行） | **不用**（零调用点，网关页也不引入） |
| `LiquidSlider` | 同目录 | 不用 |

**所以网关页的写法**

| 控件 | 用什么 |
|---|---|
| 开关（启用/退避/垫底/熔断/流式…） | 项目已有的 `Switch` 封装（内部就是 `LiquidToggle`），**不要**直接调 miuix `Switch` |
| 按钮（添加账号 / 保存 / 取消 / 删除 / 测试） | miuix `Button` / `TextButton`；需要内嵌进度时 `ProgressButton` |
| 输入 / 数值 / 选择 / 列表 | 见 §9.0 白名单 |

**为什么开关要液态玻璃**：它是「有状态、会被频繁点」的控件，液态滑块在按下时的形变反馈比 Material 滑块更明确；
而且主题色由 `MiuixTheme.colorScheme` 下发（`LiquidToggle` 与 `LiquidGlassButton` 都是这么接的），**自动跟随莫奈**。

**实现注意（来自 `LocalKyantBackdrop.kt` 的注释，踩过）**

1. 采样源由页面骨架建立并 provide 到 `LocalKyantBackdrop`；控件里用 `rememberPageBackdropOrFallback()` 取。
   **每页只建一个 layer**，不要在列表行里各建一个（会重复录制整页内容）。
2. 页面没提供时回退到「纯色 canvas 采样」—— 仍渲染玻璃形状，只是不折射内容。
3. **API < 33 必须优雅回退**（`GlassBackend.useLowPipeline`）：`LocalPageBackdrop` 为 null 时消费方走 `?: 实色`，不要 `!!`。
4. 直接用 `MiuixBaseWidget` 里的 `Switch` 封装，就自动满足上面三条 —— **不要绕回 miuix 原版**。
## 10. 模块与依赖

```
新增 core:gateway（纯 Kotlin，无 Android）
  ├─ CandidateChain.kt     对外名 → 候选链
  ├─ RoutePlanner.kt       过滤 + 排序（纯函数）
  ├─ RouteState.kt         backoff / sink / breaker / 429（可注入 Clock）
  ├─ CooldownKey.kt        (accountId, wireModel) 独立类型
  ├─ FailurePolicy.kt      失败 → 动作（§7.4 那张表）
  ├─ GatewayImporter.kt    网关 JSON → 领域模型
  ├─ GatewayExporter.kt    领域模型 → 网关 JSON
  └─ AutoContinuePolicy.kt 续写判定（纯函数）

新增 core:checkin（纯 Kotlin，P5）
  ├─ CheckinSchedule.kt / AccountPlan.kt   排期与逐账号落点（纯函数）
  ├─ CheckinPolicy.kt                      目标集合判定（可用 ∩ 可签 ∩ 范围 − 排除）
  ├─ Outcome.kt                            success / already / pending / captcha / failed
  └─ CheckinProvider.kt                    每家领取的 port（实现放 :app 或插件式任务）

新增 rust/praxis-providers（Rust cdylib，P5）
  ├─ Cargo.toml            依赖 agent2api-provider-protocol / -sdk（git tag）+ 21 家实现
  ├─ lib.rs                把 providers::registry() 与各家模块 pub 出来（见 §5.14.3）
  └─ jni.rs                8 个 JNI 函数（§5.14.4）+ JavaVM 全局引用管理

新增 core:provider（Kotlin，P5）—— 只做 JNI 封装，不含任何上游知识
  ├─ GatewayNative.kt      external fun 声明 + JSON 编解码
  ├─ ProviderChat.kt       callbackFlow 包 nativeChat（流式桥）
  └─ HttpCheckinTask.kt    通用 HTTP 签到任务（覆盖 REST 型自助服务）

新增 core:account（纯 Kotlin）
  ├─ ProviderAccount.kt    账号契约
  └─ CredentialKind.kt     ApiKey / OAuth / Cookie

改造 core:ai
  ├─ 抽 buildChatBody()    从 OpenAiCompatProvider 提出来
  ├─ 新增 Anthropic* / Responses* 编码器与组装器（P3）
  └─ AiErrorKind 补 QuotaLimited / ContentBlocked / TokenExpired

实现与接线（:app）
  ├─ app/data/gateway/GatewayProvider.kt      : AiProvider
  ├─ app/data/gateway/GatewayAccountStore.kt  Room + 加密
  ├─ app/data/gateway/GatewayConfig.kt        DataStore 读写
  ├─ app/data/gateway/ProxyResolver.kt        每账号出口
  └─ app/di/GatewayModule.kt                  Koin

UI（feature）
  ├─ feature/gateway/                        新模块：设置内的二级页组（8 个子页），**不新增 Tab**
  └─ feature/settings/ai/ModelScreen.kt       加 kind = gateway 分支
```

**新增依赖（只有一项）**

```kotlin
// gradle/libs.versions.toml
composeWebview = "<最新稳定>"
// 模块 build.gradle.kts（:app 或 :feature:gateway）
implementation("dev.jeziellago:compose-webview:$composeWebview")   // H5 容器，见 §9.10
```

**已有的、不用再加**：`androidx.compose.material3 1.4.0`（MD3 兜底）、`materialKolor 5.0.0`（莫奈）、
`miuix 0.9.4`、`okhttp 4.12.0`、`room 2.8.4`、`datastore 1.1.7`；
液态玻璃开关 `LiquidToggle` **已在仓库内**（`ui/design/glass/kyant/`），不是外部依赖。

**依赖方向**：`core:gateway` 只依赖 `core:model` 与 `core:ai`。
不碰 OkHttp / Context / Room —— 否则 §13 的单测跑不起来。

---

## 11. 分阶段（P0 ~ P6）

| 阶段 | 范围 | 产出 | 验收 |
|---|---|---|---|
| **P0** | **设置入口 + 模型配置分叉 + 导入导出骨架** | `SettingsEntry.Gateway` + `PraxisRoute.SettingsGateway` + `ModelProfile.kind` + 预置「网关」档案 + 网关入口卡片 + `GatewayImporter/Exporter`（只出报告） | 设置里能看到「网关」；模型页切到「网关」时看不到地址/密钥、能进网关页；导入真实导出后报告数字正确 |
| **P1** | 账号池 + 失败切换 + **添加账号三级流程** | `core:gateway` 状态机 + `GatewayProvider` + 账号页 + 提供商列表/登录方式/授权窗 + H5 容器 | 两个账号，改坏一个的 key 自动换号；从「添加账号」走完 OAuth 授权并成功回填 |
| **P2** | **模型条目结构化改造** + 模型管理 + 路由页 | `ModelEntry` 表与迁移 → 能力位/启停/映射/测试 UI；路由策略与候选队列 | 老配置能无损迁移；配一条 `fast → gpt-x@A`，请求 `fast` 落到正确账号；模型测试能区分「模型名错」与「账号无权」 |
| **P3** | 协议扩展 + 报表 + 日志并入 | Anthropic / Responses 编码器；Room 请求记录 + 报表页；日志页加来源筛选 | 与网关同类请求逐字段比对通过；报表日界线与本地时区一致；日志页能筛出网关来源 |
| **P4** | 系统设置 + 代理 + 云同步/备份 + 工具服务 | 超时/重试/队列/压缩/脱敏；代理池；WebDAV；备份；搜索与视觉 | 代理绑定生效（清单与转发同一出口）；云同步内容未变不重传 |
| **P5** | **Rust 内核 + JNI（21 家）** + **签到框架**（排期 + 逐账号随机落点 + 补签 + 台账 + 四分组 + 排除名单 + 结果推送）+ 通用 HTTP 签到任务 | `libpraxis_providers.so` + `core:provider` JNI 封装 + 签到页 + Room 台账 + WorkManager 调度 | 21 家里至少 3 家能对话/拉目录/续期；配一条 00:01 的排期账号错峰签到；关掉 App 后到点仍能跑 |
| **P6**（可选） | flow/trace 动画、内嵌 HTTP 网关（+ 按 AGPL §13 提供源码入口）、provider 剩余能力的 JNI 补齐 | `/v1/*` 对外服务 + 源码入口 | 内嵌服务杀 App 即停；App 内有「查看源码」链接 |

**每阶段结束都要能回滚**：`gateway_enabled = false` 时必须与现在的行为完全一致。

---

## 12. 风险与坑（按踩中概率）

| 风险 | 后果 | 对策 |
|---|---|---|
| **AGPL 合规（已选，必须执行）** | 链了 AGPL 代码却不改许可 / 不提供源码 → 违反许可 | 按 §5.14.5 八条执行：换 LICENSE、About 声明、发版附源码、NOTICE、App 内源码入口 |
| 换许可不可逆 | 已发布的 AGPL 版本收不回 | 动手前与全部贡献者确认；确认后在首个 AGPL 版本的 Release notes 里写明 |
| NDK 交叉编译失败（ring/rustls） | 编不出 `.so` | 用 `cargo-ndk` + NDK r27；`CC_aarch64_linux_android`；git 依赖设 `CARGO_NET_GIT_FETCH_WITH_CLI=true` |
| JNI 全局引用泄漏 | 长时间运行后 OOM | 回调里的 `GlobalRef` 成对 `delete_global_ref`；流结束必须释放 handle |
| `.so` 体积 | APK +15~20MB/ABI | 只发 `arm64-v8a`；用 AAB 按 ABI 拆分；`opt-level="s"` + `strip` + `lto` |
| 冷却键用请求名 | 命中不到，反复选中已限额账号（网关 #200） | `CooldownKey` 独立类型 |
| 带图请求不按能力位过滤 | 一次请求白撞 N 次（#408 实测 9 次） | 过滤前置；缺键放行 |
| 体积超限也换号 | 同家换号必再撞（#260） | 413 不换号 |
| 内容拦截当成账号失败 | 罚错号（换谁都被拦） | 单列一类，不冷却 |
| 换号 = 重试，反而更快 429 | 越换越差 | 换号次数上限默认 3 |
| 报表日界线用 UTC | 「今天」错 8 小时（#329） | 聚合键显式用本地时区 |
| 云同步不判内容 | 空闲时持续上传（#502：16 秒 3.5MB） | 内容指纹 |
| Android 后台杀进程 | 内嵌服务/长任务中断 | P6 用前台服务；客户端内路由不依赖常驻 |
| Keystore 重置 | 凭证解不开，整池崩 | 解密失败只标该账号 needsRelogin |
| 导入的 OAuth 账号静默 401 | 用户以为能用 | 导入阶段就拒绝并说明 |
| 映射大小写撞车 | 两个名字互相遮蔽 | 配置时检测并拦（#208） |
| 液态玻璃**开关**在 API<33 或低端机崩/黑块 | 页面白屏 | 用 `MiuixBaseWidget` 的 `Switch` 封装（内部走 `rememberPageBackdropOrFallback()` + `useLowPipeline`），别直接调 miuix `Switch` |
| H5 容器与新版 Compose 不兼容 | 授权页打不开 | 先试 jeziellago，再退 KevinnZou，最后才 `AndroidView`（§9.10） |
| OAuth 回调拿不到 | 登录卡在「等待授权」 | 拦截 `allowedHosts`；保留 `manualCallback` 粘贴入口 |
| 插件三元组/signature 对不上 | 包装上但启动即失败（或显示未签名） | 自建包**不签名**（允许），并给 `target_triple()` 加 android 分支 + `pack.json.entry` 补键 |
| 协议版本漂移 | 插件升级后 App 连不上 | 宿主版本与 `PROTOCOL_VERSION` 一起走 CI；不匹配时明确报「插件与 App 版本不符」 |
| 插件二进制体积 | APK +13MB/ABI | 只发 `arm64-v8a`（可选 `armeabi-v7a`）；或用 ABI split |
| **后台签到不跑** | 用户以为额度领到了（最伤信任） | WorkManager + 精确闹钟 + 打开即补跑；权限缺失时页面明说 |
| 签到重复领取 | 上游 400「今天已签」被当失败，记录里全是红 | `already` 单列一类终态，不重试、不记失败 |
| 企业号/特殊账号签不了 | 每次批量多一条假失败（网关 #447） | 账号级排除名单，且不影响单账号手动签到 |

---

## 13. 验收清单

**功能**

- [ ] 关掉网关开关：行为与现在完全一致（单配置路径）
- [ ] 一个账号 429 → 换号 + 冷却；全部冷却 → 明确报错
- [ ] 内容拦截不罚号；413 不换号；401 尝试续期一次
- [ ] 带图请求跳过明确不支持图片的账号
- [ ] 映射生效：请求对外名 → 落到配置的上游模型
- [ ] 报表/日志/代理/云同步各自跑通一次

**签到（P5）**

- [ ] 配 00:01 的排期，账号在窗口内**错峰**执行（不是同一秒全打）
- [ ] 杀掉 App 后到点仍能跑（WorkManager），或页面明确显示「后台签到未授权」
- [ ] 台账能按来源（定时/补签/手动/单签）与结果（成功/已领/待重试/需验证/失败）筛选
- [ ] `already`（今天已签）不重试、不记失败
- [ ] 排除名单生效：被排除账号不进定时与全部签到，但单账号手动仍可签
- [ ] 一轮跑完有结果推送（成功几个 / 失败几个 / 首个失败原因）

**模型层（P2）**

- [ ] 老配置的 `modelList` 迁移到 `ModelEntry` 后，模型选择与请求行为不变
- [ ] 能力位为 `false` 时带图请求跳过该模型；为 `null`（未声明）时放行
- [ ] 逐模型启停生效：关掉的模型不出现在选择器里，也不能被请求
- [ ] 映射（含跨家多条）按配置顺序尝试
- [ ] 模型测试能区分三种结论：模型名错 / 账号无权 / 网络失败

**UI 一致性（P0 起）**

- [ ] 所有**开关**走项目 `Switch` 封装（= `LiquidToggle`），没有直接调 miuix `Switch`
- [ ] 所有**按钮**是 miuix `Button` / `TextButton`（未引入液态玻璃按钮）
- [ ] 换壁纸后整套页面跟着变色（莫奈），没有写死的 `Color(0x…)`
- [ ] 三级页面跳转、授权窗、折叠、列表增删都有动画
- [ ] API 32 设备上开关退化为实色但不崩、不白屏

**provider 内核与 AGPL 合规（P5）**

- [ ] `libpraxis_providers.so` 在 arm64 真机上加载成功，`nativeInit` 返回 true
- [ ] `nativeProviders()` 返回 21 家（或 fork 里实际已迁的家数），能力位正确
- [ ] 至少 3 家：`MODELS_LIST` → `CHAT`（流式）→ `CLASSIFY` 跑通
- [ ] 凭证续期（`nativeToken`）对一家生效
- [ ] 流式回调无泄漏：连续 50 轮对话后 native 内存平稳
- [ ] **`LICENSE` 已是 AGPL-3.0**；README/关于页写明；App 内有「查看源码」入口
- [ ] 本次 Release 附带对应 tag 的源码（GitHub Release 的 Source code 即可）
- [ ] `NOTICE` / `licenses/` 保留 Agent2API 与 Agent2Providers 的版权与 AGPL 声明
**导入导出**

- [ ] 导入真实 Agent2API 导出（5 账号 + 2 自定义家 + 12 条映射），报告与人工核对一致
- [ ] 导入后立即导出，非凭证字段逐字段相等（幂等）
- [ ] 导出物能被 Agent2API 重新导入（用它的面板验证）
- [ ] 含凭证导出有二次确认 + `-secrets` 命名
- [ ] C 类（服务端专属）键在报告里逐条列出

**工程**

- [ ] `core:gateway` 单测覆盖：候选链、过滤顺序、失败分类、冷却时长、导入导出往返
- [ ] 无新增第三方依赖（复用 OkHttp / kotlinx.serialization / Room）

---

## 14. 附录

### 14.1 网关路由全表（222 条，节选按组）

已按 §5 的 20 组归类；完整原始清单可在 `http.rs` 里用 `".route("` 搜索得到。
其中 `/v1/*` 9 条、`/api/*` 204 条、`/auth/*` 3 条、`/health` 1 条、
`/plugins/*` 2 条、`/skill/*` 3 条（合计 222）。

### 14.2 Praxis 页面全表

4 Tab（对话/终端/记忆/设置）+ 19 个设置 Route + 26 个屏级文件（见 §3）。

### 14.3 关键源码位置

**Praxis（改动点）**

| 位置 | 用途 |
|---|---|
| `core/ai/AiContract.kt` | `AiProvider` 契约 —— 改造切口 |
| `core/ai/AiError.kt` | 需补三类错误 |
| `core/ai/OpenAiStreamAssembler.kt` | 新协议的组装器照它的形状写 |
| `core/model/settings/ModelProfile.kt` | 建议加 `kind` |
| `core/data/settings/SettingsRepository.kt` | 配置键与加密 |
| `app/data/ai/SettingsBackedProvider.kt` | `GatewayProvider` 接在它之后 |
| `app/data/ai/OpenAiCompatProvider.kt` | 现成的 Chat 出站，路由层复用它 |
| `app/di/AppModule.kt` / `NetworkModule.kt` | 接线与超时 |
| `feature/settings/SettingsEntry.kt` | 新增入口 |
| `app/ui/PraxisApp.kt:1044-1068` | 现成的导出落地 |
| `feature/settings/SettingsHomeScreen.kt` | 加入「网关」入口（**不动 MainShellPage / MainTab**） |
| `feature/settings/ai/ModelScreen.kt` | 按 `profile.kind` 分叉 + 网关入口卡片 |
| `core/model/settings/ModelProfile.kt` | 加 `kind` 字段并预置「网关」档案 |

**网关（对照实现）**

| 位置 | 用途 |
|---|---|
| `server/src/server/http.rs` | 222 条路由的权威清单 |
| `config/types.rs` | 60+ 配置键与默认值 |
| `core/model_rules/mod.rs` | 映射/规则/能力位结构 |
| `core/api_keys.rs` | Key 结构 |
| `core/custom_providers.rs` | 自定义提供商 |
| `core/account_transfer/export.rs` | 账号文件格式 |
| `core/upstream/provider_loop.rs` | 失败分类与换号的唯一权威 |
| `core/upstream/route_state.rs` | 三态冷却 |
| `core/routing.rs` | `CooldownKeys` 的论证 |
| `core/protocol/{anthropic,responses}*` | 协议转换 |

---

## 15. 收尾：三句话

1. **20 组里移植 15、降级 2、不移植 3** —— 不移植的只有三组，全是「手机上没有那个场景」（面板登录 / 接管本机 CLI / 网关自更新）。**签到是移植的**：排期、逐账号落点、补签、台账、四分组全在主仓，只有「每家的领取调用」在插件——那部分用「通用 HTTP 签到任务」先覆盖一大批。
2. **先做纯函数（P0/P1 的路由状态机与导入导出），再接网络与 UI** —— 网关踩过的坑几乎全在判定逻辑里，而判定逻辑正适合单测。
3. **许可证已定：Praxis 转 AGPL-3.0**，换来 21 家 provider 一次性到手（Rust `.so` + JNI）。
   代价是要履行 §5.14.5 的八条合规动作，且**换许可不可逆** —— 动手前先把贡献者签完。

4. **导入导出要双向可逆** —— 这是用户复用的关键：他已经把 Agent2API 配好了，不该在手机上重配一遍。
