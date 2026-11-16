# 07 · 上游提供商登录取证矩阵（Agent2Providers）

> 取证对象：`https://github.com/ProvidersPlugin/Agent2Providers`，`main` @ `3055f41`（2026-11-05 提交，`pack.json` 版本 3.4.2，AGPL-3.0）。
> 方法：拉取整仓源码（codeload tar.gz）后逐家阅读实现；**只记录事实**，不复制源码。
> 证据列路径**省略公共前缀** `crates/providers/src/`；行号为该 commit 下的实际行。
> 术语按协议定义（`crates/provider-protocol/src/account.rs` 的 `LoginMethod` / `LoginStart`）：
> - **api_key** = 粘贴令牌（`LoginMethod::Token`）
> - **web_login** = 拉起浏览器 / 内嵌页面走官方登录（`LoginMethod::Browser`）
> - **oauth_loopback** = 本机回环端口收授权回调（插件自绑端口，`callback_server.rs` 一类实现）
> - **device_code** = 设备授权 / 服务端轮询型：**没有本机回调**，插件按间隔问上游要结果
> - **token_import** = 粘贴或导入本机客户端登录态（`LoginMethod::Desktop` / 文件导入）
> - **cookie_import** = 读 Cookie（粘贴 Cookie 头或读本机 Cookie 库）
>
> 协议里 `poll_interval_ms > 0` 的含义是「网关主动来问插件」，**与授权在哪里终结无关**；
> 因此「回调型」也可能申报 `> 0`。下表用「本机回调 / 上游终结」区分真实拓扑。

---

## 一、34 家 agent 提供商

