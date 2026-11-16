# 安全体检（lead 完成，2026-11-16）

## 已确认
1. **导出「仅配置」粒度清空凭证**：`GatewayExportPlanner.packFile` 写 null（不是 `***`），
   `GatewayAccountsPackTest` 钉住「不能出现真凭证也不能出现占位符」✓。
2. **真实账号文件未入库**：`git status` 不含 `agent2api-accounts (1) (2).json`（它在 C:\creat-agent，仓库外）；
   仓库内也无任何真实 token（测试用例全是 sk-fake/rt-fake 假值）✓。
3. 建议在 `.gitignore` 增加 `agent2api-accounts*.json` 与 `*-secrets.json`，防以后手滑 —— 未改（属 .gitignore 变更，随下一批提交）。
4. Keystore「加密失败整次失败」的既有纪律未发现被破坏（本轮未新增存储路径）。
5. **未复核**（维持历史结论，待专项）：API Key 输入框明文（G14）、debugRawTraffic 的日志内容。
