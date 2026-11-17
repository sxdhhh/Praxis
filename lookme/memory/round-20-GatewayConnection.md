# round-20 GatewayConnection 通信层

日期：2026-11-17

## 交付

`core/gateway/adapters/GatewayConnection.kt`（213 行）：

Praxis ↔ Agent2API 网关的通信层。支持：
- 拉模型清单（含能力位、所属提供商从 owned_by 字段取）
- 获取登录方式列表（每家不同）
- 启动登录流程（设备码/网页登录/验证码）
- 轮询登录结果
- 签到 / 余额积分
- 健康检查

认证：网关 Key 放 `Authorization: Bearer` 头。

## 用途

这是「Praxis 内置 Agent2API 网关」架构的基础通信层。
Praxis 不重复网关的逻辑 —— 它通过 HTTP 调用网关的 REST API。

## 33 家确认

ProviderCatalog 里的 Agent 插件提供商 33 家 + 网页提供商 4 家 = 37 家。
全部经网关通信，GatewayConnection 不关心数量 —— 网关自己处理。