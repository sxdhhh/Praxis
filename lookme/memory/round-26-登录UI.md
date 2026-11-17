# round-26 33 家登录 UI（H5 容器）

日期：2026-11-17

## 协议先读源码，再写代码

从 web_shim.rs 抄准的真语义（不是猜的）：
- `GET /api/session/login/methods?provider=` → `{methods:[{methodIndex,label,manualCallback,callbackInput,localOnly}],instructions}`
  **methodIndex 不是数组下标**，回传时必须用这个编号
- `POST /api/session/login/start` `{provider,edition,mode:"embedded",methodIndex,socialRestore}` → `{state,authUrl,pollIntervalMs,instructions}`
- `GET /api/session/login/wait?state=` → `{done,pending:{hint},session:{accountId},error}`；**done && !pending 才算完成**
- `POST /api/session/login/callback` `{state,callbackUrl}`（manualCallback 的家）
- `POST /api/session/login/cancel` `{state}`（Trae 的回调端口靠它释放）

## 交付

1. `ProviderLoginFlow.kt`：LoginMethod/LoginMethods/LoginStart/LoginWait + 解析（容错，不抛）
2. `LoginFlowRules.kt`：状态机判定（超时文案给下一步、20 秒后主动亮粘贴框、code/url 区分）
3. `LoginPhase`：Idle/Starting/Waiting(url,hint,elapsed)/NeedCallback/Succeeded/Failed
4. `GatewayConnection` +5 方法：loginMethodsOf/startLogin/waitLogin/submitLoginCallback/cancelLogin/listAccounts
5. `PraxisWebViewSheet` 加 `onUrlChanged` —— **登录的关键**：H5 容器捕获回调 URL 直接交给网关换码，用户不用手工复制
6. `GatewayLoginScreen.kt`：方式列表 / 等待（含计时）/ 粘回调 / 成功 / 失败，H5 容器 560dp
7. 复用已有 `SettingsGatewayAddLogin(providerId)` 路由：Agent 家走登录助手，`agent2api` 走直填 Key

## 验证

core:gateway **451 例全绿**（+11 登录流程用例）。configuration cache 已生效（重跑 20 秒 up-to-date）。

## 修的坑

- 我发明的 `PraxisRoute.SettingsGatewayLogin` 路由不存在（sealed class 会编译失败）→ 复用 `SettingsGatewayAddLogin`
- SettingsHost 缺 LaunchedEffect/remember/getValue/setValue import
- buildJsonObject 全限定 → 补 import 后改短名