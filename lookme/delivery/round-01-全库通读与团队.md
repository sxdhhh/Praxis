# round-01 交付物

## 1. 记忆库（新增，进仓库）

| 文件 | 内容 |
|---|---|
| `lookme/GOAL.md` | 长期目标 + 6 条铁律 |
| `lookme/STATE.md` | 每轮覆盖的当前指针 |
| `lookme/README-工作循环.md` | 「读记忆 → 工作 → 交付」的约定与智能体分工 |
| `lookme/index/INDEX-files.md` | 961 个文本文件的声明级索引（168 KB） |
| `lookme/index/READ-LOG.md` | 通读台账 |
| `lookme/index/read/01..06-*.md` | 6 份逐文件通读报告（合计 ≈360 KB） |
| `lookme/memory/round-00,01` + `delivery/round-01` | 轮次记忆与交付 |

## 2. 六份通读报告的结论摘要

| 报告 | 文件数 | 最严重的发现 |
|---|---|---|
| 01-gateway | 110（100%） | **G1 内置家选不出模型**：真实请求并了 `builtinModels`，但模型选择器/候选队列/模型测试三处没并 |
| 02-imagegen | 60（100%） | **G1 下载器失败/取消竞态**（=CI flaky 根因）；G2 半成品被判「已装」；G4 `--patch` 从不传 |
| 03-ai-data-plugin | 133（100%） | **G13 升级丢 apiKey**（`ensureProfiles` 不搬 `model_api_key`）；`CharacterToolFilter` 零调用；迁移缺 1→2 |
| 04-app | 124 文本（100%） | `PraxisApp.kt:790` 测试实例未传 `builtinModels`；溢出存储三路退化；ProotEnvPort 两份实例 |
| 05-feature-ui | 407（100%） | 设置首页只画 16/18 项（角色卡/插件入口不可达）；15 个 Route 缺 `@Serializable`；工具回路无轮数上限 |
| 06-ci-forensics | — | **上次红灯是 flaky**；`1e00132` 转储对无 message 断言无效 |

## 3. 代码改动（本轮已落盘，待随批提交）

- 新增 `core/imagegen/.../DownloadResult.kt`：把 `DownloadResult` 从 `:app` 下沉到 `core`，
  并新增 **`DownloadTerminal.pick`** —— 把「每个文件的终态」收敛成「整次下载的终态」。
- `app/.../ModelDownloader.kt`：`awaitAll().firstOrNull { it != null }` → `DownloadTerminal.pick(results, userCancelled = shouldStop())`。
- `app/.../ImageModelDownloadManager.kt`、`ModelDownloaderTest.kt`：补 import。
- `ModelDownloaderTest.kt`：三条断言补 `"实际=" + result`（下次红灯可诊断）+ 新增对称用例
  「第一个文件就失败时同样是 Failed」。
- 新增 `core/imagegen/src/test/.../DownloadTerminalTest.kt`（8 例）。

## 4. 验证证据（本地）

```
.gradlew.bat :core:imagegen:test --no-daemon --console=plain --configure-on-demand
BUILD SUCCESSFUL in 3m 33s
DownloadTerminalTest 8 项全 PASSED（同伴被中止时仍然报真失败而不是取消 / 用户点取消时取消优先 / …）
```

静态检查：`check_kt_files.py` / `check_kt_literals.py` 对 5 个改动文件全部 OK（braces=+0 parens=+0 brackets=+0）。

## 5. 遗留

- `:app` 侧改动本地无法跑（Windows 配置期失败），只能靠 CI 验。
- 6 份报告里提到的其余缺口均未动（G1 网关接线是下一个）。
