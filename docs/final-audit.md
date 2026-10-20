# Praxis 最终审计（R20 更新）

> 对照 `/home/praxis-design/` 五份文档（1069 行）逐条核对。
> 结论分三类：**已达成** / **主动放弃（附原因）** / **未达成（附缺口）**。
> 不写推测，每条都有代码或构建结果作依据。

---

## 一、构建与门禁现状（实测）

| 项 | 结果 | 证据 |
|---|---|---|
| 模块数 | **18** 个 Gradle 项目 | `settings.gradle.kts` |
| 源码规模 | 132 个 .kt / 15,440 行 | 递归统计（排除 build/） |
| 单元测试 | **203 例，0 失败** | 见下表 |
| `assembleDebug` | 成功，APK 81.8 MB | 2m56s（增量）/ 8m09s（全量重编） |
| `lintDebug` | **0 error / 18 warning，全部为版本升级提示** | 见「lint 结论」节 |
| 版本 | `1.0.0` / versionCode `10` | 合并后 Manifest 实测 |
| 构建环境 | 本机 proot，`-Xmx1100m`，`--offline` | 单次 2.5–8 分钟 |

### 测试分布（203 例）

| 模块 | 用例 | 覆盖内容 |
|---|---:|---|
| core:model | 5 | 功能块 id 全局唯一、域枚举 |
| core:ai | 44 | SSE 解析（12）、工具分片累积（8）、错误模型（3）、压缩策略（5）、**上下文预算与装配（16）** |
| core:tool | 25 | 工具层（12）、文件工具（6）、权限状态（7） |
| core:terminal | 26 | ANSI 扫描（8）、行缓冲（9，含 pendingWrap 折行不丢字/窄屏宽字符丢弃）、会话（9） |
| core:memory | 17 | RRF 检索（11）、写入管线（6） |
| core:workflow | 28 | 条件表达式（7）、模板插值（4）、引擎（17，含悬空 nextId 不静默成功/错误标记前缀匹配/合法结束反例） |
| core:plugin | 13 | Skill 解析（8）、注册表（5） |
| ui:theme | 5 | 调色板映射 |
| ui:navigation | 13 | 栈操作、幂等、降级、ensureSeeded 幂等重入/自定义 seed/不覆盖非空栈 |
| feature:chat | 9 | 节流写入 |
| :app | 13 | ToolWorkflowPort（7）、MemoryPortImpl（6） |

---

## 二、P1–P5 验收点核对

`04-roadmap.md` 列出的 16 个验收点，**全部在代码中存在实现**：

| 期 | 验收点 | 状态 | 落点 |
|---|---|:-:|---|
| P1 | 顶部渐变模糊 | 已达成 | `AppScaffold` 的 `progressiveTextureBlur` |
| P1 | 底栏玻璃折射 | **代码已接线，真机未验证** | `LocalPageBackdrop` 下发内容层采样源 → 底栏 `drawBackdrop(vibrancy→blur→lens)` |
| P1 | 低版本降级 | 已达成 | `LowAppScaffold` + `LowFrostedTopBar` |
| P2 | 丝滑滚动（到顶/到底） | 已达成（仅 Lazy 版） | `AutoFollowScrollLazy`（Chat/Terminal 已用）；非 Lazy 版零调用 |
| P2 | 拖拽列表防回流 | 已达成 | `isWaitingForUpdate` 守卫 |
| P2 | 对话框关闭动画不跳过 | 已达成 | `LocalDismissState` |
| P3 | 流式打字机效果 | 已达成 | `AiStreamEvent.Delta` + 同一气泡改文本 |
| P3 | 消息落库、重启可读 | 已达成 | `ChatPersistence` + Room v3 |
| P3 | 切会话不串线 | 已达成 | `viewModel(key = sessionId)` |
| P4 | 工具三档权限 | 已达成 | `PermissionTier` + `ToolPermissionState` |
| P4 | 终端页 | 已达成 | `feature:terminal`（391 行） |
| P4 | 工具列表 + 权限页 | 已达成 | `ToolsScreen` / 权限页 |
| P5 | 记忆检索（RRF） | 已达成 | `MemoryRetriever` |
| P5 | 记忆写回三阶段 | 已达成 | `MemoryWritePipeline` |
| P5 | 工作流引擎 | 已达成 | `WorkflowEngine` + 编辑器页 |
| P5 | Skill 加载 | 已达成 | `SkillParser` + `SkillRegistry` |

