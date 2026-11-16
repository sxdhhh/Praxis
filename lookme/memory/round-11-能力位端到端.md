# round-11 能力位端到端（G3 落地）

日期：2026-11-17

## 交付

**`/models` 拉回的能力位第一次真正参与选路。**链路：

1. `ModelCatalog.parse`（上上轮）把 `supports_images / supports_reasoning / supports_tool_call /
   max_input_tokens / max_output_tokens` 解进 `AiModelOption`（null = 没声明 ≠ 不支持）。
2. `BuiltinModelsCodec` 新增**详细形态**：`{provider: [{id, supports_images, …}]}`，
   字段名与 /models 响应逐字一致；**解码兼容旧纯字符串数组**（旧清单升级不炸）。
3. 拉取结果 `options` 一路传到写入（`GatewayProviderModelsFetch` → `onApplyModels` →
   `SettingsHost` 两处签名 → `mergeDetailed/encodeDetailed`）。
4. `GatewayProvider.builtinCaps` 查表 → `RouterSession.modelImageCapable` →
   `RoutePlanner` 过滤：带图请求只拦「明确 false」，**null/缺键放行**（#408 的教训）。
   新增 `SkipReason.ImageModelNotCapable` 与队列文案。

## 验证

`:core:gateway` **428 例全绿**（新增 7 例：codec 详细形态 4 + planner 过滤 3）。
CI run `43928254489` build ✓ verify ✓。

## 过程坑（这轮犯了三次低级错，都修在本地没进 CI）

1. JS 的 `splice(i, 0, arr)` 不展开数组 —— 插进去一个嵌套数组把文件写坏；
   必须 `splice(i, 0, ...arr)`。发现后立即 `git checkout --` 恢复重做。
2. 锚点要求两段文本在同一行（`BuiltinModelsCodec` 与 `.decode(…)` 分两行）——
   先 grep 实际行再写 pred。
3. 编码会按 id 排序去重（与旧 merge 同语义），测试按下标取值就踩雷 —— 按 id 取。

## 自定义家仍只存名字

`updateProviderModels(modelsJson)` 保持换行名清单不变 —— 升级它是独立一轮（要动表结构或格式），
本IDA轮明确不做，避免一次改太多。内置家（含 agent2api 网关）已全量受益。