| id | 名称 | 登录方式（可多值） | 是否需要客户端伪装 | 主机/端点 | 模型目录来源 | 证据 |
|---|---|---|---|---|---|---|
| demo | 示例提供商（默认关闭） | api_key；web_login（示例同时演示轮询型与回调型两套） | 否（纯示例） | example.com；回调示例 127.0.0.1 | 静态（示例常量） | `providers/demo/mod.rs:59`（Token）、`:64`（Browser）、`:475`（poll 3000）、`:484`（poll 0 + external_browser） |
| commandcode | Command Code | api_key（静态 API Key，**无续期**） | **是（重）**：设备指纹 + 生命周期两条预请求 + CLI 版本身份 | api.commandcode.ai；chat `POST /alpha/generate`（自有 8 键信封 + NDJSON）；models `GET /provider/v1/models`；预请求 `/alpha/fingerprint/record`、`/alpha/lifecycle-events`；探活 `/alpha/billing/credits` | 在线拉取 + **26 项内置兜底清单**（5 分钟 TTL） | `providers/commandcode/mod.rs:84`（唯一 Token）、`:65`（model_refresh）、`endpoints.rs:28` `PROTOCOL_VERSION="1.53.1"`、`:31` `CLI_USER_AGENT`、`models.rs:121` `fallback_models`、模块头「三处硬事实」 |
| modelscope | ModelScope 国内版 | api_key（`ms-` 令牌，**同一串兼作网页 Cookie 值**） | 否（仅 Bearer；`X-ModelScope-Async-Mode` 是功能头） | api-inference.modelscope.cn/v1（chat `/chat/completions`、models `/v1/models`）；网页站 modelscope.cn | 在线拉取（**账号相关目录**，无静态兜底，10 分钟 TTL） | `providers/modelscope/mod.rs:129`（唯一 Token）、`:111`（model_refresh）、`region.rs:8`、`:67`、`models.rs:5`、模块头「三个实测事实」 |
| modelscope-intl | ModelScope 国际版 | api_key（同上；两站账号体系独立） | 否 | api-inference.modelscope.ai/v1；网页站 www.modelscope.ai | 在线拉取（账号相关） | `providers/modelscope/region.rs:9`、`mod.rs:129` |
| orcaterm | OrcaTerm（腾讯） | api_key（粘贴 JWT 或整份 `data.bin`）+ web_login（微信扫码授权页，回调走 `orcaterm://oauth_callback` 自定义协议交回）+ token_import（读桌面端登录态） | 部分：`X-Product`、`x-seqid`、自建 UA 常量 | api.orcaterm.cloud.tencent.com（`OAuthExchangeToken` 匿名换码）；lightai.cloud.tencent.com；授权页 orcaterm.com/oauth/authorize?provider=wechat&source=desktop&return_url=orcaterm://oauth_callback | **静态清单**（`model_refresh: false`） | `providers/orcaterm/mod.rs:102/107/111`（三种方式）、`:86`（model_refresh false）、`:54` `USER_AGENT`、`login.rs:1-20`（链路）、`:102-116`（poll 0） |
| loomy | Loomy（讯飞） | web_login → 实际是**手机号验证码**（`LoginMethod::Sms`，直连上游，`url` 为空、poll 0） | **是**：HMAC-SHA1 签名 + 内置 AccessKey 对（`sign.rs`）+ 客户端 appId / 版本 | account.xfinfr.com（`/login/phone/sendMsgCode`、`/login/phone/checkCode`）；网关 loomyad.xunfei.cn（`/api/v1`：chat、models、`/points/first-login`、`/onboarding/tasks`） | 在线拉取（`/api/v1/models`，**无静态兜底**，拉不到就报错） | `providers/loomy/mod.rs:81`（唯一 Sms）、`:463`「url 为空 + poll 0」、`:481-493`、`endpoints.rs:31` `DEFAULT_APP_ID="GM3LOOMY"`、`:34` `DEFAULT_CLIENT_VERSION="0.9.39"`、`login.rs:41` |
| lobsterai | LobsterAI（有道） | web_login + **oauth_loopback**（`redirect_uri=http://127.0.0.1:<网关端口>/auth/callback`，poll 0 的回调型） | **是**：五个功能性头（含两个 `X-LobsterAI-Client-*`）、UA `LobsterAI/0.1.0`、客户端版本 12 小时缓存 | 门户 lobsterai.youdao.com；API lobsterai-server.youdao.com（`/api/auth/exchange`、`/api/auth/refresh`、`/api/models/available`、`/api/proxy/v1/chat/completions`、`/api/user/profile-summary`）；api-overmind.youdao.com | 在线拉取 + **19 条静态兜底** | `providers/lobsterai/mod.rs:99`（唯一 Browser）、`:380`（poll 0）、`oauth.rs:1-16`（四步）、`endpoints.rs:56` `USER_AGENT`、`mod.rs` 模块头「五处取舍」 |
| raccoon | 小浣熊 | web_login + api_key（`LoginMethod::Token`：粘贴 auth.json 或 accessToken）+ token_import（桌面端 auth.json **实时读盘、不落快照**） | **是**：`X-Client-Platform/Version/Name`、`X-Raccoon-Language`、`X-Org-Code`、`X-Request-Id`；旧 JWT 与 IAM 双令牌体系 | xiaohuanxiong.com（chat `/api/web/llm/v2`；IAM `/authorize`、`/callback`） | 在线拉取 + 静态兜底（`fallback_models`） | `providers/raccoon/mod.rs:72/76/81`、`:428`（poll 0 + external_browser）、`:35` `DEFAULT_LLM_BASE_URL`、`:605`（Desktop 实时读）、`headers.rs`、`iam.rs`、`desktop.rs` |
| atomcode | AtomCode（AtomGit） | web_login（**轮询型，无本机回调**：授权在 `acs.atomgit.com/callback` 终结） | 轻：四件功能性请求头 + UA `agent2api-atomcode/*` | 登录代理 acs.atomgit.com（`/auth/login`、`/auth/check`、`/auth/token`、`/oauth/refresh`）；chat llm-api.atomgit.com/chat/completions；CodingPlan api-ai.gitcode.com（`/coding-plan/{status,models,claim,usage}-v2`） | 在线拉取（`/coding-plan/models-v2`），**无静态兜底**（`with_models(Vec::new())`） | `providers/atomcode/mod.rs:110`（唯一 Browser）、`:405`（poll 3_000）、`oauth.rs`、`endpoints.rs:69`、模块头「登录形态」「四条取舍」 |
| autoclaw | AutoClaw 国内版（智谱） | **手机号验证码**（`LoginMethod::Sms`，国内版唯一入口）；代码另有本机桌面端 `auth.json`(DPAPI)、`~/.openclaw-autoclaw/openclaw.json`、环境变量四条凭据来源，但**未申报 Desktop 登录方式** | **是（最重）**：`X-Authorization` + `X-Auth-Sign`（appId/appKey）客户端指纹、`X-Product/X-Client-Type/X-Tm`、**system 首句指纹改写**、禁止发 `X-Harness-Type`、删请求体 `metadata` | autoglm-acceleration-api.zhipuai.cn（userapi：`/userapi/v1/agent-send-code`、`/userapi/v1/agent-login/`、`/userapi/v1/user-info`；LLM 代理 `…/autoclaw-proxy/proxy/autoclaw`；积分 `/agent-assetmgr/api/v1/points/*`） | 在线拉取（五档模型路由） | `providers/autoclaw/mod.rs:137`（Sms）、`:140`（Intl 的 Browser）、`:92` `DESKTOP_APP_VERSION="1.17.8"`、`refresh.rs:58` `AUTH_APP_ID`、`:73` `CLIENT_VERSION="1.18.5"`、`region.rs`、`prompt.rs`、`web_login.rs`、`:945`（Desktop 归一路径） |
| autoclaw-intl | AutoClaw 国际版 | web_login（两跳 OAuth）+ **oauth_loopback**（`callback_server.rs` 登记端口收尾） | **是**（同上；appId/appKey 两地逐字相同） | autoglm-api.autoglm.ai（同上路径集） | 在线拉取 | `providers/autoclaw/mod.rs:140`、`oauth.rs:36`、`callback_server.rs`、`region.rs:8-9` |
| officeace | OfficeAce（华为云果办） | web_login（华为云 OAuth；**轮询型，云端回调页不往本机跳**） | **是**：SDK-HMAC-SHA256（V11）签名、P-256 **DPoP**、`x-subscription-type` / `X-Security-Token` / `X-Project-ID` | agentarts.cn-southwest-2.myhuaweicloud.com（chat `{规范化基址}/chat/completions`（`model_api_url_base` 规范化，实测含 `/v2`）、`/v1/claw/auth/state`、`/v1/claw/auth/code`、`/v1/claw/client-permission-validate`）；auth.huaweicloud.com；iam.myhuaweicloud.com；sts.cn-north-4.myhuaweicloud.com | 在线拉取（控制面 `client-permission-validate` 的 `subscription.model_info`，按倍率合并） | `providers/officeace/mod.rs:103`（唯一 Browser）、`:437-440`（poll interval + `allowed_hosts=[auth.huaweicloud.com]`）、`oauth.rs:1-30`（九步）、`:57` `CLIENT_ID="pdp5_for_agentarts"` |
| zcode | ZCode（智谱） | web_login（**CLI 轮询**：`POST /api/v1/oauth/cli/init` → `GET /api/v1/oauth/cli/poll/{id}`，**明确没有本机回调**，本地 localhost redirect 会被上游拒） | **是**：`X-Device-Mid` 设备号、`X-Platform`、`X-ZCode-App-Version`、阿里云验证码参数（`x-aliyun-captcha-verify-*`） | zcode.z.ai（`/api/v1/oauth/cli/*`、`/api/v1/zcode-plan/billing/*`、`/api/v1/client/configs`）；推理 open.bigmodel.cn / api.z.ai（`/anthropic/v1/messages`） | **静态清单**（`model_refresh: false`）+ 客户端配置同步 | `providers/zcode/mod.rs:193`（唯一 Browser）、`:172`（model_refresh false）、`login.rs:73-93`、`oauth.rs:1-45`（「没有本地回调」原文）、`credentials.rs:6-7`、`config_sync.rs:10` |
| zcode-intl | ZCode 国际版 | web_login（同一套 CLI 轮询；中转页参数名两地不同：`redirect_uri` vs `redirect`） | **是**（同上） | 同上（国际走 `api.z.ai` / `zcode.z.ai`） | **静态清单** | `providers/zcode/oauth.rs` 模块头「参数名两地不同」、`mod.rs:193/172` |
| workbuddy | WorkBuddy 国内版（腾讯） | web_login（`POST /v2{prefix}/auth/state` → 打开 `authUrl` → 轮询 `/auth/token?state=`，**上游终结、无本机回调**）；代码另有 Desktop 文件导入路径（`accounts.rs:60`），但登录方式里**未申报** | **是**：`X-Product: SaaS`、`X-Requested-With`、`X-IDE-Type/Name/Version`、按地区切换 UA（`user_agent_for_edition`） | copilot.tencent.com（国内；platform=workbuddy）；wb.tencentbuddy.com；static.workbuddy.cn；`/v2/billing/*`、`/activity/growth/*` | 在线拉取（`catalog_state`） | `providers/workbuddy/mod.rs:205`（唯一 Browser）、`login.rs:1-30`（五步链路）、`:133-136`、`region.rs:6-10`、`contract.rs:6`（`framework_headers`）、`workbuddy_shared/endpoints.rs:215` |
| workbuddy-intl | WorkBuddy 国际版 | web_login（同协议，`platform=workbuddy-ai`） | **是**（UA 里的产品段是 `WorkBuddy AI`） | www.workbuddy.ai | 在线拉取 | `providers/workbuddy/region.rs:7`、`workbuddy_shared/endpoints.rs:121-135` |
| cline-free | Cline（免费额度池） | **device_code**（WorkOS RFC 8628：`POST /user_management/authorize/device` → 用户输 user_code → `POST /user_management/authenticate` 轮询 → `POST /auth/register` 换本家会话令牌） | **是**：`X-CLIENT-TYPE`、`X-CLIENT-VERSION`、`X-PLATFORM`、UA `Cline/3.0.62`、`HTTP-Referer: https://cline.bot` | api.cline.bot（chat `/api/v1/chat/completions`、models `GET /ai/cline/recommended-models` 无需鉴权）；api.workos.com；授权页 authkit.cline.bot/device | 在线拉取（**目录接口无鉴权**、`refresh_uses_account=false`）+ 内置清单 | `providers/cline/mod.rs:135`（唯一 Browser = device）、`:116`（model_refresh）、`login.rs:1-30`（四步）、`:158`（poll = 上游 interval×1000）、`credentials.rs:52/55/69/72`、`headers.rs:35` |
| cline-pass | Cline（Pass 额度池） | device_code（同一份实现按池参数化） | **是**（同上） | 同上 | 在线拉取（按池分清单，`models::Pool`） | `providers/mod.rs` registry `ClineProvider::FREE/PASS`、`cline/mod.rs:31-32`、`models.rs:475` |
| kuku | 库库 AI / GenFlowPro（百度） | web_login（**回调型 poll 0**：打开主站，壳侧从 WebView 的 Cookie 存储读 `BDUSS` 后把整串 Cookie 当 `code` 交回）+ cookie_import（粘贴 `BDUSS`/`STOKEN`）+ token_import（读桌面端 Cookies SQLite；`FileBase64` 文件导入亦支持） | **是**：自建 UA、MD5 签名（内置密钥）、业务会话 STOKEN 换发 | kuku.baidu.com（`/api/genflowpro/*`、`/wenchain/genflowpro/*`、`/bizapi/gfpro/getgfvipremain`）；passport.baidu.com | 在线拉取（`/wenchain/genflowpro/model_list`）+ 静态兜底 | `providers/kuku/mod.rs:155/161/167`（三种）、`:50-52`（「网页登录是回调型 poll=0」原文）、`:96` `USER_AGENT`、`login.rs:69`（poll 0）、`accounts.rs:81`（Desktop） |
| monkeycode | MonkeyCode 国内版（长亭） | web_login（**内嵌桌面窗口**完成官方登录 + 验证码，宿主读 HttpOnly `monkeycode_ai_session` 后经 `login.callback` 交回；poll 0）+ cookie_import（粘贴 session / 整行 Cookie） | 轻：UA `monkeycode-local-proxy`、功能头（站点与 API 同域） | monkeycode-ai.com；`/api/v1/users/status`、`/api/v1/users/models`、`/api/v1/users/tasks`、WS `/api/v1/users/tasks/stream` | 在线拉取（`/api/v1/users/models`） | `providers/monkeycode/mod.rs:119/123`、`:101`（model_refresh）、`web_login.rs:40-42`（poll 0 + `allowed_hosts=region.login_hosts()`）、`endpoints.rs:92` |
| monkeycode-intl | MonkeyCode 国际版 | 同上（`Region` 参数化） | 轻（同上） | monkeycode-ai.net | 在线拉取 | `providers/monkeycode/region.rs`、registry 两实例 |
| qoder | Qoder 国内版 | **device_code**（PKCE 设备授权：`POST /device/selectAccounts` → `GET /api/v1/deviceToken/poll`）+ api_key（**PAT 个人访问令牌**，仅 Qoder 两站有） | **是**：COSY 自签名头 + 请求体先编码再签名、`X-Model-Key`/`X-Model-Source`、客户端版本 `1.1.38` / `1.1.0` | gateway.qoder.com.cn（chat `POST {gw}algo/api/v2/service/pro/sse/agent_chat_generation`、models `GET {gw}algo/api/v2/model/list`）；openapi.qoder.com.cn（`/device/selectAccounts`） | 在线拉取（**Qoder 两站无静态兜底**：`fallback_models()` 返回空） | `providers/qoder/mod.rs:139`（Token = PAT，条件 `site.supports_pat()`）、`:145`（Browser）、`oauth.rs:1-20`（「设备授权 PKCE」）、`:187`（poll 2_000）、`endpoints.rs:433`（`device_only`）、`:438`（`supports_pat` 仅 Qoder）、`:479`、`cosy.rs:53` |
| qoder-intl | Qoder 国际版 | device_code + api_key（PAT） | **是**（同上） | api3.qoder.sh / openapi.qoder.sh | 在线拉取（无静态兜底） | 同上 + `endpoints.rs` Site 参数化 |
| qwenwork | 千问办公 国内版 | **device_code**（官方**设备授权页**，授权完本插件轮询收账号；`device_only=true`，**无 PAT、无 jobToken 交换**） | **是**（与 Qoder 共用 COSY 协议栈） | gateway.qwenwork.cn（`{gw}algo/...`；`/api/v1/deviceToken/poll`） | 在线拉取（`{"qwork":[…]}` 单场景，含 `enable:false` 条目）+ **静态兜底** `qwenwork_fallback_models()` | `providers/qoder/mod.rs:145` 的 hint「千问办公：官方设备授权页」、`endpoints.rs:430-436`（`device_only()`）、`:479-486`（千问兜底清单）、`mod.rs` registry `QWENWORK_CN/INTL` |
| qwenwork-intl | 千问办公 国际版 | device_code | **是**（同上） | gateway.qwenwork.ai | 在线拉取 + 静态兜底 | 同上 |
| trae | Trae（字节） | web_login + **oauth_loopback**（授权页 302 回 `127.0.0.1:<port>`，后台任务换证） | **是**：`X-Machine-Id`/`X-Device-Id`/`X-Uid`/`X-App-Id`/`X-Ide-Version`、UA `Trae/0.1.69`、`OS_VERSION="Windows 11 Pro"` 写死 | api.trae.cn / api.trae.com.cn（chat `/api/agent/v3/llm_utils_chat`、换码 `/trae/api/v3/oauth/ExchangeToken`、用户 `/cloudide/api/v3/trae/GetUserInfo`）；授权页 www.trae.cn；另 trae-api-cn.mchost.guru | 在线拉取 | `providers/trae/adapter.rs:78/82`、`login.rs:1-25`、`:116-122`（poll 2_000）、`headers.rs:9-15`、`oauth.rs:33/72/74` |
| catpaw | CatPaw（美团） | web_login + **oauth_loopback**（passport 登录后 `login-callback` 把 `{token,state}` **POST 到本机**；另有一条 `poll-token` 兜底通道，绕过浏览器 CORS / PNA）+ api_key（粘贴 accessToken / auth.json）+ token_import（auth.json **实时读、记录里不落 token**） | **是**：入口地址由 `GET /api/gateway/passport/login-config` 动态取；`X-Auth-Token`、`x-session-id`、`X-Agent-Version`；客户端 / 工具版本 `2.0.2` / `1.0.1` | catpaw.meituan.com（passport 登录入口）；ai.catpaw.meituan.com（`/api/agent/maas/model-types` 目录等）；catx.nocode.cn | 在线拉取 | `providers/catpaw/adapter.rs:105/109/120`、`login.rs:1-40`（双通道原文）、`:169-174`、`mod.rs:102/106`、`accounts.rs:61` |
| accio | Accio 国际版 | web_login + **oauth_loopback**（`loopback + PKCE(S256) + state`，`client_id=accio-work`；回调落插件自绑端口） | **是**：`x-language`/`x-app-version`/`x-package-region`/`x-platform`/`x-os`、UA `agent2api-accio/3.3.0`、应用版本 `0.34.4` | www.accio.com（区域 GLOBAL）；推理网关 phoenix-gw.alibaba.com/api/adk/llm；`/api/oauth/token`、`/api/auth/refresh_token`、`/api/auth/userinfo`、`/api/llm/config`；`/api/entitlement/*` | 在线拉取（`/api/llm/config`） | `providers/accio/adapter.rs:173/180`、`login.rs:1-20`（四步 PKCE）、`:126-131`（poll 2_000）、`endpoints.rs:16/40/66/77/86` |
| accio-cn | Accio 国内版 | web_login + oauth_loopback | **是**（同上，`x-package-region: CN`） | www.accio-ai.com（区域 CN） | 在线拉取 | `providers/accio/endpoints.rs:17/111`、registry `ACCIO/ACCIO_CN` |
| codearts | CodeArts（华为云） | web_login + **oauth_loopback**（`loopback + PKCE + DPoP`；portal **只认我们给的 port**，回调路径它自己拼 `/oauth/callback`；另有 `/snap-manager/v1/login/ticket` 轮询兜底，经它落账**无 refresh token**）+ api_key（粘贴 AK/SK 或整份 auth 文件） | **是**：`x-client-type`、`x-snap-traceid`、`X-Sdk-*`（SDK-HMAC）、`X-Domain-Id`、DPoP 签名、插件版本 `26.9.101`、`client_id=vscode-codebot` | opengw.developer.huaweicloud.com（chat `/api/v2/chat/completions`、`/snap-manager/v1/*`）；codearts.huaweicloud.com；sts.cn-north-4.myhuaweicloud.com；snap-access.cn-north-4.myhuaweicloud.com | 在线拉取 | `providers/codearts/adapter.rs:172/176`、`login.rs:1-40`（两次回调 + ticket 兜底）、`:132-137`、`chat.rs:40`、`oauth.rs:36` |
| antigravity | Antigravity（Google） | web_login + **oauth_loopback**（Google OAuth 授权码，插件一轮一个随机端口）+ api_key（粘贴 refresh token / access token，形态宽松归一） | **是**：Google `client_id`、`x-goog-user-project`、`x-client-name/version`、UA 版本镜像（`KNOWN_STABLE_VERSION="4.3.0"`，可用环境变量覆盖） | accounts.google.com；oauth2.googleapis.com；daily-cloudcode-pa.googleapis.com（推理）；cloudcode-pa.googleapis.com；antigravity.google | 在线拉取 | `providers/antigravity/adapter.rs:79/87`、`login.rs:1-25`（粘贴式 + loopback）、`:316-321`、`endpoints.rs:62/136/193/308` |
| doubaowork | 豆包工作 | web_login（官方页面登录，成功后保存**完整 Cookie**；poll 0）+ cookie_import（粘贴 `sessionid`/`sessionid_ss` 或 Cookie JSON）+ token_import（读本机客户端登录数据，`cookies_db.rs`） | **是**：Chrome UA 伪装（`Mozilla/5.0 … Chrome/14x`）+ `DEFAULT_CLIENT_VERSION="2.33.5"` | www.doubao.com（chat `/chat/completion`、`/alice/user/launch`、`/alice/slot/action_bar_v3/brief_list`、`/alice/ug/campaign/*`）；上传前置 `/alice/resource/prepare_upload` | 在线拉取（`/alice/slot/*`） | `providers/doubaowork/mod.rs:37/40/43`、`api.rs:13` `BASE_URL`、`:16` `DEFAULT_CLIENT_VERSION`、`login.rs:47-49`（poll 0）、`accounts.rs:263`、`desktop.rs`、`cookies_db.rs` |
| kiro | Kiro（AWS） | web_login + **oauth_loopback**（门户 OAuth；监听 **Kiro IDE 那组端口** 3128/4649/6588/8008/9091/…；Google/GitHub 换 `prod.us-east-1.auth.desktop.kiro.dev/oauth/token`，Builder ID / IDC 走 AWS OIDC）+ token_import（导入 kiro-cli / Kiro IDE 登录态）+ api_key（`{"apiKey":"ksk_…"}`） | **是**：`x-amz-user-agent`、`x-amzn-kiro-*`、UA 镜像 `KiroIDE-<version>-<hash>`（hash 由**主机名**派生）、Builder ID 固定 profile ARN | app.kiro.dev（门户）；prod.us-east-1.auth.desktop.kiro.dev；oidc.{region}.amazonaws.com；management.{region}.kiro.dev（目录 / 用量）；runtime.{region}.kiro.dev/generateAssistantResponse（推理） | 在线拉取（`management.{region}.kiro.dev`）+ 静态兜底 | `providers/kiro/mod.rs:78/86/94`、`login.rs:1-20`（四步）、`:167-169`（poll 1500 + external_browser）、`:238-240`、`endpoints.rs:24-52`（PORTAL / AUTH_SERVICE / CALLBACK_PORTS / SCOPES）、`:62` `ide_user_agent`、`accounts.rs:43`（Desktop） |
| astudio | AStudio（讯飞 Astron Studio） | web_login（**本机内嵌登录页** `127.0.0.1`：手机号验证码 / 账号密码 + **极验滑块**；poll 1500）+ api_key（粘贴 `modelBearerToken`，可带 `sessionToken`/`accountId`/`ssoSessionId` 以启用余额与签到）；桌面端登录态归一仍存在（`accounts.rs:366`），但 `login_start` 已不再接受 methodIndex 0 | 轻：`x-login-token`、客户端版本 `STUDIO_VERSION="3.5.1"` | 模型 astronstudio-api-volces-prod.xf-yun.com（OpenAI **Responses**：`/model-gateway/v1/responses`）；网页会话 agent.xfyun.cn（积分 / 会员 / 签到） | 在线拉取 + **静态兜底**（`fallback_models`） | `providers/astudio/mod.rs:67`（Token）、`:164-190`（`login_start` 的 methodIndex 分流）、`login.rs:134`（`login_methods()` 动态申报 `type: oauth` + `type: api`）、`login_page.rs:71-76`、`accounts.rs:366`、`client.rs:16` |

