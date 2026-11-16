# round-07 生图 G2/G11 三次红灯的完整复盘

日期：2026-11-17

## 交付

- **G2**：下载临时物（.part / 解压目录 / zip）全部隔离到 `models/.incoming/<id>/`；
  `isInstalled` 单 zip 分支排除 `*.part` 与 `*_extract`（兼容旧布局残留）；
  清理连 `.incoming/<id>` 本体一起删。新增 5 例（布局隔离/清理清单/半成品×2/真成品）。
- **G11**：`EngineProcess` 增加 `onExit` 回调 + 存活看护线程（崩溃自动清引用并通知 UI）；
  `stop()` 立即返回、2s 宽限后 `destroyForcibly` 交给守护线程（不阻塞调用线程 → 不 ANR）；
  `:app` 用 `LaunchedEffect` 把 `engineRunning` 接上 onExit —— 修掉「崩了但按钮说还在跑」。
- CI 终绿：run `43910169417`（febaaf8）build ✓ verify ✓。

## 三次红灯的根因链（都写在提交里了）

1. `10d2505` 红：**cleanStale 在 mkdirs 之后运行**，而清理清单现在包含 `.incoming/<id>` 本体
   → 刚建的目录被删 → 每个下载都写进不存在的目录 → ModelDownloaderTest 全军覆没。
   教训：**「清理清单」与「建目录」必须一个顺序，改布局时要把这两处当一对看。**
2. `6d48bad` 修了顺序，但 zip 四例仍红：`.part` 成功后仍被改名进成品目录，而解压读的是
   `.incoming` 里的压缩包 —— 文件不在。教训：**「改名落点」也是布局的一部分**。
3. `8f55f04` 给 `downloadOne` 加了 `finalDir` 参数，但**只改了签名没改调用点** → CI 直接编译失败。
   教训：**改签名必须当场 grep 全部调用点**（历史已犯过：`engineQnnInstalled` 参数块）。
4. `febaaf8` 绿。

## 方法论沉淀（进 memory 供以后查）

- CI 红时先抓 `verify` job 的 `TEST-*.xml` 断言原文（workflow 已有转储），不要猜。
- 布局类改动要检查三件事：谁写入、谁改名、谁清理 —— 三处必须同源（都从 plan 派生，不许手写路径）。