**注意**：这里说的是「代码存在且通过单测」，**不等于真机视觉验收通过**。
视觉类（模糊、玻璃、动画）只能装机后确认，本轮未装机。

### 底栏玻璃：从「误标已达成」到「真接线」（R21 → R22）

R21 发现指标写错了：当时依据是「`LocalKyantBackdrop` + kyant backdrop 依赖都存在」，
**但依赖与工具函数存在 ≠ 已接线**。实测事实：

| 当时的实际情况 | 证据 |
|---|---|
| 底栏没有任何 backdrop 代码 | 只有 `FloatingNavigationBar(color=surface)` |
| `LocalKyantBackdrop` 从未被 provide | 全仓无一个 `CompositionLocalProvider` 提供它 |
| 玻璃控件全走纯色回退 | `rememberPageBackdropOrFallback` 自身零调用 |
| `LiquidToggle` / `LiquidSlider` 压根不存在 | 只在 KDoc 里被提到 |

R22 已打通完整链路：

```
AppScaffold:153  rememberLayerBackdrop { drawRect(surface); drawContent() }
        ↓ LocalPageBackdrop provide（:202，仅在 layerBackdrop 的 Box 之外下发）
PraxisApp:160    bottomBar 槽（不在 content 里，避免底栏被录进采样源）
        ↓
BottomBarGlass   rememberBottomBarBackdrop() → Modifier.bottomBarGlass(...)
        ↓ drawBackdrop(vibrancy ⇒ blur(18dp) ⇒ lens(16/22dp, ca=0.1))
PraxisBottomBar  底色转全透明，玻璃负责全部背景
```

三个具体设计点：

1. **采样源必须来自内容层**。底栏在内容之上、内部没有内容；自建 backdrop 采到的是它自己，
   折射出来只是一块半透明实色。

2. **底栏必须画在 `layerBackdrop` 之外**。所以 `AppScaffold` 新增 `bottomBar` 槽，
   分两层 Box：内层挂 `layerBackdrop` 给内容，外层放底栏 —— 否则玻璃会采到自己（自采样重影）。

3. **玻璃代码单独一个文件（`BottomBarGlass.kt`）**。`miuix-blur` 的 AAR 声明 `minSdk=33`，
   而本项目 minSdk 是 28。把 blur 调用封在独立文件后面，低版本走不进那个分支，
   引用 `android.graphics.RuntimeShader` 的类就永远不会被解析。

**未验证部分**：以上是代码链路与编译期接线已验证（APK 的 dex 里 `BottomBarGlassKt`
与 `PraxisBottomBar` 同在一个 dex，引用关系成立）。
**折射、模糊、镜片边缘的视觉效果一条都没看过** —— 必须装机才能确认。

### 为什么会漏掉：单测验证不了「有没有人调」

同类事故已发生三次（`MemoryWritePipeline`、`ensureSeeded`、本轮这批），每次都单测全绿。
已把静态引用扫描固化进 `tools/audit_unwired.py`：

```sh
python3 tools/audit_unwired.py            # 报告
python3 tools/audit_unwired.py --strict   # CI 用，有新增未接线项则退出 1

```

白名单 `KNOWN_UNWIRED` 必须写原因；已接线的项若不从白名单删除，脚本会主动报「白名单过期」。

---

## 三、主动放弃（含原因）