---

## 二、网页插件 4 家（不在上述仓库内，证据来自本机安装件）

> 这 4 家的源码**不在** `ProvidersPlugin/Agent2Providers` 仓库（该仓库只有 `crates/provider-protocol`、`crates/provider-sdk`、`crates/providers` 三个 crate）。
> 证据 = 本机 `C:\Users\sxd\.agent2api\plugins\agent2api-web-providers\3.4.3`（`pack.json`、`ui/provider-docs.json`、`bin/agent2api-web-providers.exe` 的字符串），**属已安装产物，不是公开源码**——标为「本机安装件取证，源码未确认」。

| id | 名称 | 登录方式（可多值） | 是否需要客户端伪装 | 主机/端点 | 模型目录来源 | 证据 |
|---|---|---|---|---|---|---|
| chatglm | 智谱清言 | api_key（粘贴令牌）+ token_import（Windows 本机登录态导入）+ guest（访客模式，可换发凭证） | 未确认 | chatglm.cn | 在线（`model_refresh: supported`） | `pack.json` providers[0]；`ui/provider-docs.json` → chatglm.notes（「支持访客模式、粘贴令牌和 Windows 本机登录态导入」）；exe 字符串 `chatglm.cn` |
| deepseek-web | DeepSeek 网页 | api_key（`userToken`）+ cookie_import（Cookie） | 未确认（人机验证需在官方网页手动完成） | chat.deepseek.com | 在线（`model_refresh: supported`，仅 `deepseek-web-flash`） | 同上；docs notes「使用本人 chat.deepseek.com 的 userToken 与 Cookie」；exe 字符串 `chat.deepseek.com` |
| tabbit | Tabbit 国内版 | web_login → 手机号验证码 + cookie_import（Cookie 里的 `token`/`user_id`，或 DevTools「Copy as cURL」） | 未确认（国内 / 国际**签名 key 不同**） | web.tabbit.com | 在线 | docs notes（「国内站支持手机号验证码登录…两套账号，令牌不通用、签名 key 也不同」）；exe 字符串 `web.tabbit.com` |
| tabbit-intl | Tabbit 国际版 | cookie_import（同上，仅网页登录态） | 未确认 | web.tabbit.ai | 在线 | docs notes；exe 字符串 `web.tabbit.ai` |

