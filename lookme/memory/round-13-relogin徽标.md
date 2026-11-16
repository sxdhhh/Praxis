# round-13 needsRelogin 变真信号（收尾 G5）

日期：2026-11-17

上一轮 G5 降级后，`needsRelogin` 只有写入方、没有读者 —— 又半个「有写无读」。本轮补齐：

- `RouteState`：`needsRelogin` 集合 + `markNeedsRelogin(id)` / `needsReloginOf(id)`；
  `onSuccess` 时清除（换上新 Key 的那一刻提示就该消失 —— 它不是惩罚）。
- `RouterSession`：标 `reloginAccounts` 的同一行调 `state.markNeedsRelogin`。
- `GatewayAccountRow.needsRelogin`：由 `from(state)` 带出（调用方零改动）。
- 账号页：`needsRelogin == true` 时在状态灯旁显示「凭证过期 · 需重新登录」红徽标。

## 验证

`:core:gateway` **428 例全绿**（+2：RouteState 标记/清除、Session→State 链路断言）。

## 提交

- `fix(gateway): needsRelogin 从死信号变成账号页可见的「需重新登录」徽标`