| 文档要求 | 决定 | 原因 |
|---|---|---|
| `core:speech`（TTS/STT 9 引擎） | **不做** | 设计文档自己把语音列为 P5 之后；9 种引擎适配是数千行且强依赖厂商 SDK。半成品语音比没有更糟（用户以为能说话，实际只会报错）。已保留 `PraxisDomain.SETTINGS` 里的「语音」入口占位。 |
| `core:tool:android` 独立模块 | **合并进 `:app`** | 文档要求拆开是为了「契约层可纯 JVM 测」。实际做法是 `core:tool` 纯契约 + 实现在 `:app/data/`，效果相同且少一个模块。真实实现：`ShellCommandRunner`、`FileTools` 的沙箱边界都在 `:app`。 |
| ToolPkg（QuickJS，19 类钩子） | **不做** | 设计文档 `03-business.md` 明确标 P3。Operit 的 `PackageManager.kt` 167KB，且要嵌 QuickJS 运行时。风险量级与 Skill 完全不同（能执行代码 vs 只能注入提示词）。 |
| MCP（P2） | **不做** | 需要完整 JSON-RPC 客户端 + 工具描述转换 + 传输层（stdio/SSE）。当前工具层已够用，MCP 的边际价值不足以支撑它带来的攻击面。 |
| 语音档案化（`SpeechProfileEntity`） | 随 `core:speech` 一起不做 | 同上 |
| `ui:features:*` 拆 9 个模块 | **合并为 3 个** | 文档设想每域一个模块。实际 `feature:chat` / `feature:settings` / `feature:terminal`。理由：features 之间零依赖 + 域内共享 ViewModel，拆太细会造循环依赖，且每次加页面都要动 `settings.gradle.kts`。 |

---

## 四、未达成（诚实列出）

| 缺口 | 影响 | 为什么不本轮做 |
|---|---|---|
| **未装机验证** | 视觉/交互/真机行为全部未验证 | 用户明确要求「`/home/` 全部文档预期达成之前不装机」。而文档里有一部分（语音/MCP/ToolPkg）已被主动放弃，所以「全部达成」需要一次显式确认。 |
| 工作流可视化编辑器 | 目前只有内置示例 + 运行，不能拖拽建节点 | 编辑器要解决节点增删 + 引用完整性 + 参数校验，纯文本配置更好做。等引擎真机跑稳再上画布。 |
| ~~模型配置加密存储~~ | **R17 已完成** | 改用 Android Keystore + AES/GCM（不引 Tink），加密失败降级明文，旧数据自动迁移。见下节。 |
| 记忆检索无向量 | 靠关键词 + 标签 + RRF | 内置 embedding 会让 APK 涨几十 MB，且真机算力受限。千级以内的记忆用 RRF 足够。 |
| 终端宽字符 | 只做 CJK=2 的确定规则 | 完整 Unicode EastAsianWidth 表体积大，且实际终端场景以 ASCII + CJK 为主。 |

---

## 五、七项审计结果（R14 实测）

| # | 审计项 | 结果 |
|---|---|---|
| 1 | `core:*` 里出现 `import androidx.compose` | **0 处** |
| 2 | `feature:*` 之间直接依赖 | **0 处** |
| 3 | 设备硬编码（1280 / 1080.dp / density=3.25） | **0 处** |
| 4 | 明文密钥模式（sk-* / ghp_* / Bearer） | **0 处** |
| 5 | `apiKey` 进了 data class 字段 | **无**（只在 SettingsRepository 单独存取） |
| 6 | TODO / FIXME | **0 处** |
| 7 | 关键组件是否有生产调用点（非仅 DI 注册） | **全部有**（见下） |

第 7 项是 R12 教训提炼的：`MemoryWritePipeline` 曾只在 DI 注册、零调用，
界面能看、检索能跑，但记忆库永远是空的——测试全绿也发现不了。
本轮核对：`SkillRegistry.reload()` / `composePrompt()` 在 `PraxisApp:83/106` 有调用；
`WorkflowEngine` 4 处、`MemoryPort` 5 处、`MemoryWritePipeline` 3 处。

---

## 五之二、服务商密钥加密（R17）

