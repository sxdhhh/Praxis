# 端侧生图体检（lead 完成，2026-11-16）

## 已修（本轮）
- 下载终态竞态：`DownloadTerminal.pick`（用户取消优先，否则真失败优先）+ 8 例单测；
  `:app` 用例断言带 message。CI 已绿（run 43893590345）。

## 剩余 P0/P1 与改法（引 lookme/index/read/02-imagegen.md 的证据）
1. **G2 半成品被判已装**：`ImageDownloadPlan.kt:116` 把 `<id>_extract` 放进 `models/<id>/`，
   而 `ImageModelCatalog.kt:219-221` 单 zip 判据 = 目录非空。
   改法：临时物统一 `filesDir/models/.incoming/<id>/`；`isInstalled` 排除含 `.part`/`_extract` 的目录。两处一起改。
2. **G4 `--patch` 不传**：`PraxisApp.kt` specFor 未给 patchFile，资产里也无 .patch。
   短期：UI/工具层把 sd15npu 可用尺寸锁 512 并说明；中期：补丁随模型包下发。
3. **G11 引擎无看护**：`EngineProcess.stop()` 无宽限/无 waitFor。改法：start 起监听线程更新存活 StateFlow；stop 2s 宽限再 destroyForcibly。
4. **DiT 四类（zimage/klein/qwen21/krea2turbo）**：缺 `libdit_engine.so`（jniLibs 实测 5 个文件里没有），需 Hexagon SDK 自编 —— 仓库内不可解锁，UI 已如实拒绝。
