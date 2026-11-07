# 把 Agent2API 网关能力移植进 Praxis

> 目标：让 Praxis 具备网关的核心能力（**多提供商 / 多账号池 / 模型映射 / 失败切换 / 网关 Key / 多协议**），
> 并且**能直接读写 Agent2API 的配置**（导入即用、导出可回灌），不是另起一套配置体系。

---

## 0. 这份文档的依据

写之前把两端都读了。为免含混，先列清边界。

**Praxis 侧（实际读过）**

| 文件 | 读到了什么 |
|---|---|
| settings.gradle.kts / gradle.properties / README.md | 23 个子项目、依赖方向 app → feature → ui → core、端口模式、构建约定 |
| core/ai 全部 16 个 kt | AiContract（ChatMessage/ChatRequest/AiStreamEvent/AiProvider）、SseParser、OpenAiStreamAssembler、ChatEndpoint、AiError、TokenEstimator、ContextAssembler、ModelCatalog |
| core/model/settings 全部 | ModelSettings、ModelProfile / ModelProfileSet、ApiProvider、FunctionSlot、ModelAdvancedSettings、ThinkingEffort |
| SettingsRepository.kt（1446 行） | 全部 DataStore 键名、多配置档案 p:<id>:<key> 前缀、加密读写 |
| OpenAiCompatProvider / SettingsBackedProvider / OkHttpModelCatalogFetcher | 请求怎么发、SSE 怎么解、每请求读设置、节流器位置 |
| AppModule / NetworkModule | Koin 绑定、OkHttp 全局约定（connect 15s / read 120s） |
| ChatRepository（结构）、ChatViewModel（调用点） | 持久化；provider.stream() 的**两个**调用点：主对话 2264、摘要 2188 |
| feature/settings/ai 全部、SettingsHost、SettingsEntry、SettingsHomeScreen | 设置分区布局与新增入口的方式 |
| PraxisApp.kt（导出落地那段） | 已有「导出 JSON 到 getExternalFilesDir/exports/」的实现，可直接复用 |

**网关侧（本地 v3.4.2 源码）**：config/types.rs（60+ 配置键）、core/api_keys.rs、core/model_rules、
core/custom_providers.rs、core/account_transfer/export.rs、core/upstream（路由与上游链路），
以及此前 14 轮逐条处理过的 issue / PR 结论。

**没读、且说明为什么无关**：core/linux、core/terminal、core/plugin、core/tool 的内部（容器与工具链，与网关无关）；
ChatViewModel 的绝大部分（24 万字符，只需知道它调 AiProvider）；网关的 providers 各家适配器（**已搬到插件仓库，本仓没有**）。

---

## 1. 三条结论先行

### 结论一：切口是现成的 —— AiProvider

Praxis 已经把「请求长什么样、回来的流长什么样」抽成与 provider 无关的契约：

```kotlin
interface AiProvider {
    val displayName: String
    fun stream(request: ChatRequest): Flow<AiStreamEvent>
}
```

全仓只有 ChatViewModel 里两处调它（主对话 2264、摘要 2188）。**这意味着网关可以在不改 UI 的前提下替换掉「谁来发这个请求」** ——
新增一个 GatewayProvider : AiProvider，内部做账号选择 / 失败切换 / 映射改写，上层一行不动。

### 结论二：不要整体搬一份网关，拆成三层

| 层 | 放哪 | 内容 | 纯 JVM |
|---|---|---|---|
| 协议层 | core:ai（已存在） | SSE 解析、Chat 请求与事件、错误分类；新增 Responses / Anthropic 编码器 | 是 |
| 路由层 | **新增 core:gateway** | 候选链、映射解析、排序、失败分类与冷却状态机、Key 白名单 | 是 |
| 账号池层 | **新增 core:account** | 账号契约（provider / 凭证形态 / 额度读数）、导入导出模型 | 是（实现在 :app） |

这样路由状态机能被单测覆盖（Praxis 现有 100 个测试源文件就是这么干的），而不是绑在 OkHttp 上只能真机试。

### 结论三：先做客户端内路由，HTTP 网关放最后

Praxis 是手机上的 agent 客户端。用户要的是「十个账号自动轮换、挂了会换下一个」，
不是「这台手机对外提供 3065 端口」。所以：

- **P1 先做客户端内路由**：不新增进程、不需要前台服务、不进 APK 体积
- **对外 HTTP 网关放 P4 且做成可选**：Android 上长期跑 HTTP 服务要前台服务 + 常驻通知 + 电池白名单，代价很大（见 §12）

---

## 2. 两端现状

### 2.1 Praxis（实测数字）

