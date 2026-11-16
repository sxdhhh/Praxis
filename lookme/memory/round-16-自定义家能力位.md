# round-16 自定义家能力位（G3 真收尾）

日期：2026-11-17

## 交付（cee8a8b + 8192476，CI run 43949361369 绿）

- `BuiltinModelsCodec` 新增 `encodeCapsList` / `parseCapsList`：providers 表的 modelsJson
  两种历史形状（行清单 / JSON 数组）统一解析 —— **顺带修掉一个潜伏错配**：
  `updateProviderModels` 写 JSON 数组而 `nativeModels` 按行切，JSON 形状会被当成一个
  整名（模型清单拉完直接发不出去）。
- `updateProviderModelsWithCaps` + `customCaps()`：拉清单带能力位落库、选路查表合并
  内置家与自定义家两路 —— **带图过滤现在覆盖全部家**。
- `BuiltinModelsCodec.from(AiModelOption)` 工厂（core:gateway → core:ai 方向既有）。

## 验证

`:core:gateway` **431 例全绿**（+3 形状解析用例）。CI run 43949361369 build ✓ verify ✓。

## 过程坑

- `companion object` 在 **standalone object 里非法**（Modifier not applicable）—— 改普通函数。
- 锚点串手写与实际行不一致（`provider, id = it` vs `provider.id, id = it`）→ findIndex -1。
  **先打印实际行再写锚点**，这条现在是第三次记了。
- 一次 for 循环越界读 undefined（`st[nmEnd]`）→ 直接抛错，反而防止了坏写入 —— 崩得好。