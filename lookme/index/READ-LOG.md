# 通读台账

| 范围 | 文件数 | 状态 | 产出 | 完成时间 |
|---|---|---|---|---|
| 全库清单 + 声明级索引 | 1251（文本 961） | ✅ 完成 | `index/INDEX-files.md` (168 KB) | round-01 |
| `core:gateway` + `feature:gateway` | 97 + 11 | ✅ 完成 | `index/read/01-gateway.md` | — |
| `core:imagegen` + app 生图 + 引擎资产 | 40 + ~5 | ✅ 完成 | `index/read/02-imagegen.md` | — |
| `core:ai/model/data/plugin` | 47+23+27+36 | ✅ 完成 | `index/read/03-ai-data-model-plugin.md` | — |
| `app` 全量 | ~124 文本 | ✅ 完成 | `index/read/04-app.md` | — |
| `feature:*` / `ui:*` / core 其余 | ~330 | ✅ 完成 | `index/read/05-feature-ui-core-misc.md` | — |
| CI 取证 | — | ✅ 完成 | `index/read/06-ci-forensics.md` | — |
| `docs/*.md`（除 memories） | 21 | ⏳ 待读 | — | — |
| `docs/memories/*`（2161 条历史决策） | 14 | ⏳ 按需检索（不全读） | — | — |
| `third_party` / `tools` / `scripts` / `build-plugins` | ~220 | ⏳ 待读（多为二进制/脚本） | — | — |

## 通读完成度（round-01 收工时）

| 范围 | 文件数 | 覆盖 | 报告 |
|---|---|---|---|
| `core:gateway` + `feature:gateway` | 110 | 逐文件 100% | 01-gateway.md（535 行 / 67 KB） |
| `core:imagegen` + app 生图 + 资产 | 60 | 逐文件 100% | 02-imagegen.md（494 行 / 53 KB） |
| `core:ai` / `model` / `data` / `plugin` | 133 | 逐文件 100% | 03-ai-data-model-plugin.md（748 行 / 92 KB） |
| `app` | 124 文本 | 逐文件 100% | 04-app.md（578 行 / 58 KB） |
| `feature:*` / `ui:*` / core 其余 | 407 | 逐文件 100%（`ui:provision` 130 个按「搬运代码」只给清单+入口） | 05-feature-ui-core-misc.md（756 行 / 99 KB） |
| CI 取证 | — | — | 06-ci-forensics.md |

合计覆盖 **834 个源码文件**（不含 .so/aar/日志等二进制与构建产物）。
仍未逐行读：`docs/*.md` 21 份（部分已读）、`docs/memories` 14 份（7.8 MB 历史决策，按需检索）、
`third_party`/`tools`/`scripts`/`build-plugins`。
