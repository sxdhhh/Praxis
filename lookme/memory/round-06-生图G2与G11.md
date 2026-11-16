# round-06 生图 G2 + G11（solo）

日期：2026-11-17

## G2 半成品被判「已安装」

- 布局：`.part` / 解压临时目录 / zip 全部移到 `models/.incoming/<id>/`（`ImageDownloadPlan.incomingDir`），
  成品目录里只会出现「真的下完并改名过的文件」。
- 判据：`ImageModelCatalog.isInstalled` 单 zip 分支在「目录非空」之外**排除** `*.part` 与 `*_extract`
  （兼容旧版布局残留）。
- 清理：`DownloadCleanup.staleDirs` 连 `.incoming/<id>` 本体一起删。
- 新增/更新 5 例：布局隔离、清理清单、part 不算已装、解压目录不算已装、真成品仍判已装。

## G11 引擎进程无看护

- `EngineProcess.stop()`：立即返回（不阻塞调用线程 → 不 ANR），宽限 2s 后 `destroyForcibly`
  交给守护线程（文档 §8.6 的 2s 收尾）。
- 新增 `onExit` 回调 + `praxis-engine-watch` 线程：引擎自己退出时清 `process` 引用并通知 UI，
  `:app` 用 `LaunchedEffect` 把 `engineRunning` 翻回 false —— 修掉「崩了但按钮说还在跑，没法重启」。

## 验证

`:core:imagegen:test` **202 例全绿**。`:app` 侧（ModelDownloader 新布局 / EngineProcess / PraxisApp onExit）等 CI。

## 教训

- 反引号函数名里不能带点（`临时物全部隔离到 .incoming…` 直接编译失败：Name contains illegal characters）。
- 读长文件时打印循环越界会把 `undefined` 当成文件内容 —— 那是脚本的 bug，不是文件坏了，别慌。