---

## 三、按登录方式分组小结

### A. 只要 API Key（粘贴令牌即可用，无网页流程）
- **commandcode** — 静态 API Key，唯一入口，无续期。
- **modelscope / modelscope-intl** — 一个 `ms-` 令牌两用（Bearer 推理 + 网页 Cookie 值）。
- **demo** — 示例（同时演示网页登录两套形态，实际不接上游）。

### B. 必须走网页登录（`web_login`），且**本机回环回调**
- **accio / accio-cn**（loopback + PKCE）
- **antigravity**（Google OAuth，一轮一个随机端口）
- **codearts**（loopback + PKCE + DPoP，另有 ticket 轮询兜底）
- **kiro**（监听 Kiro IDE 那组固定端口；社交登录与 AWS OIDC 两条换码路）
- **lobsterai**（`http://127.0.0.1:<网关端口>/auth/callback`）
- **trae**（授权页 302 回 `127.0.0.1`）
- **catpaw**（passport 页 POST 回本机 **+** `poll-token` 双通道）
- **astudio**（**本机内嵌**登录页，不是系统浏览器；含极验滑块）
- **autoclaw-intl**（`callback_server` 登记端口）
- **kuku**（回调型 poll 0，壳侧从 WebView 取 `BDUSS` 当 `code` 交回）

