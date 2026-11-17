# round-28 导入账号 + 保活（维护页）

日期：2026-11-17

## 网关真实语义（读源码）

- `POST /api/accounts/import`：body 就是导出文件原样 `{accounts:[...],customProviders:[...]}`；
  网关还兼容 `{success,data:{accounts}}` 信封壳（旧网页导出的历史包袱）—— 所以 Praxis 原样转交。
- `GET /api/checkin-keepalive` / `POST /api/checkin-keepalive`：保活配置读写（原样 JSON）。
- `POST /api/checkin-activity` `{id,mode}`（mode: full|claim|keepalive）：单账号粒度活跃任务。

## 交付

1. `GatewayConnection` +4 方法：importAccounts / keepaliveState / saveKeepalive / runActivity
2. `GatewayMaintenanceScreen`：粘贴账号 JSON → 导入网关；保活 JSON 读/改/存
3. 路由 `SettingsGatewayMaintenance`；两层接线 + PraxisApp 状态与动作

## 目标完成度（用户列的全部功能）

| 功能 | 状态 |
|---|---|
| 登录（33 家 + H5 容器） | ✅ |
| 签到 | ✅ |
| 拉积分 | ✅ |
| 拉模型 | ✅ |
| 导入账号 | ✅（本轮） |
| 保活 | ✅（本轮） |

## 验证

core:gateway **456 例全绿**。未推 CI（按指示最终审查后统一推）。

## 下一步

**最终审查 + 推 CI**（这是用户明确要求的最后一步）。需要：
1. 自查所有新文件 import 完整性（:app/:feature 本机编不了，只能静态查）
2. 检查 sealed class when 分支完整性
3. 推送并盯 CI 红绿，红灯挖根因再修