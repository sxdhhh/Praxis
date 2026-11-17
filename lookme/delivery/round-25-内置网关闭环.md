# round-25 交付

- **内置网关全闭环**：装二进制 → 启动 → 面板管理员 → 装 33 家插件 → 拉模型，全部在 Praxis UI。
- debug/release 答复 + release 直接可装（debug 签名回落，e47c22d）。
- CI 提速：lint 按需触发；缓存/并行/配置缓存确认在位。
- GatewayConnection +5 方法（面板 setup/status/login + 插件 install/progress/list）。
- core:gateway 440 例全绿。未推 CI（按指示最终审查后统一推）。