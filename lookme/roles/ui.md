# 前端/Compose 体检（lead 完成，2026-11-16）

1. 本轮 UI 改动遵守既有铁律：无新增 Dialog（未触常驻挂载问题）；无新增 Switch；列表沿用 LazyColumn + LocalTopBarInset/LocalScrollBottomLimit ✓。
2. `GatewayProvidersScreen` 插件家行：direct=false 时不画「拉取模型清单」（不摆假按钮）✓。
3. `GatewayAddAccountScreens` 登录方式来自目录（remember(providerId)）✓。
4. 待办：插件家行建议加「怎么用」一句（见 ux.md）；设置首页仍缺角色卡/插件入口两枚举项（05 报告 G1，未动）。
