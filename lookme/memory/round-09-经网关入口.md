# round-09 「经网关」入口落地

日期：2026-11-17

## 交付

目录与内置表各加一家 `agent2api`（Agent2API 网关）：
- **形态**：Api 直连 + ApiKey。地址用户自填（网关的 `/v1`，本机 `http://<ip>:3065/v1` 或经 nginx
  `/a2a/v1`）；Key 用面板里建的**网关密钥**。
- **意义**：这是 38 家插件家（workbuddy / trae / raccoon / qoder…）在 Praxis 里可用的唯一现实路径 ——
  客户端伪装与网页会话都在网关侧，Praxis 不重复实现。一条账号 → `/v1/models` 全量模型可选。
- `BuiltinProviders` 表行带完整说明注释；`ProviderCatalogTest` 加「经网关入口在目录里且只要 Key」，
  数量断言 24→25 / 61→62。

## 插曲（又一次自伤，记录在案）

第一次尝试时那行 `anchor + join + === anchor ? "" : ""` 三元优先级写岔，
实际执行成了 `replace(anchor, "")` —— 把 `sensenova` 从**工作树**删了还写盘。
HEAD 里是好的，`git checkout --` 恢复后按行插入完成。教训：
**任何“聪明”的单行字符串手术都要先过脑子；replace 的第二个参数永远别用三元拼出来。**

## 验证

`:core:gateway:test` **416 例全绿**。`:app` 侧（BuiltinProviders 表行）等 CI。