| 项 | 值 |
|---|---|
| Gradle 子项目 | 23 |
| Kotlin 主源 | **335 个 / 88,501 行** |
| Kotlin 测试 | 100 个源文件 |
| Java 主源 | 76 个（多为 :ui:provision 的 HyperCeiler 搬运） |
| minSdk / target / compile | 28 / 37 / 37，JDK 21 |

**请求链（现状）**

```
ChatViewModel:2190  provider.stream(request)
        |
SettingsBackedProvider        每请求读 DataStore（含 CHAT 功能位覆盖、节流器、自定义头）
        |
OpenAiCompatProvider          OkHttp + SSE，ChatEndpoint.resolve(baseUrl)
        |
SseParser → OpenAiStreamAssembler → List<AiStreamEvent>
```

**设置层（现状）**：DataStore Preferences 单文件，已支持**多配置档案**：

```
model_profile_ids     = id1\nid2        model_profile_active = id1
model_profile_names   = id1\u0001名字
p:<id>:model_base_url / model_id / model_api_key(加密) / model_custom_headers(加密) / …
```

**没有的（= 要移植的）**：多账号、失败切换与冷却、模型映射与路由组、
网关 Key、非 OpenAI 协议、请求日志与报表。

### 2.2 网关（Agent2API v3.4.2）

