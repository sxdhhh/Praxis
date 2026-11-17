# 当前状态指针

- 轮次：**round-29（六项功能全部落地；下一步：最终审查 → 推 CI → 修红）**
- 新方向：内置 Agent2API 网关，33 家 Agent 插件提供商全功能可用
- 已提交：GatewayConnection 通信层 / ProviderCatalog 清理 / 登录恢复 / 上限编辑 / OkHttp 依赖
- 用户新需求：
  1. 网关打开软件自启动
  2. 添加日志窗口（和环境页的一样）
- core:gateway 测试 433 例全绿（本轮）
- 未推（按用户指示：全部完成后统一审查再推）
- 下一步：
  1. GatewayProcess（启动/停止/日志捕获）
  2. PraxisApp 自动启动网关
  3. 网关设置页加日志窗口
  4. ProviderCatalog 的 33 家接上网关 API
