# 目标（内置 Agent2API 网关）

> 更新于 2026-11-17。用户确认：路径 A（proot 容器跑网关）。

## 总目标

把 Praxis 改造为**内置 Agent2API 网关**的端侧 AI 客户端：

1. **内置网关**：在 Praxis 里跑 Agent2API，让 33 家 Agent 插件提供商的登录、签到、积分、保活、拉模型全部在 Praxis UI 里完成
2. **只保留插件提供商**：删掉 24 家 API preset 家（模型配置页本来就能填任意 API）
3. **H5 容器登录**：需要网页登录的提供商，用 Praxis 内置 H5 容器打开登录页
4. **AGPL 隔离**：网关作为独立进程运行，Praxis 保持 MIT

## 过程纪律

1. 每轮开工先读 `lookme/GOAL.md` → `lookme/STATE.md` → `lookme/memory/*` → 最近 `lookme/delivery/*`
2. 每轮收工写 `lookme/memory/round-NN` + `lookme/delivery/round-NN` + 更新 `lookme/STATE.md`
3. 不推 CI，全部完成后统一审查再推
4. 不动用户真机；token 只从环境取
5. 不复制上游代码（Agent2API 是 AGPL，Praxis 通过 localhost HTTP 调用独立进程保持 MIT）