### C. 必须走网页登录，但**没有本机回调**（设备码 / 服务端轮询）
- **cline-free / cline-pass** — WorkOS RFC 8628 **设备码**（用户手输 `user_code`）。
- **qoder / qoder-intl / qwenwork / qwenwork-intl** — **设备授权 + PKCE 轮询**（千问办公是官方设备授权页，`device_only`）。
- **zcode / zcode-intl** — CLI 轮询（`oauth/cli/init` + `poll`），本地 redirect 会被上游拒。
- **officeace** — 华为云 OAuth，云端回调页不往本机跳，只能轮询取 code。
- **atomcode** — 授权在 `acs.atomgit.com/callback` 终结，只能轮询。
- **workbuddy / workbuddy-intl** — `auth/state` → 打开 `authUrl` → 轮询 `auth/token`。
- **doubaowork** — 官方页面登录，成功后由宿主保存完整 Cookie。

### D. 手机号 / 验证码（`LoginMethod::Sms`）
- **autoclaw**（国内版唯一入口）、**loomy**（唯一入口，无任何备用方式）、**astudio**（内嵌页，与账号密码并列）、**tabbit**。

### E. 要 Cookie（`cookie_import`）
- **kuku**（`BDUSS`/`STOKEN`，或本机 Cookies SQLite）
- **doubaowork**（`sessionid`/`sessionid_ss`，或本机客户端 Cookie 库）
- **monkeycode / monkeycode-intl**（`monkeycode_ai_session`）
- **deepseek-web**（`userToken` + Cookie）
- **tabbit / tabbit-intl**（Cookie 里的 `token`/`user_id`）

