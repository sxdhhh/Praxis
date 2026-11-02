# 模拟器能测什么、不能测什么（2026-11-03 实证）

## 事实

APK 只打 **arm64-v8a** 一个 ABI：

```
lib/arm64-v8a/libandroid-shmem.so
lib/arm64-v8a/libandroidx.graphics.path.so
lib/arm64-v8a/libdatastore_shared_counter.so
lib/arm64-v8a/libproot-loader.so
lib/arm64-v8a/libproot.so
lib/arm64-v8a/libpty.so
lib/arm64-v8a/libtalloc.so
```

模拟器 `emulator-5554` 是 **x86_64**，装包直接失败：

```
INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113
```

## 结论：模拟器上测不了的

任何依赖原生 `.so` 的功能，一处都测不了：

  - 终端 PTY（`libpty.so`）→ 终端页本身
  - proot 容器（`libproot.so` / `libproot-loader.so`）→ Linux 环境
  - **P1 终端复制粘贴**（建在 PTY 之上）
  - **P7 终端可用性**
  - 终端相关的帧耗时判据

## 模拟器上能测的

纯 Compose 的 UI 与逻辑：

  - P2 侧边栏压暗（像素采样）
  - P3 侧边栏多选（外观与数据库行为）
  - P5 菜单样式
  - P6 圆角与悬浮控件
  - 聊天页、设置页等

## 但注意：模拟器装的是旧包

模拟器上当前是 2026-11-01 22:31 的构建，**不含 P2/P3/P5/P6 的任何改动**。
要测这些，得先有新 APK —— 而新 APK 也只能从 CI 出（本地无完整构建环境）。

## 这条限制以前没被明确说过

之前几轮我说过「模拟器可以验 P2/P3」，但实际动手时才发现装不上。
原因是当时没查 ABI。现在写清楚，避免再绕一次。
