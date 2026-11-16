# round-03 提供商全量目录 + 登录方式按事实走

日期：2026-11-16

## 一、你指出的两个问题，都对

1. **「提供商没这么少」** —— 对。Praxis 原来只有 24 家 API 家（抄的是上游 preset 精选子集），
   而网关侧还有 **33 家 Agent 插件家 + 4 家网页插件家**，Praxis 里一家都没有。
2. **「不需要登录的却给了网页登录」** —— 也对，而且是个真 bug：
   `GatewayLoginMethods.forProvider(providerLabel, baseUrl)` 的规则是「**只要有 http 地址**就加一条
   『打开登录页（网页授权）』」。于是 `api.openai.com`、`api.deepseek.com`、`api.z.ai/api/anthropic`
   这些只有 API Key 的家也挂了一个网页授权入口 —— 点开是官网首页，页面里根本没有授权按钮。

## 二、上游真相（数据出处都核过）

| 来源 | 内容 |
|---|---|
| `Agent2API/desktop-tauri/ui/preset-providers.js` | 24 条 API 家（自述抄自 9Router 的 `open-sse/providers/registry`，后者有 149 个 js 条目）|
| `~/.agent2api/plugins/agent2api-providers/3.4.2/pack.json` | 34 个 Agent 家（去掉示例 `demo` 后 33）|
| `~/.agent2api/plugins/agent2api-web-providers/3.4.3/pack.json` | 4 个网页家 |
| `ProvidersPlugin/Agent2Providers` README（GitHub） | 各家能力矩阵 + 部分登录方式说明 |
| 各插件包 `ui/provider-docs.json` | 各家能力位与中文说明（登录方式的直接证据）|

**实测两个网关实例**（用户已装好插件与账号）：
- 本地 `127.0.0.1:3065`：11 个模型，1 家（raccoon）
- 远端 `110.42.47.142:3065`：**173 个模型 / 14 家**（accio 42、trae 35、custom-… 35、workbuddy-intl 15、
  workbuddy 9、catpaw 8、raccoon 8、qoder 5、zcode 5、autoclaw 4、loomy 2、qoder-intl 2、qwenwork 2、routing-group 1）
- `/v1/models` 的每个模型都带 `supports_images / supports_reasoning / supports_tool_call /`
  `input_modalities / max_input_tokens / max_output_tokens / kind` —— 这是 Praxis §5.4.1 缺的能力位数据源。

## 三、代码改动

### 新增 `core:gateway/ProviderCatalog.kt`（单一真相，61 家）

- `ProviderKind { Api, AgentPlugin, WebPlugin }` —— 决定「Praxis 能不能自己发请求」。
- `ProviderLoginKind { ApiKey, WebLogin, DeviceCode, TokenImport, CookieImport, Unknown }`。
- 61 条 `ProviderEntry(id, label, order, kind, login, note)`：24 + 33 + 4。
- `direct()` / `viaGateway()` / `of(id)` / `loginOf(id)` / `labelOf(id)` / `loginLabel(id)` / `counts()`。
- 只填**有出处**的登录方式：commandcode=ApiKey、qwenwork(-intl)=DeviceCode、doubaowork=CookieImport、
  raccoon=TokenImport、loomy/kiro/astudio=WebLogin、deepseek-web=CookieImport；其余 `Unknown`（不猜）。

### `GatewayLoginMethods.forProvider(providerLabel, baseUrl, loginKind)`

- 网页登录**只给** `WebLogin` 且地址合法的家；其余家只留一条凭据入口，且文案跟着凭据来源变
  （API Key / 粘贴令牌 / 粘贴 Cookie / 设备授权令牌）。
- `Unknown` 按 `ApiKey` 走（最保守、一定能走完）。

### `:app` 与 `feature:gateway`

- `BuiltinProviders`：`label` 回退到目录、新增 `catalogIds()` / `direct(id)` / `loginKind(id)`。
- `PraxisApp`：内置家行改为**遍历全量目录 61 家**，插件家 `direct=false`、副标题写
  「经网关使用 · 官方设备授权 · 登录与模型目录在网关里，Praxis 不重复实现」。
- `GatewayProvidersScreen`：`GatewayBuiltinProviderRowUi` 增加 `direct` / `loginLabel`；
  只有直连家才画「拉取模型清单」。
- `GatewayAddAccountScreens`：登录方式改为读 `ProviderCatalog.loginOf(providerId)`。

## 四、验证

```
:core:gateway:test   BUILD SUCCESSFUL  406 例全过（新增 ProviderCatalogTest 8 例 + 登录方式 3 例改写）
:core:imagegen:test  BUILD SUCCESSFUL  （上一轮的 DownloadTerminal 8 例）
check_kt_files.py / check_kt_literals.py  8 个改动文件全 OK
```

`:app` 与 `feature:*` 本地编不了（Windows 配置期就失败），只能靠 CI。

## 五、踩到的坑

- **KDoc 里写 `plugins/*/pack.json` 直接编译失败**（`Unclosed comment`）：Kotlin 块注释可嵌套，
  `/*` 会开出内层注释。这是我 memory 里记过的坑，自己又踩了一次 —— 改成 `<pack>` 占位。
- 目录数量断言第一次写成 62（照 pack.json 的 34 数），实际要排除示例 `demo` → 33，总数 61。

## 六、下一步

1. 等 agent-8 的登录矩阵，把 `Unknown` 的 20 家补上证据。
2. 把远端网关的 **173 个模型能力位**做成离线快照进 Praxis（内置家才有真清单）。
3. 经网关的接入形态：Praxis 建一个指向 `/a2a/v1` 的账号 → 38 家插件家全部可用。
