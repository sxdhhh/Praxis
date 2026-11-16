# DBA 体检（lead 完成，2026-11-16，基于已有报告未重读全量）

维持 03/04 号报告结论并补充本轮：
1. 迁移链缺 `MIGRATION_1_2`（v1 设备升级即崩）—— 高优先，修复需真机验证。
2. `gateway_daily` 死表、`gateway_model` 无写入方 —— 维持原结论。
3. 本轮未新增表/键（能力位仍在内存对象 `AiModelOption`，未落库）—— 下一步若做「模型能力位快照」，建议落 `gateway_model` 表（它本就有列），而不是新键。
4. `gateway_builtin_models`（JSON 聚合键）继续作为内置家清单存储；扩展成「名字+能力位」时要兼容旧数组形状（BuiltinModelsCodec 已有 merge 语义）。
