# round-02 本地网关重启 + 网关 G1 接线

日期：2026-11-16

## 一、本地网关（Agent2API）的真实状态

- 之前那一份部署在 `C:\creat-agent\a2a-service`，由 nginx 挂在 `/a2a/`（面板）与 `/a2a/v1/`（API）。
- **源码里的 Rust 产物 `target/` 在之前清磁盘时被删了**，本机也没有 cargo / docker，
  所以 `C:\creat-agent\Agent2API` **无法就地重建**。
- 解法：拉官方发行包 `agent2api-server-windows-amd64.zip`（v3.4.2，13.6 MB），
  校验 sha256 一致（`b2d0d037…f7419`），解包到 `C:\creat-agent\a2a-service`。
- 运行事实（实测）：
  - 进程 `agent2api-server.exe`，监听 `0.0.0.0:3065`；`/health` = `{"status":"ok"}`
  - 面板 `http://127.0.0.1:3065/` 200（136 KB），`/login` 200
  - 配置目录 `C:\Users\sxd\.agent2api`，库 `agent2api.db`
  - 日志 `C:\creat-agent\a2a-service\server.log`
- **当前是空库**：无管理员、无账号、API Key 认证未启用 → `/v1/*` 处于 fail-closed，
  `/v1/models` 返回空数组。要真正转发，必须先注册管理员 → 建网关 Key → 加一个账号/provider。

### 踩到的坑

- `& .\agent2api-server.exe --help | Select-Object -First 40` 会把进程留在后台：
  管道被截断后进程没死，且**看起来**像「端口被占用查不到进程」。诊断时用
  `netstat -ano | Select-String ":3065"` 才看得到真实 PID。
- `Get-NetTCPConnection -LocalPort 3065` 在本机返回空（即使 3065 明明白白在 LISTENING）——
  **不要用它判断端口占用**，用 netstat。
- 正确的启动方式：后台任务 + `Tee-Object` 落日志，不要接 `Select-Object`。

## 二、另起的零依赖本地 stub（8787）

`C:\creat-agent\local-gateway\server.mjs`（Node，无依赖）：OpenAI Chat / Anthropic Messages /
Responses / Images 四种接口的最小实现，模型清单 4 个，SSE 带 usage 与 `[DONE]`。
用途：**没有真实 key 时也能把 Praxis 的网关链路走完**（拉清单 → 建账号 → 选模型 → 真请求 → 报表）。
启动：`node server.mjs`（`PORT`/`REQUIRE_KEY`/`LATENCY_MS` 可覆盖）。

## 三、网关 G1 接线（已改，待 CI 验）

`app/.../ui/PraxisApp.kt`：把「内置家清单」和「自定义家清单」收成**唯一来源**，四处共用：
- 新增 `gatewayBuiltinNativeModels`（内置家 → NativeModel）
- 新增 `gatewayNativeModelsForRouting`（suspend，provider 表 + 内置家）
- 模型页候选、候选队列改用后者；三处 `GatewayProvider` 构造点改用前者
- **模型测试实例此前完全没传 `builtinModels`** → 内置家「测试连接」必报
  「模型 X 没有对应任何上游」；已补上并加进 remember 键

静态检查：braces/parens/brackets 全 +0，literals OK。`:app` 本地跑不了，等 CI。
