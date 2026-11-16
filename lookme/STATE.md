# 当前状态指针

- 轮次：**round-09（G4 已落地并 CI 绿；下一步「经网关」入口 → G6 粘性 → 能力位落库）**
- CI 基线：commit `1e00132` 的 run `43875229354` **全绿**（build ✓ / verify ✓ / 9 个 core:plugin 债照旧）
- 工作树未提交：`DownloadResult.kt`（新）、`DownloadTerminalTest.kt`（新）、`ModelDownloader.kt`、
  `ImageModelDownloadManager.kt`、`ModelDownloaderTest.kt`、`lookme/`（全部新增）
- 本地已验证：`:core:imagegen:test` BUILD SUCCESSFUL（新增 8 例全过）
- 已读范围：见 `index/READ-LOG.md`（六个模块 100% 逐文件）
- 本地网关：`C:\creat-agent\a2a-service\agent2api-server.exe` 跑在 3065（面板 http://127.0.0.1:3065/）；
  空库，需注册管理员 + 建 Key + 加 provider 才能转发。零依赖 stub 在 8787（`local-gateway/server.mjs`）。
- 下一步优先级：
  1. 网关 G9 导入侧（家无地址 → 账号不落库或点名）+ 导出补 `customProviders`
  2. 生图 G2（临时物移出模型目录 + `isInstalled` 排除 `.part`/`_extract`）与 G4（sd15npu 尺寸锁 512）
  3. 决策项：G5/G6/G13（续期/粘性/垫底）接线还是摘掉 UI
  4. 攒够提交后一次推送并核对 CI