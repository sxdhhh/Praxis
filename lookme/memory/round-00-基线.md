# round-00 基线（压缩自前期会话）

## 已建立的共识
- 模块方向 `app → feature → ui → core`，`core:*` 纯 Kotlin（`core:data` 例外可用 android.*）
- 路由：`ui/navigation/Routes.kt` 的 `@Serializable : PraxisRoute` 与 `PraxisNavDisplay.kt` 的 `entry<...>` 1:1
- 顶/底留白：`contentPadding = PaddingValues(top = LocalTopBarInset.current, bottom = 24.dp + LocalScrollBottomLimit.current)`
- 弹窗铁律：常驻挂载 + `visible` 参数，不许 `if (visible) { Dialog }`
- 开关铁律：用项目 `MiuixSwitchWidget`（内部 `LiquidToggle`），不用裸 miuix Switch
- Kotlin 块注释**可嵌套**：KDoc 里写 `jniLibs/*.so` 会开出内层注释导致 `Unclosed comment`

## 工程事实
- 打包进 APK：`app/src/main/jniLibs/arm64-v8a/libstable_diffusion_core.so`（11.42 MB，3.0.0）
  + `app/src/main/assets/qnnlibs/`（20 文件 / 156.1 MB，含 6 个 HTP 版本 × 3 + 2 核心）
- 本地可跑测试模块：`core:ai`、`core:gateway`、`core:imagegen`
  （`:app`/`:feature:*`/`:core:data` 在 Windows 上配置不过，只能 CI）
- 本地命令：`JAVA_HOME=C:\creat-agent\env\jdk21` + `gradlew.bat :core:X:test --no-daemon --console=plain --configure-on-demand`
- 磁盘纪律：保持 ≥3 GB 空闲，`work/` 可清

## 反复自伤的 CI 红灯（清单）
- `when` **表达式**里留尾逗号（4 次）
- 声明先于使用（7 次）
- Kotlin 原始字符串不能转义 `"`（5 次）
- 漏 `import dev.praxis.core.gateway.userMessage` / `imagegen.userMessage`（4 次）
- `Int == 0L` 比较
- `groups` 传给 `RoutePlanner.plan` 而不是 `CandidateChain.of`
- 参数块插了两次 / lambda 作用域错（`@Composable invocations can only happen…`）
- `@Composable` 留在 `data class` 上面
- KDoc 里写 `jniLibs/*.so` → `Unclosed comment` → 连带 `Unresolved reference EngineProcess`
- 提交范围漏 `feature/settings` → `No parameter with name 'engineQnnInstalled'`
- `feature:chat` 缺 `core:gateway` 依赖 → 10 处 `Unresolved reference gateway`
