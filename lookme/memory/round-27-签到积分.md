# round-27 签到/积分页

日期：2026-11-17

## 网关真实语义（读 api/billing.rs 拿到的）

- `POST /api/checkin`：**已领取时返回 409 + success:false + msg**（如「今日已签到」）
  —— 这是正常结果，面板前端按 warn 展示；Praxis 同样按提示色展示，不算错误。
- `POST /api/checkin/claim-and-report`：签到+查余额一把。
- `GET /api/usage`：积分汇总。
- `POST /api/auto-checkin/run`：对全部目标账号跑一轮。

## 交付

1. `GatewayConnection` +6 方法：checkinStatus / claimCheckin / claimAndReport / usageSummary / autoCheckin / runAutoCheckin
2. `CheckinResult` + `CheckinRules`（409 分支、余额字段多分支兜底 credits/balance/total/remain）
3. `GatewayCheckinScreen`：签到/自动签到/刷新积分，busy 防重复点击
4. 路由 `SettingsGatewayCheckin`；两层接线；busy 状态防抖

## 验证

core:gateway **456 例全绿**（+5 CheckinRulesTest）。

## 全功能清单对照（用户目标）

登录 ✅（round-26）· 签到 ✅（本轮）· 拉积分 ✅（本轮）· 拉模型 ✅（round-22）·
导入账号 ⏳（网关 /api/accounts/import，待做）· 保活 ⏳（/api/checkin-keepalive，待做）

## 下一步

1. 导入账号页（从网关账号 JSON 导入 → /api/accounts/import）
2. 保活设置页（/api/checkin-keepalive）
3. **最终审查 + 推 CI**（用户要求全部完成后统一推）