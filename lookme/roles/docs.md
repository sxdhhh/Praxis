# 文档体检（lead 完成，2026-11-16）

## 需要更新的文档（只列改动点，未动手）
1. `docs/GATEWAY-PORTING-STATUS.md §五-1`：「只接了 2 家内置家」→ 改为「24 家直连 preset + 61 家全量目录（ProviderCatalog）；37 家插件家经网关使用」。
2. 同文件 §八-2「内置家不可用：任何内置家的账号都会被跳过」→ 已过时：内置家可拉清单（需账号），G1 修复后可直连选模型。
3. 同文件 §三 的「最近一次全绿 a683271」表：补 2e43173 / 670a583 两次绿记录与新增套件（DownloadTerminalTest / ProviderCatalogTest / GatewayAccountsPackTest / ModelCatalogTest 能力位 6 例）。
4. `docs/PORTING-GATEWAY.md §8.5`：补「整包 `agent2api-accounts.json` 才是网关真实导出/导入形状；`accounts/*.json` 保留为人读格式」。
5. `README.md 模块地图`：core:gateway 职责里补 ProviderCatalog 与「登录方式按事实生成」。
6. `lookme/index/READ-LOG.md`：补 07-provider-login-matrix 一行（38 家取证）。

## 僵尸 KDoc 现状
已知 4 处（feature/GatewayAccountsScreen.kt:112/:157、GatewayRoute.kt PLAN_LINES、GatewayRoutingScreen KDoc、GatewaySystemScreen KDoc）——本轮未逐行复核，标「待复核」。
