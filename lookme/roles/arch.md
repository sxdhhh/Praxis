# 架构/DI 体检（lead 完成，2026-11-16）

## 结论
1. 依赖方向未发现新增破坏：本轮新增 `ProviderCatalog` 在 core:gateway（纯 JVM，无 Android 依赖）✓；
   `:app` 的 `BuiltinProviders` 委托 core 的目录，方向仍是 app→core ✓。
2. `BuiltinProviders`（24 家的 baseUrl/quirks，含 Cline 伪装头）与 `ProviderCatalog`（61 家的 kind/login）
   是**两份表**，id 必须一致 —— 已有 `ProviderCatalogTest` 钉数量与唯一性，但**没有钉「两表 id 交集一致」**，
   建议补一条测试：`BuiltinProviders.ids() ⊆ ProviderCatalog.direct() 的 id`。
3. 已知双实例（ProotEnvPort 两份，报告 04-app G2）与死注册问题本轮未复核，维持原结论。
4. `GatewayLoginMethods.forProvider` 唯一生产调用点在 `GatewayAddAccountScreens.kt:140`（已带 loginKind）✓。

## 建议
1. 补「两表 id 一致性」测试（防漂移）。
2. `BuiltinQuirks` 可考虑并入 core（它是纯数据），`:app` 里只留 delegating object。