| 项 | 实现 |
|---|---|
| 方案 | Android Keystore + AES/GCM/NoPadding（128-bit tag） |
| 为什么不用 EncryptedSharedPreferences | 依赖 Tink，多 ROM 有初始化抛异常导致**设置页打不开**的案例 |
| 存储格式 | `base64(iv):base64(ciphertext)`，IV 每次随机 |
| 加密失败 | 降级存明文（保可用性） |
| 解密失败 | 返回空串 = 「未配置」，提示用户重填（不崩） |
| 旧数据 | `looksEncrypted()` 判别，启动时 `migrateApiKeyIfNeeded()` 自动升级 |
| 备份 | `backup_rules.xml` / `data_extraction_rules.xml` 已全量排除，不存在跨设备解密问题 |
| 测试 | 5 例（只测判别函数；encrypt/decrypt 需真机 Keystore） |

## 五之二、服务商密钥加密（R17）

| 项 | 实现 |
|---|---|
| 方案 | Android Keystore + AES/GCM/NoPadding（128-bit tag） |
| 不用 EncryptedSharedPreferences 的理由 | 依赖 Tink，多 ROM 有初始化抛异常导致设置页打不开的案例 |
| 存储格式 | base64(iv):base64(ciphertext)，IV 每次随机 |
| 加密失败 | 降级存明文（保可用性） |
| 解密失败 | 返回空串 = 未配置，提示用户重填（不崩） |
| 旧数据 | looksEncrypted() 判别，启动时 migrateApiKeyIfNeeded() 自动升级 |
| 备份 | 两个 backup 规则文件已全量排除，不存在跨设备解密问题 |
| 测试 | 5 例（只测判别函数；encrypt/decrypt 需真机 Keystore） |

## 六、lint 结论

**0 error / 18 warning。18 条全部是版本升级提示，无代码问题：**

| 规则 | 条数 | 含义 |
|---|---:|---|
| `NewerVersionAvailable` | 12 | 依赖有更新版本 |
| `GradleDependency` | 5 | 同上（toml 里声明） |
| `AndroidGradlePluginVersion` | 1 | Gradle 9.8.2 已发布 |

### 已修掉的代码类告警

| 规则 | 修法 |
|---|---|
| `AutoboxingStateCreation` | `remember { mutableStateOf(0) }` → `mutableIntStateOf(0)`，读写同步改 `.intValue` |
| `MonochromeLauncherIcon` | adaptive-icon 补 `<monochrome>` |
| `ObsoleteSdkInt`（themes.xml） | 删掉 minSdk 28 下多余的 `tools:targetApi="23"` |
| `ObsoleteSdkInt`（mipmap 目录） | **无法修**，`app/lint.xml` 按路径压制，原因见下 |

### 为何 mipmap-anydpi-v26 的告警不能按建议修

lint 建议把 `mipmap-anydpi-v26` 并入 `mipmap-anydpi`。**实测照做会让构建直接失败**：
`AAPT: error: resource mipmap/ic_launcher not found`（AGP 不把无 SDK 限定符的 anydpi
目录当作 mipmap 资源）。目录级 `tools:ignore` 对 folder 类告警无效，只能在 `app/lint.xml` 里
按路径压制。

### 一个曾长期存在的统计错误

早前用 SARIF 的 `results[].level` 统计严重度，而该字段在本项目里恒为 `none`，
于是每轮都误报「lint 零警告」。**正确口径是 XML 报告的 `severity` 属性**。

---

## 七、装机前必须确认的三件事

1. **放弃清单是否接受**：`core:speech` / MCP / ToolPkg / 可视化编辑器。（密钥加密已在 R17 完成，不在放弃清单内）
   这五项不做，是判断而非遗漏（原因见第三节）。

2. **release 签名是否要现在配**：目前缺 `PRAXIS_KEYSTORE` / `PRAXIS_STORE_PASS` /
   `PRAXIS_KEY_PASS` 时，release 产物**保持未签名**——这是有意的（让「忘了配签名」
   在安装阶段暴露，而不是产出一个看起来正常的包）。

3. **装机用 debug 还是 release**：debug 包名 `dev.praxis.debug`，可与旧版共存。
