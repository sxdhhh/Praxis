# round-07 交付

| 项 | 内容 | 验证 |
|---|---|---|
| G2 | 临时物隔离 + isInstalled 排除半成品 + 清理含本体 | :core:imagegen 202 例全绿；:app 用例 CI 绿 |
| G11 | onExit + 存活看护 + 宽限停止 | CI（febaaf8）build ✓ verify ✓ |
| 提交 | 8d44e8c / c5eda79 / 10d2505 / 6d48bad / 8f55f04 / febaaf8（后两个是红灯修复） | run 43910169417 |

## 遗留
- G4（--patch 不传 → sd15npu 尺寸静默回落）：短期 UI 锁 512，未做。
- 「经网关」账号入口：未做。
- G6 粘性接线 / 能力位落库（gateway_model）：未做。
- 真机验收（A 线 + B 线）：等用户装 APK。