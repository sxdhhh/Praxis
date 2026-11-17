# 方向确认：把 Agent2API 网关能力搬进 Praxis

日期：2026-11-17

## 用户说的原话

> 直接内置一个网关然后用内置我们软件的 node 启动服务不就行了，然后用的我们软件的 UI
> 只在必要的时候使用浏览器 h5 比如登录跳转，要么就实现客户端伪装，
> 然后内置那 21 家直接删了吧反正模型那里本来都可以填 api
>
> 我要的是能登录正常在我们软件登录的啊，然后浏览器用内置的 h5 容器，
> 要能做到 agent2 的所有功能比如签到，拉取积分，导入账号，保活，拉取模型等等

## 我之前的理解（全错了）

我以为「经网关」=「让用户去网关面板操作，Praxis 只是消费端」。
实际上用户要的是：**Praxis 里面就是 Agent2API**——登录、签到、积分、保活、拉模型，
全在 Praxis 的 UI 里完成，不需要用户打开另一个面板。

## 可行路径（按工程量排）

### 路径 A：Praxis 里用 proot 容器跑 Agent2API 网关

- Praxis 已有 proot + Ubuntu rootfs（core:linux，234 处引用）
- Agent2API 有官方 linux-arm64 release（13.7 MB）
- Android proot Ubuntu 24.04 的 glibc 2.39 ≥ Agent2API 构建时的 glibc 2.35 ✓
- AGPL-3.0：Praxis 是 MIT，**分开运行**的 AGPL 程序不需要感染 MIT 主体
  （FSF 的立场：通过 pipe/socket 调用独立进程不算衍生作品）
- **做法**：Praxis 启动时（或用户手动）在容器里启动 Agent2API →
  Praxis 通过 localhost:3065 通信 → 所有 Agent 插件家的登录/签到/积分/模型全部可用
- 工程量：中等。主要是「容器内启动网关」的 UX + Praxis 与网关的 API 对接

### 路径 B：把 Agent2API 的插件适配器用 Kotlin 重写

- 33 家每家的登录流/客户端伪装/签到逻辑都要自己写
- 工程量巨大（每家几百行 Rust），而且要跟进上游变更
- **不推荐**——等于维护一个第二实现

### 路径 C：把 Agent2API 的 Rust 二进制交叉编译到 Android（不用 proot）

- Rust 交叉编译到 aarch64-linux-android 需要 NDK
- 网关用了 tokio/hyper 等 async 运行时，bionic libc 兼容性需要验证
- AGPL：直接嵌入 APK = Praxis 也要 AGPL
- 工程量：高（交叉编译 + 许可转换 + 适配）

## 我建议的路径

**路径 A**（proot 容器跑网关）——工程量最小、AGPL 风险最低、功能最全。

### 分阶段

**Phase 1（立刻）**：修复导入 + 恢复 API 家的 H5 登录 + 清理 UI
**Phase 2**：Praxis 加「启动网关」按钮 → 容器内启动 Agent2API →
  Praxis 自动连接 localhost:3065 → Agent 插件家全部可用
**Phase 3**：Praxis UI 直接消费网关的登录/签到/积分 API（不再需要用户打开网关面板）
**Phase 4**：内置网关二进制随 APK 分发（不用 proot，直接跑）

## 同时要做的：删掉 24 家 API preset

用户说得对——模型配置页本来就能填 API 地址和 Key，24 家 preset 是冗余的。
删掉后 ProviderCatalog 只保留 Agent 插件家 + agent2api 网关入口。

## AGPL 结论

Agent2API 网关 = AGPL-3.0。Praxis = MIT。
**方案 A（proot 容器独立进程）**：Praxis 通过 localhost HTTP 调用独立的 AGPL 程序，
按 FSF 的解释这不算衍生作品（GPL FAQ: "Mere aggregation"），Praxis 可以保持 MIT。
**方案 C（嵌入 APK）**：Praxis 必须转 AGPL-3.0。