### F. 支持「导入本机客户端登录态」（`LoginMethod::Desktop`，界面可见）
- **catpaw**（`auth.json`，实时读、不落快照）、**doubaowork**（Cookie 库）、**kuku**（Cookies SQLite）、**kiro**（kiro-cli / Kiro IDE）、**orcaterm**、**raccoon**。
- 代码里有 Desktop 归一但**未在登录方式里申报**：**autoclaw**（`mod.rs:945`）、**workbuddy**（`accounts.rs:60`）、**astudio**（`accounts.rs:366`，且 `login_start` 已明确拒绝该分支）。

### G. 只此一条路（没有备用登录方式）
- 只申报 `Browser`：**lobsterai**、**officeace**、**atomcode**、**workbuddy / -intl**、**zcode / -intl**、**cline-free / cline-pass**、**qwenwork / -intl**。
- 只申报 `Token`：**commandcode**、**modelscope / -intl**。
- 只申报 `Sms`：**loomy**。

### H. 必须带客户端伪装（特殊头 / UA / 设备号 / 请求体改写）
按强度分档（都是为绕上游客户端校验，不是可选优化）：
1. **改写请求体或提示词**：autoclaw（system 首句指纹改写 + 删 `metadata` + 禁 `X-Harness-Type`）、commandcode（自有 8 键信封 + 两条预请求）、qoder / qwenwork（COSY 编码 + 签名）、officeace / codearts（SDK-HMAC + DPoP）。
2. **设备号 / 版本身份**：trae（`X-Machine-Id`/`X-Device-Id` + `Trae/0.1.69` + 写死 `Windows 11 Pro`）、zcode（`X-Device-Mid`）、kiro（UA 由主机名派生 `KiroIDE-<ver>-<hash>`）、cline（`Cline/3.0.62`）、doubaowork（Chrome UA）、workbuddy（`X-IDE-*` + 按地区 UA）、catpaw（`X-Agent-Version`）、antigravity（Google client 头 + 版本镜像）。
3. **仅功能性头 / 版本上报，无身份伪装**：modelscope（`X-ModelScope-Async-Mode`）、monkeycode（UA `monkeycode-local-proxy`）、astudio（`x-login-token`）、atomcode（四件功能头）、kuku（内部 MD5 签名）、lobsterai（`X-LobsterAI-Client-*`）、orcaterm（`X-Product`）、raccoon（`X-Client-*`）、accio（`x-platform` 系）、codearts 之外的 OfficeAce 普通业务头。

