# round-21 用户新需求 + 编译修复

日期：2026-11-17

## 用户说

> 应该打开软件自启动的，然后添加日志窗口就和环境页的那个日志窗口一样

## 两条新需求

1. **网关软件自启动**：App 启动时自动启动内置网关（不需要用户手动点）
2. **日志窗口**：给网关加一个日志窗口，样式和环境页的日志窗口一样

## 编译修复

- core:gateway 加 OkHttp 依赖（GatewayConnection / ProviderHttpClient 需要）
- GatewayConnection.kt KDoc 里 /v1/ 的 /* 又触发嵌套注释 → 改成 〔v1〕
- GatewayImporter.kt 错误消息转义简化
- ProviderCatalog 删掉 24 家 API preset（只留 agent2api 入口）
- ProviderCatalogTest 计数 62 → 38（1 agent2api + 33 Agent 插件 + 4 网页）