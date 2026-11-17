# round-25 内置网关闭环 + 用户两问

日期：2026-11-17

## 用户三问

1. 下一步（插件自动装进容器）—— 已做：面板 setup/login → install-from-release → progress 轮询。
2. debug 包比 release 慢 —— 原因与修法见下。
3. build 检查太久 —— lint 按需化 + 已有缓存/并行/配置缓存确认。

## debug vs release（答复）

- debug **没有 R8**（isMinifyEnabled=false）：不优化、不收缩，dex 大、类加载多；
- Compose 在 debug 下带**调试钩子**（LiveLiterals/重组检查），运行时多干活；
- debuggable=true 允许 JDWP 全量插桩；没有 baseline profile，冷启动全靠 JIT。
- **修法（e47c22d）**：本机缺 CI 签名时，release 用 **debug keystore** 签名 ——
  `assembleRelease` 产出「可直接安装的优化包」，日常用 release 即可。

## CI 提速（答复）

- lintDebug（3-6 分钟）从每次 push 摘掉，改为 workflow_dispatch 点名 lint 才跑；
- gradle.properties 已有 caching/parallel/configuration-cache（确认在位）。

## 插件自动安装（下一步内容）

网关真实路由（从 http.rs 抠的，不是猜的）：
- `POST /api/panel/setup`（首次注册；409=已存在，当成功）
- `GET /api/panel/status`（registered 判断）
- `POST /api/plugins/install-from-release` {id: agent2api-providers}（异步 spawn）
- `GET /api/plugins/progress`（轮询）
- `GET /api/plugins`（清单）

GatewayConnection 加 5 个方法；概览页「初始化管理员并安装提供商插件」按钮；
PraxisApp 轮询 30×3s 进度进日志窗口。

## 验证

core:gateway **440 例全绿**。

## 手机上的完整流程（待真机验收）

1. Linux 环境页装 Ubuntu/Kali rootfs
2. 网关页 → 安装内置网关（13 MB，sha256 校验）→ 自动启动
3. 填管理员账号密码 → 初始化并安装插件（33 家装进容器）
4. 模型自动拉取 → 127.0.0.1:3065 就是手机自己