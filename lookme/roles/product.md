# 产品体检（lead 完成，2026-11-16）

## 验收线 A：网关「内建家 → 拉清单 → 建账号 → 选模型 → 真发请求 → 报告有数据」
| 步骤 | 状态 | 证据 |
|---|---|---|
| 内建家 | ✅ 24 家直连 + 37 家插件家，全部在目录里 | `core/gateway/ProviderCatalog.kt` |
| 拉模型清单 | ✅ 机制在（`onFetchGatewayModels`），但**必须有账号**才能拉（凭证出口同一条） | `app/ui/PraxisApp.kt:1862` |
| 建账号 | ✅ 直填 Key / 网页登录两种，登录方式按家的事实给 | `GatewayLoginMethods.forProvider` |
| 选模型 | ⚠️ **修复待验证**：内置家清单此前进不了选择器/候选队列/测试三处，G1 已接线（`gatewayNativeModelsForRouting`），CI 绿但未真机 | `app/ui/PraxisApp.kt:358` |
| 真发请求 | ⚠️ 机制完整（三协议 + 换号），**从未对真实上游发过请求** | GATEWAY-PORTING-STATUS §十 |
| 报告有数据 | ⚠️ 报表页在，但 `gateway_request` 从未在真机写入过一条 | 同上 |

**结论：A 线卡在「零真机验证」。**下一次装 APK 后按 STATUS §十 清单过一遍即可定案。

## 验收线 B：生图「下模型 → 起引擎 → 出图」
| 步骤 | 状态 | 证据 |
|---|---|---|
| 下模型 | ✅ 下载器修完终态竞态；⚠️ 半成品判「已装」（G2）与并发取消语义已修待真机 | `DownloadTerminal` |
| 起引擎 | ⚠️ sd15cpu 组件齐；sd15npu `--patch` 缺（G4）；DiT 四类缺 `libdit_engine.so` 必然起不来 | `EngineLaunch` / 资产枚举 |
| 出图 | ⚠️ NDJSON→落盘→相册链路静态完整，零真机 | `ImageGenClient` |

**结论：B 线同样卡「零真机」。**DiT 是外部依赖（Hexagon SDK），不属于本仓库能解锁的项。

## 假控件 / 僵尸文案（单列）
- 路由页「粘性」开关：机制零接线（G6）—— **真开关无效果**，要么接线要么摘掉。
- 模型管理页「逐模型启停」：只影响选择器，不影响请求（G2）。
- feature/GatewayRoute.kt `PLAN_LINES` 等四处文案说「还没做」而其实已做（G12）。

## 建议下一步
1. 真机跑一轮 A 线验收（用你远端网关的 Key + 任意一家），把「从未验证」清掉。
2. 决策 G6 粘性：接线 or 摘开关。
