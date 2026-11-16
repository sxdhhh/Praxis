# CI/构建体检（lead 完成，2026-11-16）

## 现状
- run 43893590345（2e43173）：build ✓ verify ✓（含 :app 逐类 + Lint）。
- run 43901055219（670a583）：进行中（本文件写作时）。
- 失败诊断：上一轮的 JUnit XML 断言转储已覆盖 :app 与 :feature:chat 两个 per-class 步骤 ✓。

## 观察（未改动）
1. 纯文档提交不触发 build（路径过滤）✓；lookme/** 不在过滤列表里 → **lookme 提交会触发 CI**，可考虑加入忽略。
2. APK 体积构成：qnnlibs 20 件 156.1MB + jniLibs 11.4MB 全部打进包（87.8MB zip / 153.3MB 解压后 apk 实测 153MB 是旧数据，需重测）。
   可选优化：qnnlibs 改为按需下载（首次启动拉取），包体可减 ~150MB —— 属产品决策，未动。
3. 汇总行不含 :app/:feature:chat（既有管线顺序）—— 看这两步各自的 BUILD SUCCESSFUL 与 ::error:: 行。
