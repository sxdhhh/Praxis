# round-05 账号整包兼容 + 团队机制纠错

日期：2026-11-16

## 一、你指出的两件事

1. **「从 agent2 网关导出的账号应该能导进我们软件，我们也能导出回去」** —— 对，而且此前只有一半能用：
   - 导入侧本来就能读整包形状（`accounts` + `customProviders`），但把 OAuth/会话型账号全当「没凭证」跳过，
     而且原因写成「不认识这家」——**对 trae/workbuddy 这种插件家是错的归因**。
   - 导出侧产出的是「一账号一文件 + `format: agent2api-account`」，而网关导出/导入的是**整包**
     （`version` / `exportedAt` / `accounts[]` / `customProviders[]`）——所以「Praxis → 网关」这条路从来不通。
   实测那份真实导出：95 个账号，顶层就这四个键；账号字段有 accessToken / refreshToken / endpoint /
   edition / priority / pluginLedger / credits 等一大堆。

2. **「智能体团队创建成子代理了」** —— 也对。我用 `subagent` 建了 8 个**子代理**，并发上限 8，结果 8 个全挂
   （provider 侧失败，无产出）。正确做法是用**共享任务板 + 唤醒既有团队成员**（`team_task_create` →
   `team_task_update` reassign 到具体 owner → `send_message` 派活）。已改正。

## 二、代码：整包形状单一真相

新增 `core/gateway/GatewayAccountsPack.kt`：`GatewayAccountsPack` / `PackAccount` / `PackProvider` / `PackModel`
+ `GatewayAccountsPackCodec`（decode / encode / build）。要点：
- `PackAccount.resolvedBaseUrl`：真实导出里账号级地址叫 `endpoint`；
- `hasCredential` / `isOauth`：把「有凭据」与「是 OAuth 会话」分开，导入报告据此给不同原因；
- 编码时剔掉无 id/provider 的空壳行；`models` 保留 enabled 标志（导入时再过滤）。

导入侧（`GatewayImporter`）：改用共享 codec（删掉私有 Bulk* 类型），并区分三种跳过原因：
插件家（`ProviderCatalog.of(id).direct == false`）/ 不认识这家 / 无凭证。插件家文案明确写
「由网关插件实现，登录与会话都在网关侧，Praxis 里用不上 —— 请改用经网关方式接入」。

导出侧（`GatewayExportPlanner`）：新增 `packFile()` 与 `emitGatewayPack` 参数（默认 false 保持老用例不变），
产出 `agent2api-accounts.json`；「仅配置」粒度下**清空**凭证而不是写 `***`（占位符会被网关当真 Key 存进去，
然后每条都 401，比不带更糟）。

## 三、验证

`:core:gateway:test` **415 例全绿**（新增 `GatewayAccountsPackTest` 7 例：往返、OAuth/APIKey 区分、
空包判失败、models 启停保留、仅配置清空凭证、含凭证保留、整包可选项）。

## 四、这轮踩的坑（第 2 次同一个）

KDoc 里写 `accounts/*.json` 又触发 `Unclosed comment`（Kotlin 块注释可嵌套）。
**规则：KDoc 里永远不要出现 `/*`。**` 改成 `<name>` 占位。

## 五、团队机制（已修正）

- 角色任务用 `team_task_create` 建在共享板（task-15..26），**必须 `reassign` 到具体 owner**，
  否则「有任务没人」。
- 派活用 `send_message` 唤醒既有成员（8 个 UUID 队友）；`subagent` 建的子代理有 8 并发上限，
  且这批全失败了 —— 不要再拿它当团队用。
- 并发上限内先派 8 个角色，其余 4 个（product / docs / arch / ux）归我（lead）自己做。