| 项 | 值 |
|---|---|
| 形态 | 单端口 3065 = 面板 + /v1/* 网关 |
| 语言 | Rust（axum + tokio），SQLite |
| 协议 | OpenAI Chat / Responses / Images、Anthropic Messages |
| 核心能力 | 多提供商多账号池、映射、路由组、失败切换（backoff / sink / breaker）、Key 白名单、请求日志与报表 |
| 配置面 | config.json（60+ 键）、apiKeys、modelRules、customProviders、账号文件 |
| 不做 | 各家的登录 / 续期 / 签到实现（在独立插件仓库，主仓只留契约与面板入口） |

---

## 3. 能力映射表

| 网关能力 | 网关实现 | Praxis 落点 | 工作量 | 备注 |
|---|---|---|---|---|
| 多配置（baseUrl+key+模型池） | config.json + 面板 | **已有** ModelProfile / model_profile_* | 0 | 复用，不重造 |
| 多账号池 | account_store | 新增 core:account + Room 表 | 中 | 一期只支持 apiKey 型（见 §7） |
| 模型映射 | model_rules::Mapping | core:gateway 纯函数 | 小 | 语义 1:1 移植 |
| 路由组 | routing_groups | core:gateway | 中 | 可后置到 P2 |
| 失败切换 | upstream/provider_loop + route_state | core:gateway 状态机 | 大 | 建议连测试用例一起搬 |
| 冷却三态 backoff / sink / breaker | route_state.rs | core:gateway（纯内存） | 中 | 进程重启即清，与网关一致 |
| 网关 Key 白名单 | api_keys.rs | 只有 P4 对外才需要 | 小 | 客户端内路由不需要 Key |
| Anthropic Messages | core/protocol/anthropic* | core:ai 新增编码器 | 大 | 只接自定义 provider 时可后置 |
| Responses 协议 | core/protocol/responses* | core:ai | 中 | Codex 类客户端才用 |
| 图像压缩 | image_compress | 可复用 Praxis 现有附件处理 | 小 | 非核心 |
| 请求日志 / 报表 | request_stats + SQLite | Room 新表 + 复用现有列表页 | 中 | P4 |
| 配置导入导出 | account_transfer / config.json | **本文 §8** | 中 | 本次重点 |

---

## 4. 数据模型（新增）

### 4.1 配置层：DataStore 新命名空间 gateway

```kotlin
data class GatewaySettings(
    val enabled: Boolean = false,            // 总开关：关掉就退回现在的单配置行为
    val strategy: RouteStrategy = RouteStrategy.Order,   // order | roundRobin | weighted
    val sessionSticky: Sticky = Sticky.Auto, // auto | off | on
    val failoverBackoff: Boolean = true,
    val sinkOnQuotaLimited: Boolean = true,
    val breakerEnabled: Boolean = false,
    val breakerThreshold: Int = 5,
    val breakerTimeoutSeconds: Int = 60,
    val breakerSuccessThreshold: Int = 2,
)
```

键名**建议与网关同名**，导入导出时零转换：
routingStrategy / routingSticky / routingFailoverBackoff / routingSink /
routingBreakerEnabled / routingBreakerThreshold / routingBreakerTimeoutSeconds / routingBreakerSuccessThreshold。

### 4.2 账号池：Room 新表（praxis.db 版本 +1）

```kotlin
@Entity(tableName = gateway_account)
data class GatewayAccountEntity(
    @PrimaryKey val id: String,          // 与网关账号 id 同构，导入可直接沿用
    val providerId: String,              // 内置家 id 或 custom-xxxxxxxxxxxx
    val name: String,                    // 备注名（导入时 nameCustom 优先）
    val baseUrlOverride: String?,        // 账号级地址覆盖（= 网关的 baseUrlOverride）
    val enabled: Boolean = true,
    val priority: Int = 0,               // 全局队列顺序，越小越前
    val maxConcurrent: Int = 0,          // 0 = 不限
    val credentialsCipher: String,       // 加密后的凭证 JSON（见 §7）
    val expiringAt: Long? = null,        // 额度 / 凭证到期时刻（面板倒计时用）
    val createdAt: Long,
)
```

**运行期状态不落库**（与网关一致：纯进程内存，重启即清）：

```kotlin
class RouteState {
    fun backoffUntil(accountId: String): Long?
    fun markBackoff(accountId: String, now: Long): Long        // 15m 起翻倍，上限 1h
    fun sinkUntil(accountId: String): Long?                    // 垫底 TTL 30m
    fun markLimited(cooldownKey: CooldownKey, until: Long)     // 429 冷却
    fun breakerOpen(accountId: String, now: Long): Boolean
}
```

> ⚠️ 一个真踩过的坑（网关 issue #200 / #408）：**冷却键必须是上游真名，不是请求名**。
> 别名请求撞 429 后若把冷却写在别名键上，判定侧按真名查不到，账号看起来「没限额」却被反复选中。
> 移植时把 CooldownKey = (accountId, wireModel) 这个二维键**写进类型**，不留给调用点决定。

### 4.3 映射与模型登记（沿用 modelRules 形状）

```kotlin
data class ModelMapping(val alias: String, val target: String, val provider: String?,
                        val reasoning: String?, val enabled: Boolean)
data class RuleEntry(val id: String, val provider: String?, val enabled: Boolean, val reasoning: String?)
data class CustomModel(val provider: String, val id: String, val name: String? = null)
data class CapabilityOverride(val provider: String, val id: String, val values: Map<String, Boolean>)
```

字段名与网关 core/model_rules 完全一致（alias / target / provider / reasoning / enabled），
所以 modelRules 的 JSON 可以**逐字段直读**，不需要转换表。

---

## 5. 协议层：在 core:ai 里加编码器，不动契约

Praxis 的 AiStreamEvent 已经覆盖了网关需要的全部形态：Delta / ReasoningDelta /
ToolCallDelta / ToolCalls / Done。**新增协议只加「编码器 + 组装器」，不改事件模型。**

| 方向 | 现状 | 要新增 |
|---|---|---|
| 出站 OpenAI Chat | buildRequestBody（OpenAiCompatProvider 内） | 抽到 core:ai，供路由层复用 |
| 入站 OpenAI Chat | SseParser + OpenAiStreamAssembler | 已有 |
| 出站 Anthropic Messages | — | AnthropicRequestBuilder |
| 入站 Anthropic SSE | — | AnthropicStreamAssembler（message_start / content_block_delta / message_delta / message_stop） |
| 出站 Responses | — | ResponsesRequestBuilder |
| 入站 Responses SSE | — | ResponsesStreamAssembler |

**三条移植要点**（都是网关踩过的坑）：

1. **Anthropic 流式的 usage 字段**：message_start 要带 input_tokens 与 cache_read_input_tokens，
   message_delta 要带 output_tokens。少一个，客户端那边缓存率恒为 0（网关 issue #424）。
2. **工具调用必须按 index 累积**：name / id 只在第一片出现，之后只有 arguments 增量，
   直到 finish_reason 才能发 ToolCalls（Praxis 的 OpenAiStreamAssembler 已经这么做了，Anthropic 同样）。
3. **错误分类要能被路由层消费**：Praxis 已有 AiErrorKind（NETWORK / RATE_LIMIT / SERVER / AUTH / INVALID_REQUEST），
   需要补三类与网关对齐：QuotaLimited（额度尽，带 reset_at）、ContentBlocked（内容拦截）、TokenExpired（凭证过期）。
   **没有这三类，路由层就无法区分「该换号」与「换号也没用」**——这是失败切换的核心判断。

---

## 6. 路由引擎（核心，建议整块移植网关的状态机）

### 6.1 一次请求的完整流程

```
ChatRequest(model = 对外名)
   |
   ├─ 1. 解析候选链：对外名 → [(provider, wireModel, reasoning)]
   |      · 原生承载：该家清单里有这个 id
   |      · 映射承载：modelRules.mappings 里 alias == 对外名（可跨家多条）
   |
   ├─ 2. 展开为账号块：每个 (provider, wireModel) → 该家的启用账号列表
   |
   ├─ 3. 过滤：
   |      · 账号 enabled / 凭证存在
   |      · 能力位（带图请求 → 跳过明确不支持图片的条目，issue #408 的教训）
   |      · 冷却：backoff / sink / breaker / 429 限额（键 = accountId + wireModel）
   |
   ├─ 4. 排序：priority → 组内顺序 → 会话粘性（同会话尽量同账号）→ sink 垫底
   |
   └─ 5. 逐个尝试，按失败类型决定动作（见 6.2）
```

### 6.2 失败类型 → 动作（这张表就是网关 upstream 的核心，别自己发明）

| 失败类型 | 判定 | 动作 | 冷却时长 |
|---|---|---|---|
| 额度/限流（429 且上游给了重置时刻） | 显式 429 或正文配额标记 | 标记该账号该模型冷却 + 换号；**还有额度时另加「垫底」** | 到上游给的时刻，认不出则 10 分钟 |
| 凭证过期（401） | 显式 401 / token 失效文案 | 尝试续期一次 → 仍失败则标记 needsRelogin 并换号 | 不冷却账号，只标记 |
| 内容拦截 | 上游 200 但正文是拒绝标记，或 finish_reason 命中 | **不罚账号**（换谁都会被拦）+ 换家 | 无 |
| 体积超限（413） | 状态码 413 或折叠后的判据 | 不换号白撞（同家换号没用），直接报错或剥图重试 | 无 |
| 上游 5xx / 网络 | 其他失败 | 退避（15m 起翻倍，上限 1h）+ 换号；连续失败到阈值触发熔断 | 退避 / 熔断 |
| 未知失败 | 其余 | 换号，不冷却 | 无 |

> 关键判断：**「换个号能不能解决」决定要不要冷却**。额度、凭证、5xx 能；
> 内容拦截、请求体本身有问题（400/413）不能 —— 这类还换号只是把同一份错误重放 N 次。

### 6.3 冷却三态（与网关一致的参数）

| 状态 | 触发 | 默认时长 | 解除 |
|---|---|---|---|
| backoff | 普通失败（5xx/网络） | 15m，连续失败翻倍，上限 1h | 成功一次即清；或用户手动清 |
| sink（垫底） | 还有额度却被 429 | TTL 30m，期间排到队尾 | TTL 到期，或成功一次 |
| breaker（熔断） | 连续失败达阈值 | 60s 半开，成功 N 次关闭 | 半开探测成功 |

三者都是**进程内存**。Praxis 是 App，进程被杀就没了——这与网关重启即清一致，不需要持久化。

### 6.4 必须**不**做的事（网关里的红线，别在移植时丢掉）

- **不要按模型名跨家共享冷却**：某家 flash 被限流不代表别家也限流（issue #200 的原始症状）。
- **不要在带图请求上「先投再看」**：能力位明确说不支持图片时直接跳过，别指望上游拒绝后换号（#408：白撞 9 跳）。
- **不要为「体积超限」换号**：同家所有账号共用同一份清单与限制，换号必然再撞（#260）。
- **不要把「已签到/额度不足」这类业务状态混进 token 记账**。

---

## 7. 账号池与凭证

### 7.1 凭证形态（Praxis 能覆盖到哪一步）

| 形态 | 例子 | Praxis 一期 | 说明 |
|---|---|---|---|
| API Key | OpenAI / DeepSeek / 中转站 / 自定义家 | ✅ 直接支持 | 与现有 model_api_key 同形态 |
| 账号级地址覆盖 | 自定义提供商 | ✅ 支持 | 对应网关 baseUrlOverride |
| OAuth refresh_token | 网关的插件家（Kiro / Tabby 等） | ❌ 一期不做 | **这些家的续期实现不在主仓，在插件仓库**；Praxis 若要做，得逐家重写登录链 |
| 网页 Cookie / 设备码 | 同上 | ❌ 一期不做 | 同上 |

> 这条边界必须写进产品文案：**Praxis 的网关模式 = 「多把 API Key 的池子」**，
> 不是「把 Agent2API 的所有登录态搬过来」。导入账号文件时，遇到 OAuth 型要给出明确提示而不是静默失败。

### 7.2 加密存储

复用 Praxis 现有方案（与 apiKey / customHeaders 同一套，见 SettingsRepository 的读写）：

- 加密后 base64 存 Room 的 credentialsCipher 字段；
- 密钥在 Android Keystore，导出时必须显式勾选「包含明文凭证」；
- 解密失败（换机 / Keystore 重置）→ 标记该账号 needsRelogin，**不要让整个池子崩掉**。

### 7.3 与现有 ModelProfile 的关系

现在 Praxis 的一「套配置」= baseUrl + key + 模型池。**网关模式下这套配置变成「一个账号」**：

```
ModelProfile（配置档案，用户可见：工作 / 个人）
   └── GatewaySettings + 账号池（GatewayAccountEntity[]）+ 映射
```

**建议**：给 ModelProfile 加一个 kind 字段（`single` / `gateway`）。
single = 今天就有的行为（一条 baseUrl + 一把 key）；gateway = 新行为。
这样老用户升级后行为完全不变，也不会出现「两个开关互相打架」。

---

## 8. 配置导入 / 导出（与网关兼容）

这是本移植的**硬要求**：用户已经把 Agent2API 配好了，不应该在 Praxis 里重配一遍。

### 8.1 网关实际导出的三样东西

| 载体 | 触发方式 | 形状 | Praxis 一期 |
|---|---|---|---|
| **账号文件** | 面板「导出账号」（单个或整组） | 每账号一个 JSON，``format: agent2api-account`` | ✅ 主通道 |
| **自定义提供商** | 随账号文件带出，或面板自定义提供商列表 | ``customProviders: [...]`` | ✅ 随账号导入 |
| **整份配置** | ``GET /api/config`` / 云同步快照 / 备份 | 扁平 config.json（60+ 键） | ✅ 只取网关相关键 |

网关还把网关 Key（``apiKeys``）与模型规则（``modelRules``）放在同一份配置里，
这两块客户端内路由用不上（Key 是给外部调用方的），但**导出时要能写回去**。

### 8.2 账号文件（真实形状）

```json
{
  "format": "agent2api-account",
  "id": "custom-acct-28f993d4dbb3",
  "provider": "custom-3f2a91b04c7e",
  "name": "我的中转-1",
  "apiKey": "sk-xxxx",                    // 或 accessToken / refreshToken（OAuth 型）
  "baseUrl": "https://api.example.com/v1", // 账号级覆盖；缺省跟随提供商
  "customProviders": [ { "id": "custom-3f2a91b04c7e", "name": "…", "baseUrl": "…", "models": [], "mappings": [] } ]
}
```

**映射到 Praxis**

| 网关字段 | Praxis 字段 | 规则 |
|---|---|---|
| ``id`` | ``GatewayAccountEntity.id`` | **原样保留**，这样导出能写回同一个 id |
| ``provider`` | ``providerId`` | 内置家直接用（openai / deepseek / …）；``custom-*`` 先落 customProvider |
| ``name`` | ``name`` | 优先用文件名里的备注名，其次 ``name`` |
| ``apiKey`` | ``credentialsCipher`` 内的 ``apiKey`` | 加密后存；**明文不落库** |
| ``accessToken`` / ``refreshToken`` | — | **一期不支持**（见 §7.1），导入报告里逐条列出而不是静默丢 |
| ``customProviders[0].baseUrl`` | ``baseUrlOverride`` | 让账号能跟着自己的提供商走 |
| ``desktop`` / ``pluginSecret`` / ``pluginPublic`` | — | 丢弃（桌面端登录态，手机上没有意义） |

### 8.3 自定义提供商

```json
{ "id": "custom-3f2a91b04c7e", "name": "我的网关", "baseUrl": "https://api.example.com/v1",
  "models":    [ { "id": "gpt-x", "enabled": true, "reasoning": "" } ],
  "mappings":  [ { "alias": "fast", "target": "gpt-x", "enabled": true } ] }
```

Praxis 侧落成：一个「网关内的 provider」+ 它的模型清单 + 映射。
字段名与网关一字不差，直接反序列化即可（``ignoreUnknownKeys = true`` 已开）。

### 8.4 整份配置（config.json）里真正需要搬的键

| 网关键 | 值域 | Praxis 落点 | 备注 |
|---|---|---|---|
| ``routingStrategy`` | order / roundRobin / weighted | ``GatewaySettings.strategy`` | 同名直搬 |
| ``routingSticky`` | auto / off / on | ``sessionSticky`` | 同名直搬 |
| ``routingFailoverBackoff`` | bool | ``failoverBackoff`` | |
| ``routingSink`` | bool | ``sinkOnQuotaLimited`` | |
| ``routingBreakerEnabled`` / ``Threshold`` / ``TimeoutSeconds`` / ``SuccessThreshold`` | bool / int | 同名 | |
| ``retryCount`` | int | 单账号内重试次数 | |
| ``retryCrossProviderCount`` | int | 换号次数上限 | 网关键名如此（不是 retryAccountSwitchCount） |
| ``retryIntervalSeconds`` | int | 重试间隔 | |
| ``noRetryStatusCodes`` | int[] | 直接换号、不原地重试的状态码 | 如 ``[400, 402, 413]`` |
| ``connectTimeoutSeconds`` / ``headersTimeoutSeconds`` / ``streamIdleTimeoutSeconds`` / ``bodyTimeoutSeconds`` | int | 覆盖 OkHttp 超时 | 现在 NetworkModule 写死 15/120，需改成可配 |
| ``queueMaxWaits`` / ``queueWaitSeconds`` | int | 排队等待 | 复用现有 RequestThrottle 扩展 |
| ``imageCompressEnabled`` / ``MaxEdge`` / ``Quality`` / ``ThresholdKb`` | bool / int | 附件压缩 | 可复用 Praxis 现有图片处理 |
| ``modelRules`` | 对象 | 映射 / 禁用 / 自定义模型 / 能力覆盖 | 见 §4.3，字段同名 |
| ``apiKeys`` | 数组 | **只在导出时回写**（客户端内不需要） | |
| ``scheduledTasks`` | 对象 | **不导入** | 那是服务器定时任务（续期/签到），手机上没有对应语义 |
| ``promptMode`` / ``promptGateway*`` | 字符串 | 可选映射到 Praxis 的 system prompt 模式 | 语义不完全对等，建议提示用户确认 |
| ``githubToken`` / ``updateProxy`` / ``updateBeta`` | — | **不导入** | 那是网关自己的软件更新配置 |
| ``captcha*`` / ``corsEnabled`` / ``debugMode`` | — | **不导入** | 面板/服务端专属 |

> 原则：**能同名直搬的同名直搬，不能的直接列进「不导入」清单并在导入报告里告知**。
> 静默丢弃一个用户以为生效了的配置，比报错更糟。

### 8.5 导出（要能写回网关）

两种粒度，用户显式选择：

```
① 仅配置（默认）：不含任何凭证。可以直接发给别人 / 提交到仓库。
     { format: praxis-gateway-export, version: 1, exportedAt, 
       routing: {...}, modelRules: {...}, customProviders: [...], 
       accounts: [ { id, provider, name, baseUrlOverride } ]   // 只有元信息
     }

② 含凭证（二次确认 + 红色提示）：额外带 apiKey / accessToken。
     文件名强制加 -secrets 后缀，落到 exports/（卸载即清）。
```

导出还要**额外生成一份网关原生格式**（``agent2api-account`` 逐账号文件 + config 片段），
这样从 Praxis 回到 Agent2API 也是「导入即用」：

```
exports/
  gateway-config.json                 // 网关可读的 config 片段（routing / modelRules / customProviders / apiKeys）
  accounts/
    custom-3f2a91b04c7e-我的中转-1-20261107-001.json   // format: agent2api-account
```

### 8.6 幂等、冲突与报告

| 场景 | 处理 |
|---|---|
| 同 id 已存在 | 覆盖（update），记入 ``updated`` |
| 同 provider+baseUrl 但 id 不同 | 视为新账号（网关的 id 由导入侧生成，重复导入会得到重复账号 —— 提示用户） |
| provider 未知且非 custom-* | ``skipped``，理由「Praxis 不认识这家提供商」 |
| 凭证类型不支持（OAuth） | ``skipped``，理由「一期只支持 API Key 型」 |
| JSON 解析失败 / 缺 format | ``failed``，带原文件名 |

导入结束**必须弹一份报告**（复用 Praxis 现有 toast + 列表模式）：
``导入 12 个：新增 9、更新 2、跳过 1（不支持 OAuth）、失败 0``。

### 8.7 安全

1. 含有凭证的文件**默认不导出**；导出时必须二次确认，并在文件名与内容里都标记 ``secrets``。
2. 导出落在 ``getExternalFilesDir/exports/``（Praxis 已有这套实现，见 PraxisApp.kt:1044-1068）；
   该目录卸载即清、无需存储权限，但**也不是绝对私密**（同机其他应用在有权限时可见）——文案要说清。
3. 导入的明文凭证**立刻加密**，不在内存里长时间持有，不进日志（现有 ``HttpLoggingInterceptor`` 只打 BASIC，不要为调试改成 BODY）。

---

## 9. UI 落点

设置首页（`SettingsHomeScreen` 的卡片清单）新增一张卡「网关」，点进去是一个二级页组：

| 页面 | 内容 | 复用 |
|---|---|---|
| 网关总览 | 开关、账号数、今日成功率、最近失败原因 | 现有卡片 + `SettingsSectionHeader` |
| 账号池 | 列表（名称 / 提供商 / 状态 / 到期倒计时）、拖动排序、启用禁用 | 抄账号页的表格与 ⋯ 菜单形态 |
| 模型映射 | 对外别名 → 各家上游模型，逐条开关 | 抄网关面板的映射表 |
| 路由策略 | 策略 / 粘性 / 退避 / 垫底 / 熔断 | 抄 `ModelScreen` 的 Section 结构 |
| 导入导出 | 选文件 / 选粒度 / 执行 / 看报告 | 复用 `exports/` 落地与 toast |
| 请求日志（P4） | 时间 / 模型 / 账号 / 状态 / 耗时 | 复用现有列表骨架 |

**聊天页**：顶部模型选择器（`ModelPickerDialog`）在网关模式下列出的是**对外模型名**
（映射后的名字），不是某家的 id —— 用户不需要知道背后用的是哪个账号。

---

## 10. 模块与依赖

```
core:gateway            新增，纯 Kotlin
  ├─ CandidateChain.kt      对外名 → 候选 [(provider, wireModel, reasoning)]
  ├─ RoutePlanner.kt        过滤 + 排序（纯函数，输入 = 账号快照 + 请求，输出 = 有序候选）
  ├─ RouteState.kt          backoff / sink / breaker / 429 冷却（纯内存 + 可注入 Clock）
  ├─ FailurePolicy.kt       失败分类 → 动作（§6.2 那张表）
  ├─ GatewayImporter.kt     读网关 JSON → 领域模型（纯函数，可单测）
  └─ GatewayExporter.kt     领域模型 → 网关 JSON

core:account            新增，纯 Kotlin
  ├─ ProviderAccount.kt     账号契约（不含 Android / 不含加密）
  └─ CredentialKind.kt      ApiKey / OAuth / Cookie（一期只实现 ApiKey）
```

实现与接线（`:app`）：

```
app/data/gateway/GatewayProvider.kt : AiProvider   ← 路由 + 换号 + 事件透传
app/data/gateway/GatewayAccountStore.kt            ← Room DAO + 加密凭证
app/data/gateway/SettingsGatewayConfig.kt          ← DataStore 读写
app/di/GatewayModule.kt                            ← Koin 绑定（在 AppModule 里 include）
```

**依赖方向必须守住**：`core:gateway` 只依赖 `core:model` 与 `core:ai`；
不碰 OkHttp、不碰 Context、不碰 Room —— 否则 §13 那些单测跑不起来。

---

## 11. 分阶段计划

每一阶段都要求：**能跑通、能回滚、有单测**。宁可慢，不要一次性铺开。

### P0 · 导入导出骨架（3~5 天）

- 新增 `core:gateway` 的 `GatewayImporter` / `GatewayExporter`（纯函数 + 单测）
- 设置页新增「网关 → 导入导出」，能选文件、解析、**只展示报告**，暂不生效
- 验收：喂一份真实 Agent2API 导出，报告里的新增 / 跳过 / 失败逐条正确

### P1 · 多账号 + 失败切换（1~2 周）

- Room 表 + 加密凭证 + 账号池 UI
- `GatewayProvider` 实现 `AiProvider`，接在 `SettingsBackedProvider` 之后（gateway 开关打开才生效）
- 冷却三态 + 失败分类，跑通「429 换号 / 5xx 退避 / 内容拦截不罚号」
- 验收：两个账号，把一个的 key 改错，请求自动换到另一个；48 小时内账号页显示冷却状态

### P2 · 映射与路由组（1 周）

- `modelRules` 全量支持（mappings / disabled / custom / capabilities）
- 聊天页模型选择器改为展示对外名
- 验收：配一条 `fast → gpt-x@providerA` 的映射，客户端请求 `fast` 能落到正确账号

### P3 · 协议扩展（按需）

- Anthropic Messages / Responses 的编码器与组装器（只在要接对应上游时才做）
- 验收：与网关的同类请求逐字段比对（可用网关的 `/api/models/test` 做对照）

### P4 · 可选：内嵌 HTTP 网关 + 请求日志 / 报表

- 前台服务 + 常驻通知 + 端口配置；`/v1/*` 复用同一个 `GatewayProvider`
- Room 请求日志表 + 报表页
- 验收：外部 curl 能跑通；杀掉 App 后服务随之停止（不要做成后台常驻）

---

## 12. 风险与坑（按踩中概率排序）

| 风险 | 说明 | 对策 |
|---|---|---|
| **一次请求试 N 个账号 = 上游 429 更快** | 换号是「重试」，重试本身会加剧限流 | 换号次数上限默认 3，且冷却键按 (账号, 上游真名) |
| **冷却键写成请求名** | 别名请求撞 429 后冷却写在别名上，判定按真名查不到 → 反复选中已限额账号（网关 issue #200） | 把二维键写进类型（§4.2） |
| **带图请求投给不收图的账号** | 白撞 N 次才失败（网关 issue #408：一次请求打 9 次上游） | 候选过滤读能力位；缺键放行、明确不支持才跳过 |
| **Android 后台杀进程** | 内嵌服务/长任务会被 LMK 杀掉 | P4 用前台服务；客户端内路由不依赖常驻 |
| **Keystore 重置导致凭证解不开** | 换机 / 刷机 / 系统修复后密文全废 | 解密失败标 `needsRelogin`，不让整池崩；导出功能是唯一迁移手段 |
| **OkHttp 超时与流式** | read 120s 对长思考模型可能不够（现有值） | 拆成 headers / streamIdle / body 三个超时（网关已有这套键） |
| **映射大小写** | 网关全链路大小写不敏感匹配，别名与 id 只差大小写会撞 | 导入时检测并提示，别让用户配出「两个名字都能命中」 |
| **导入的 OAuth 账号静默失效** | 一期不支持续期 | 导入阶段就拒绝并说明，不要导进来再报 401 |

---

## 13. 验收清单（每个阶段可勾）

**导入导出**

- [ ] 导入一份真实 Agent2API 导出（含 3 个自定义提供商 + 5 个账号），报告数字与人工核对一致
- [ ] 导入后立刻导出，两份 JSON 的**非凭证字段**逐字段相等（幂等）
- [ ] 导出物能被 Agent2API 重新导入（用它的面板验证一次）
- [ ] 含凭证导出有二次确认，文件名带 `-secrets`

**路由**

- [ ] 一个账号 429 → 自动换下一个，且第一个账号进入冷却，冷却期间不再被选中
- [ ] 所有账号都冷却 → 返回明确错误（不是空响应）
- [ ] 内容拦截不冷却账号（换家重试后行为正确）
- [ ] 带图请求跳过明确不支持图片的账号

**设置一致性**

- [ ] 关掉网关开关后，行为与今天完全一致（单配置路径不受影响）
- [ ] 网关模式下的模型列表能在聊天页正确展示与切换

---

## 14. 附录：关键源码位置

**Praxis（现有，改动点）**

| 位置 | 用途 |
|---|---|
| `core/ai/AiContract.kt` | `AiProvider` / `ChatRequest` / `AiStreamEvent` —— 改造的切口 |
| `core/ai/AiError.kt` | 错误分类，需补 QuotaLimited / ContentBlocked / TokenExpired |
| `core/ai/OpenAiStreamAssembler.kt` | SSE → 事件的现成实现，新协议照它的形状写 |
| `core/model/settings/ModelProfile.kt` | 配置档案，建议加 `kind: single / gateway` |
| `core/data/settings/SettingsRepository.kt` | 全部配置键与加密读写 |
| `app/data/ai/SettingsBackedProvider.kt` | 每请求读设置 + 节流器，`GatewayProvider` 应放在它之后 |
| `app/data/ai/OpenAiCompatProvider.kt` | 现成的 Chat 出站实现，路由层复用它发请求 |
| `app/di/AppModule.kt` / `NetworkModule.kt` | 接线与超时 |
| `feature/settings/SettingsEntry.kt` / `SettingsHost.kt` | 新增「网关」入口 |
| `feature/settings/ai/ModelScreen.kt` | 分区写法（10 个 Section）可直接照搬 |
| `app/ui/PraxisApp.kt:1044-1068` | 现成的「导出到 exports/」落地 |

**网关（对照，本地 v3.4.2 源码）**

| 位置 | 用途 |
|---|---|
| `src-tauri/server/src/server/config/types.rs` | 60+ 配置键与默认值 |
| `core/model_rules/mod.rs` | `ModelRules` / `Mapping` / `RuleEntry` / `CustomModel` / `CapabilityOverride` |
| `core/api_keys.rs` | `apiKeys` 结构（allowedProviders / allowedModels） |
| `core/custom_providers.rs` | 自定义提供商记录形状与校验 |
| `core/account_transfer/export.rs` | 账号文件格式（`agent2api-account`）与字段裁剪规则 |
| `core/upstream/provider_loop.rs` | 失败分类 → 换号/冷却的**唯一**权威实现 |
| `core/upstream/route_state.rs` | backoff / sink / breaker 三张表与 TTL |
| `core/routing.rs` | `CooldownKeys`（冷却键必须是上游真名的那套论证） |

---

## 15. 一句话收尾

这次移植的**技术难点不在写代码，在「别把网关踩过的坑再踩一遍」**：
冷却键的维度、能力位过滤、体积超限不换号、内容拦截不罚号 —— 这四条每一条都对应过真实事故。
所以建议：**先搬状态机与判定（纯函数 + 单测），再接网络与 UI**。
