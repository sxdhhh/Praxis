# 后端/领域层体检（lead 完成，2026-11-16）

1. `ProviderCatalog` 与 `BuiltinProviders`：两份表分工清晰（kind/login vs baseUrl/quirks），但缺「id 交集一致」测试（见 arch.md 建议 1）。
2. `GatewayLoginMethods.forProvider(label, baseUrl, loginKind)`：全仓唯一生产调用点已带 loginKind ✓；测试 12 例覆盖各 kind 文案 ✓。
3. `GatewayAccountsPack` 往返：解码→编码→再解码字段不丢（测试钉住）；`resolvedBaseUrl` endpoint 优先 ✓；`enabled=false` 模型在导入侧过滤 ✓。
4. `ModelCatalog` 能力位：null 语义已钉住（「没声明 ≠ 不支持」）；字符串布尔/数字都认；`max_tokens` 不会被误当上下文。
5. 死代码提醒（未新增）：`CandidateChain.ofEntries`、`ConversationKey`、`FailurePolicy.decide`、`RouteState.markSink` 仍生产零调用（G2/G6/G7/G13）。
