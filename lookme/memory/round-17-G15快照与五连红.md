# round-17 G15 快照时间（五连红的完整教训）

日期：2026-11-17

## 交付（终绿 91c369b + c0d3df8，run 43956677524）

- 新 DataStore 键 `gateway_builtin_models_fetched_at`（provider → epoch 毫秒）。
- 拉清单成功 → **合并写回**（只动这一家）；行模型带 `fetchedAt`；
  内置家行描述显示「清单 3 小时前」(`GatewayModelCatalogCache.ageText`)。

## 五连红：全部是「锚点插入」型错误，没有一个逻辑错误

1. ba0161e：ageText 少传 nowMillis；SettingsRepository 的 setter 是两行语句，
   锚点插在第一行后把它腰斩。
2. 17317d3：修腰斩时替换范围差一行，留下重复声明。
3. c981828：删重复声明。
4. 91c369b：声明块 `idx+10` 定位落进了**下一段 KDoc 的中间**。
5. c0d3df8：`put(String, Long)` 需要 `kotlinx.serialization.json.put` 显式 import，
   改用显式 `JsonPrimitive` 绕过。

## 纪律升级（追加到 round-14 的三条之后）

4. **锚点之后 N 行的定位一律 findIndex 真实锚点**，不要用 `(idx + 偏移)` 数数 ——
   KDoc/空行的行数永远跟你数的不一样。
5. **两行语句（`fun x() =\n    put(...)`）不是合法锚点**；锚点必须是完整语义单元。
6. 往 JsonObject 里放非字符串值：要么 import `kotlinx.serialization.json.put` 扩展，
   要么显式 `JsonPrimitive`。

## 结论

这五连红没产生任何逻辑风险（全是编译期被 CI 拦下），但消耗了 5 轮 CI ≈ 70 分钟。
**结构级改动整文件重写**（round-14 教训）+ **锚点真实性**（本条）—— 两条合起来用。