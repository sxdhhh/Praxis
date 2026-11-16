# round-15 G6（imagegen）QNN 解压进度 + 旧 stamp 清理

日期：2026-11-17

## 交付（80060f0，CI run 43944760633 绿）

1. `QnnRuntimeInstaller.install(onProgress)`：逐文件回调 (done, total)；
   启动路径把「正在解压 QNN 运行时 k/20 …」写进启动消息 —— 156 MB 几十秒不再像卡死。
2. 新 stamp 解压**成功后**清兄弟目录（旧 156 MB）；失败时保留旧的以便回滚。

## imagegen 缺口账本（对照 round-11 的遗留）

- G2 半成品判已装 ✅ / G4 尺寸锁 512 ✅ / G11 引擎看护 ✅ / G19 删除 ✅ / G12 acknowledge ✅ / G6 解压进度+清理 ✅
- 未做：能力位落自定义家 modelsJson（独立一轮）；G20「第几张」进度（低价值）；G13/G14 等小项。

## 下一步

真机验收（delivery/round-12 清单）是唯一能让两条验收线闭环的事 —— 代码侧能自证的都做完了。