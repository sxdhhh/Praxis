# 网关工程体检（lead 完成，2026-11-16）

## 做了什么
读了 `core/gateway/GatewayImporter.kt`、`GatewayAccountFile.kt`、`GatewayExportPlan.kt`、`GatewayExporter.kt`、
新增的 `GatewayAccountsPack.kt`，以及用户提供的真实导出（`agent2api-accounts (1) (2).json`，95 账号 / 751KB，
只在仓库外分析，**未入库**）；改动 `GatewayImporter` / `GatewayExportPlan` / `app/ui/PraxisApp.kt` 导出接线。

## 体检结论
1. **导出回灌此前从未成立**：旧导出是「一账号一文件 + `format:agent2api-account`」，网关认的是整包
   `{version,exportedAt,accounts[],customProviders[]}`。已修：`GatewayAccountsPackCodec` + `emitGatewayPack`。
2. **导入的跳过原因此前错误归因**：插件家的 OAuth 会话被写成「Praxis 不认识这家」。已修：三种原因分开
   （插件家 / 不认识 / 无凭证），插件家明确指向「经网关接入」。
3. **这份 95 账号的文件，Praxis 能直接用的只有 1 条**：`custom-e1a0f12f950e`（Gemini，apiKey 型）。
   其余 94 条是 workbuddy/trae/raccoon/autoclaw/qoder/loomy 等插件家的会话令牌 —— Praxis 没有客户端实现，
   导入会被如实跳过并给原因。
4. **让 38 家插件家真正可用的最短路径是「经网关」**：Praxis 里建一个账号指向网关 `/v1`（本机 3065 或
   远端 110.42.47.142:3065）+ 网关 Key，模型从网关 `/v1/models` 拉（173 个、带能力位）。这条路今天就能走。
5. **customProviders 双向丢失（旧 G9）已由整包修复**：导出带 `customProviders`（含 models 数组），
   导入读它并过滤 `enabled=false`。

## 遗留
- `:app` 导出接线（`emitGatewayPack = true`）本地编不了，等 CI（run 43901055219）。
- 「经网关」账号尚未在 UI 里有专门入口（可作为下一个小改动：内置家里加一条「网关（Agent2API）」）。
