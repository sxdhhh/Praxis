# 测试体检（lead 完成，2026-11-16）

## 本轮新增且已被 CI 跑到
- `DownloadTerminalTest`（core:imagegen，8 例）—— CI run 43893590345 全绿 ✓
- `ProviderCatalogTest`（core:gateway，9 例）、`GatewayAccountsPackTest`（core:gateway，7 例）—— JVM modules 步骤显式列模块，:core:gateway 在清单里 ✓
- `ModelCatalogTest` 能力位 6 例 —— :core:ai 在清单里 ✓

## 缺口
1. `feature/gateway` 仍无测试目录（11 个页面 0 测试）。
2. `:app` per-class 白名单：本轮没往 :app 加新测试类，无需登记；下次加 :app 测试类时**必须**同步 build.yml。
3. 最该补的 5 个：
   a. 换号循环端到端（401→换号成功 / 已吐字不换号）—— 目前只有 :app 字符串护栏。
   b. 内置家+自定义家合并清单（gatewayNativeModelsForRouting）行为。
   c. 导入→导出往返（用 95 账号形状的合成样本，不含真值）。
   d. isInstalled 对 .part/_extract 的排除（G2 修复的回归测试）。
   e. EngineProcess 生命周期（G11 修复后）。
