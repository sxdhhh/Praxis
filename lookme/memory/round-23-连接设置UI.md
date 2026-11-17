# round-23 网关连接设置 UI 完整接线

日期：2026-11-17

## 交付

1. **GatewayConnectionSettings.kt**（core:gateway）：连接设置编解码 + 地址校验
   - 拦 127.0.0.1/localhost（指手机自己，真机上最常见的错）
   - 要求 http(s) 协议头
   - 7 例测试全绿
2. **GatewayRoute 概览页**「网关连接」卡片：地址 + Key 输入 + 校验提示 + 保存
3. **全链路接线**：PraxisApp → SettingsHost(两层) → GatewayRoute
4. **网关状态与日志窗口**已显示在概览页

## 验证

core:gateway **440 例全绿**（+7）。

## 待办

- 真机验证：手机上填 PC 的局域网地址（如 http://192.168.1.80:3065）应能连上
- 管理员凭据输入（面板登录用，管理 API 需要）
- 33 家登录 UI