# round-11 交付

- **G3（带图能力过滤）落地**：能力位从 `/models` → 存储（内置家详细形态，兼容旧形状）→
  `GatewayProvider.builtinCaps` → `RouterSession/RoutePlanner.modelImageCapable` →
  带图请求跳过「明确不支持」的模型（新 `SkipReason.ImageModelNotCapable` + 队列文案）。
- 提交 `7b35ee4`；CI run `43928254489` build ✓ verify ✓；core 428 例全绿（+7）。
- 明确不做：自定义家 modelsJson 的能力位（独立一轮）。

## 遗留
1. G5 续期 / G13 垫底决策（接线或摘除）。
2. 真机验收：agent2api 家走 A 线六步（拉清单现在会带回能力位，模型页可直接看到）。
3. imagegen G6/G12/G19（解压进度 / 下载终态 acknowledge / 模型库删除）。