### I. 模型目录来源分布
- **纯在线**（拉不到就报错 / 不兜底）：modelscope / -intl、loomy、qoder / -intl、atomcode、officeace、workbuddy / -intl、monkeycode / -intl、catpaw、trae、codearts、accio / -cn、antigravity、doubaowork、autoclaw / -intl。
- **在线 + 内置静态兜底**：commandcode（26 项）、lobsterai（19 条）、raccoon、kuku、kiro、astudio、qwenwork / -intl、cline（目录接口无鉴权）。
- **纯静态清单**：orcaterm（`model_refresh: false`）、zcode / -intl（`model_refresh: false`，另有 `/api/v1/client/configs` 配置同步）。

---

## 四、未确认 / 存疑（不猜）

1. **网页插件 4 家的源码**不在本仓库；结论只能来自本机安装件的 docs 与二进制字符串，**未见实现代码**。tabbit 的「签名 key」、chatglm 的访客换发流程、deepseek-web 的人机验证处置，实现细节**未确认**。
2. **workbuddy / autoclaw / astudio 的 Desktop 导入**：代码路径存在但登录方式未申报（astudio 明确拒绝 methodIndex 0）。网关界面是否另有入口**未确认**。
3. **zcode 的 API Key 登录**：只有 `coding_key.rs`（拿 `access_token` 换编码套餐 Key），**没有**用户粘贴 API Key 的登录方式；账号面另有激活码（`activation.rs`）与验证码池（`AdminPanel::CaptchaPool`），性质未在登录方式中体现。
4. **loomy 的 session 有效期**：模块头写 14 天且无 refresh 接口，属作者自述，**未在本机实测**。
5. **各家是否需要代理**：仅 antigravity / zcode 注释提到本机访问需代理，其余**未系统确认**。
6. **`poll_interval_ms` 与真实轮询节奏**：本表记录插件申报值（accio / antigravity / codearts 2 000、atomcode 3 000、kiro 1 500、astudio 1 500、cline 跟随上游 interval），**不代表上游限流建议**。
7. **kiro 的 `apiKey` 与 `ksk_` 前缀**：来源是 \u0060LoginMethod::Token\u0060 的 label/placeholder 常量，**未验证该 Key 的实际签发口径与可用范围**。
