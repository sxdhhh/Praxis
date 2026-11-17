# round-21 GatewayProcess + 自启动 + 日志窗口

日期：2026-11-17

## 交付

1. **GatewayProcess.kt**（131 行）：网关进程管理——连接外部网关（健康检查 /health）、
   启动本地进程（预留）、日志环形缓冲（200 行）、存活监听。
2. **PraxisApp 自启动**：LaunchedEffect 连接外部网关 + 定期刷新日志到 UI。
3. **AppModule**：GatewayProcess 注册为 Koin 单例。
4. **GatewayRoute**：概览页加网关状态描述 + 日志窗口（最近 50 行）。
5. **DB 16→17 迁移**：gateway_account 加 requestLimitPerMinute 列（G4 数据面）。

## 33 家确认

ProviderCatalog 只留 agent2api 入口（API preset 已删）+ 33 家 Agent 插件家 + 4 家网页家 = 38。
GatewayConnection 通信层连接网关后，全部 37 家模型/登录/签到/积分经网关 API 可用。

## 待做（下一轮）

1. PraxisApp 的 gatewayLogs/gatewayStatus 传给 GatewayRoute 的接线
2. 日志窗口的自动滚动到底部
3. 网关二进制下载/内置到 APK
4. 33 家的登录流程通过 GatewayConnection 接通
5. 签到/积分/导入的 Praxis UI 对接网关 API