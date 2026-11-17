# round-24 内置网关（跑在手机容器里，不连电脑）

日期：2026-11-17

## 用户要求

> 我认为应该内置或者移植网关而不是连接电脑

## 关键技术验证（本轮实测）

1. **官方 linux-arm64 产物是静态链接**：ELF e_machine=183（aarch64），
   无 INTERP 段（无动态加载器）。→ 不依赖 rootfs 的 glibc，Ubuntu/Kali 都能跑。
2. **proot 容器与 App 共享网络命名空间**：容器里监听 127.0.0.1:3065，
   App 侧直接连 127.0.0.1:3065 —— 不需要任何端口转发。
3. **进程必须前台常驻**：proot 的 bind 挂载是 ptrace 里的路径翻译，
   脱离 proot 的进程看到的是真实 Android 文件系统（容器路径全消失）。
   所以用 `startSession`（长驻会话，与 sshd/frpc 同一套）而非 nohup。

## 交付

1. **GatewayContainerInstaller**（208 行）：
   - 按 ABI 选资产（arm64-v8a → arm64 包，x86_64 → amd64 包）
   - 下载 + **sha256 校验**（要执行它，必须校验）
   - 拷进 rootfs 的 `/opt/agent2api/`，容器内 GNU tar 解包 + chmod +x
   - `isInstalled` 判据：二进制存在且 > 1 MB
2. **GatewayProcess** 加 `startInContainer` / `stopInContainer` / `isContainerRunning`：
   - 用 `envPort.startSession(env, GatewayContainerInstaller.runArgs(env), onLine)`
   - 日志经 onLine 直接进环形缓冲（网关 stdout 就是它的日志）
   - 停止：先 stop 会话再 `pkill -f agent2api-server` 补刀
3. **自启动改为内置优先**：配了外部地址 → 连外部；没配且容器已装 → 自动启内置；
   都没有 → 日志窗口提示怎么装。
4. **概览页按钮**：安装内置网关（约 13 MB）/ 启动 / 停止。

## 许可

Agent2API 是 AGPL-3.0。**不打进 APK**，运行时从官方发布页下载进容器，
作为独立进程运行，只通过 localhost HTTP 往来 → Praxis 保持 MIT。

## 待真机验证

- 装 rootfs（Ubuntu/Kali）→ 点「安装内置网关」→ 点「启动内置网关」
- 日志窗口应看到网关启动输出；`http://127.0.0.1:3065` 应能拉到模型
- 首次启动会初始化 `~/.agent2api`（容器内 /root），插件需按网关自身流程安装
