# Praxis 最终审计（R37 更新）

> 对照 `/home/praxis-design/` 五份文档（1069 行）逐条核对。
> 结论分三类：**已达成** / **主动放弃（附原因）** / **未达成（附缺口）**。
> 不写推测，每条都有代码或构建结果作依据。

---

## 一、构建与门禁现状（实测）

| 项 | 结果 | 证据 |
|---|---|---|
| 模块数 | **18** 个 Gradle 项目 | `settings.gradle.kts` |
| 源码规模 | 150 个 .kt / 19,113 行 | 递归统计（排除 build/） |
| 单元测试 | **225 例，0 失败** | 见下表 |
| `assembleDebug` | 成功，APK 81.8 MB | 2m56s（增量）/ 8m09s（全量重编） |
| `lintDebug` | **0 error / 18 warning，全部为版本升级提示** | 见「lint 结论」节 |
| 版本 | `1.0.0` / versionCode `10` | 合并后 Manifest 实测 |
| 构建环境 | 本机 proot，`-Xmx1100m`，`--offline` | 单次 2.5–8 分钟 |

### 测试分布（225 例）

| 模块 | 用例 | 覆盖内容 |
|---|---:|---|
| core:model | 5 | 功能块 id 全局唯一、域枚举 |
| core:ai | 44 | SSE 解析（12）、工具分片累积（8）、错误模型（3）、压缩策略（5）、**上下文预算与装配（16）** |
| core:tool | 25 | 工具层（12）、文件工具（6）、权限状态（7） |
| core:terminal | 26 | ANSI 扫描（8）、行缓冲（9，含 pendingWrap 折行不丢字/窄屏宽字符丢弃）、会话（9） |
| core:memory | 17 | RRF 检索（11）、写入管线（6） |
| core:workflow | 28 | 条件表达式（7）、模板插值（4）、引擎（17，含悬空 nextId 不静默成功/错误标记前缀匹配/合法结束反例） |
| core:plugin | 13 | Skill 解析（8）、注册表（5） |
| core:data | 15 | 日志行格式 round-trip（10，含尾随换行/冒号不吞 tag/orphan 行）、Keystore 密文判据（5） |
| ui:theme | 5 | 调色板映射 |
| ui:navigation | 13 | 栈操作、幂等、降级、ensureSeeded 幂等重入/自定义 seed/不覆盖非空栈 |
| feature:chat | 9 | 节流写入 |
| feature:settings | 12 | 映射一致性（权限判定三态 4、工作流节点参数/后继 5、档位与风险文案 3） |
| :app | 13 | ToolWorkflowPort（7）、MemoryPortImpl（6） |

---

## 二、P1–P5 验收点核对

`04-roadmap.md` 列出的 16 个验收点，**全部在代码中存在实现**：

| 期 | 验收点 | 状态 | 落点 |
|---|---|:-:|---|
| P1 | 顶部渐变模糊 | 已达成 | `AppScaffold` 的 `progressiveTextureBlur` |
| P1 | 底栏玻璃折射 | **代码已接线，真机未验证** | `LocalPageBackdrop` 下发内容层采样源 → 底栏 `drawBackdrop(vibrancy→blur→lens)` |
| P1 | 低版本降级 | 已达成 | `LowAppScaffold` + `LowFrostedTopBar` |
| P2 | 丝滑滚动（到顶/到底） | 已达成（仅 Lazy 版） | `AutoFollowScrollLazy` = apixiaoyuan v5 弹簧引擎（k=130/c=24/m=1，245 行逐行移植）；Chat / Terminal / Log 三处已用；非 Lazy 版零调用 |
| P2 | 拖拽列表防回流 | 已达成 | `isWaitingForUpdate` 守卫 |
| P2 | 预测性返回转场 | 已达成（四档可选） | `ui:navigation/transition/`：AOSP（InstallerX 260 行逐行移植）/ miuix / Scale / Classic；外观页可切且立即生效 |
| P2 | 对话框关闭动画不跳过 | 已达成 | `LocalDismissState` |
| P3 | 流式打字机效果 | 已达成 | `AiStreamEvent.Delta` + 同一气泡改文本 |
| P3 | 消息落库、重启可读 | 已达成 | `ChatPersistence` + Room v3 |
| P3 | 切会话不串线 | 已达成 | `viewModel(key = sessionId)` |
| P4 | 工具三档权限 | 已达成 | `PermissionTier` + `ToolPermissionState` |
| P4 | 终端页 | 已达成 | `feature:terminal`（391 行） |
| P4 | 工具列表 + 权限页 | 已达成 | `ToolsScreen`（`PermissionSwitchRow` 显示真实策略判定 + 用户级开关）/ 权限页 |
| P5 | 记忆检索（RRF） | 已达成 | `MemoryRetriever` |
| P5 | 记忆写回三阶段 | 已达成 | `MemoryWritePipeline` |
| P5 | 工作流引擎 | 已达成 | `WorkflowEngine` + 节点列表页（R31 起 `WorkflowNodeCard` 逐节点展示参数与后继；仍无可拖拽编辑器） |
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

### 第二个审计脚本：滚动终点（`tools/audit_scroll_inset.py`）

底栏是**浮层**，由此推出两条必须同时满足的约束：

1. 内容区**容器**不能给底栏留 padding（否则内容整体被抬高，什么都不从底栏下经过，玻璃无物可折射）；
2. 滚动**终点**必须抬高 `LocalScrollBottomLimit`（否则最后一项永远滚不到底栏上方）。

第 2 条曾漏掉一整批：**8 个设置页 + 会话列表页**全都硬编码 `bottom = 24.dp`，
而底栏占位是 96dp —— 每页最后一项被压住约 72dp。聊天页与终端页当时写对了，设置页全漏。

这种缺陷单测看不见（布局问题），lint 也不报（语法完全合法）。所以另有脚本兜住：

```sh
python3 tools/audit_scroll_inset.py --strict
```

修法：8 个设置页统一改用 `SettingsList`（内部读 `LocalScrollBottomLimit`），
会话列表页直接读该 Local。**新增设置页只要用 `SettingsList` 就不会再漏。**

### 顺带删掉一个会诱发 bug 的 API

`AppListScaffold` / `AppScrollScaffold` / `AppTabScaffold` 三个变体已删除。
它们内部都再套一层 `AppScaffold`，而顶栏由 `:app` 的 `PraxisApp` 统一提供 ——
任何页面套用都会出现**双顶栏**。零调用的原因不是「还没迁移」，而是**它们与架构冲突**。
留着只会诱使后来者踩坑。`ui:design` 现在只暴露 `AppScaffold` 一个栏层。

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
| ~~未装机验证~~ | **R24 起已装机验证**：底栏四 tab 均匀分布（x=232/479/726/973, y=2643）、顶栏标题、输入栏、日志页均实测正常 | 遗留待观感确认：玻璃折射强度、顶栏渐变模糊、转场动画、底部让位尺寸（96dp） |
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

## 五之三、API 配置页对齐 Operit（R34）

| 项 | 实现 |
|---|---|
| 参照物 | Operit `ModelApiSettingsSection.kt`（2197 行）/ `ModelConfigData.kt`（30+ 字段）/ `ApiProviderConfigCollect.kt`（38 种服务商） |
| 抄的是什么 | **「选服务商 → 自动回填默认端点与默认模型」** 这条流程；不是字段数量 |
| 预设表 | `core:model/settings/ApiProvider.kt`，14 项（国内 5 + 国际 4 + 本地 3 + 自定义），纯数据无 Compose 依赖，可纯 JVM 单测 |
| 未抄字段 | 多 Key 池、topP/topK/penalty、上下文总结、llama.cpp/MNN 参数、自定义请求头、各家专有开关 —— 判据是「填了会不会真的进请求体」 |
| 控件 | 全部 miuix：`Card` + `SmallTitle` + `TextField` + `OverlaySpinnerPreference` + `SliderPreference` + `TextButton` |
| 弹层 | 用 Overlay 版而非 Window 版（Window 版内部开 Dialog 新 Window，单 Activity + NavDisplay 下抢焦点闪退） |
| 保存 | 保持显式保存（Operit 是边改边自动存）；`baseUrl` 半截字符串自动落盘会存坏，且触发无效请求 |
| 温度 | 本版才成为真控件：同时修掉 `SettingsBackedProvider` 只 `copy(model)` 导致 `settings.temperature` 从未进过请求体的缺陷 |
| 端点拼接 | 抽到 `core:ai/ChatEndpoint.kt`；修正旧规则只认「正好以 /v1 结尾」导致智谱 `/v4`、阿里云 `/compatible-mode/v1` 被插成 `.../v4/v1/chat/completions` → 404 |
| 清除密钥 | 新增显式按钮。密钥留空在 `onSave` 里表示「不修改」，此前「想删 Key」无任何表达方式（填空格会被 `isNotBlank()` 拦掉） |
| 测试 | `ChatEndpointTest`（13 例，逐条对应真实故障）+ `ApiProviderPresetsTest`（6 例，钉住预设表默认值正确性） |

## 五之四、主页聊天栏对齐 Operit（R35）

| 项 | 实现 |
|---|---|
| 参照物 | Operit `ClassicChatInputSection.kt`（778 行）与 `AgentChatInputSection.kt`（3339 行） |
| 抄的结构 | 纵向容器：**阶段提示在上、输入行在下**；圆形图标按钮而非文字按钮 |
| 输入框 | 裸 `BasicTextField` + 自画 Surface → miuix `TextField`（`useLabelAsPlaceholder = true`、`minLines=1 / maxLines=5`） |
| 按钮 | `TextButton("发送"/"生成中")` → `IconButton` 40dp 圆钮，三态（Send 档 / Stopwatch 档 / 禁用） |
| 阶段提示 | 新增 `ChatStage`（Idle/Connecting/Receiving/ExecutingTool）+ 转圈 + 文案，入场 fade 300 / expand 400 |
| 停止键 | 新增 `ChatViewModel.cancel()`：取消协程 + flush 半截内容 + 状态归位 |
| 未抄 | 附件面板、全屏输入、待发队列、@提及高亮、Token 超限、语音 —— 均无对应链路 |

### 顺带修掉的两个真缺陷

1. **`CancellationException` 被当成错误**：`catch (e: Exception)` 排在前面，会把用户主动取消捕获成「未知错误」气泡。修正为 `CancellationException` 分支前置并重新抛出。
2. **`feature:chat` 缺 miuix-icons 直接依赖**：此前靠 `ui:widgets` 的 `api` 传递；改为本模块 `implementation(libs.miuix.icons)`。

### 测试

`ChatStageTest`（6 例）：阶段推进顺序、流式中重复 send 被拒、取消后状态归位且保留半截正文、取消不产生错误气泡、空闲取消是空操作、每个非空闲阶段都有文案。
用 `Dispatchers.setMain(UnconfinedTestDispatcher())` 驱动 `viewModelScope`（纯 JVM 无 Main 会直接抛异常）。

## 五之五、丝滑滚动回到 apixiaoyuan 正源（R36）

用户指出：「丝滑滚动用 apixiaoyuan 的，你之前看到是 nizou 那个项目的」。

### 错误事实链（已完全取证）

| 时间 | 事实 |
|---|---|
| 基线 `63507cb` | `AutoFollowScroll.kt` = **245 行**，与 apixiaoyuan 原版 **只差 package 一行**（v5 弹簧引擎） |
| `f8ef00d` | 提交信息写「丝滑滚动改照抄 apixiaoyuan 原生 animateScrollToItem」，内容却是换成 `animateScrollToItem` + `-2` 余量 → **107 行** |
| 真因 | 那个写法来自 **`cn.nizou.sxd/CustomScoreScreen.kt:317`**，不是 apixiaoyuan。apixiaoyuan 全仓**零** `animateScrollToItem` / `animateScrollTo` |
| 后果 | 注释与提交信息都声称「照抄 apixiaoyuan」，实际把正源实现删了，引用的是一个不存在的出处 |

### 两套方案不是一回事

| 方案 | 出处 | 解决什么 |
|---|---|---|
| `animateScrollToItem` + `-2` | cn.nizou.sxd `CustomScoreScreen.kt:317` | 一次滚动动画；追加时反复重启，速度接不上 |
| `LOG_SCROLL_ANIMATION_THRESHOLD = 500` | cn.nizou.sxd `LogsScreen.kt:689` | 手动跳转多远才瞬跳 |
| **弹簧跟随引擎** | apixiaoyuan `AutoFollowScroll.kt`（k=130/c=24/m=1） | **持续跟随**：内容高度离散变化→每帧连续速度轨迹，v 从不归零 |

### 处置

1. `git checkout 63507cb -- AutoFollowScroll.kt`，恢复 245 行 v5 弹簧引擎，并与上游逐字 diff 验证（仅 package 不同）。
2. `feature:settings/LogScreen` 接入 `AutoFollowScrollLazy(itemCount = entries.size)`（上游 `LogScreen.kt:153` 原样）。日志页此前只有跳顶/跳底 FAB、无自动跟随。
3. 保留日志页的 500 阈值 FAB，但注释明确写出它是 **cn.nizou.sxd 的值**、与自动跟随无关。
4. `ChatScreen` / `TerminalScreen` 的错误注释同步纠正（原注释把引用指错了项目）。
5. 显式补齐 `compose-foundation` 依赖（`ui:design` 与 `ui:theme`）——它是 `ScrollState` / `LazyListState` / `gestures.scrollBy` / `isSystemInDarkTheme` 的来源，之前全仓靠 material3 与 miuix 隐式传递。

### 未构建

本轮仍按要求**未构建**（R32–R36 五轮累积）。

## 五之六、Linux 环境：Ubuntu 容器 + SSH + FRP（R37）

用户要求：「终端使用 operit 的 ubuntu，并且可以开启 frp 服务器和 ssh，然后可以换源为国内镜像源，但是界面要自写可以部分参考 apixiaoyuan」。

### 取证结果：Operit 主仓**没有**这套能力

搜通了 `/root/Operit`：

| 搜什么 | 结果 |
|---|---|
| `proot` / `rootfs` / `ubuntu` | 只在 `PathMapper.kt`、`RawSnapshotBackupManager.kt` 等文件里作为**路径字符串**出现 |
| `sshd` / `frpc` / `frps` | 只出现在 `OpenSourceLicenses.kt`（开源许可列表） |
| `rootfs` / `alpine` / `debian` 容器 | 只有 `templates/flutter/linux`（模板目录，与本主题无关） |
| 终端执行层 | `StandardShellExecutor` = `Runtime.exec` + `shell -c`，标准 Android 权限 |

结论：Operit 的终端是**系统 shell**，不是 Ubuntu 容器。所以「用 operit 的 ubuntu」这件事在源码层面不成立 —— 本能力按需求新写。

### apixiaoyuan 的相反结论（必须记录，否则以后还会重蹈）

`cn.apixiaoyuan.app/core/pk/host/TerminalSession.kt` 的 KDoc 明确断言过：

> 「Android 自带 `/system/bin/sh` + toybox 就是一个完整用户态……再塞一个 Ubuntu rootfs 只是把同样的东西换成 200MB 的另一份副本，而且还要 proot 才能跑（国产 ROM 上常被限制）。」

那条结论对它的目标（终端里跑 node）成立。本次需求不同，**必须**要真发行版，理由三条：

| 需求 | 系统 sh | 为什么必须容器 |
|---|:-:|---|
| `apt` 装包 | ✗ | toybox 没有包管理器 |
| 换国内镜像源 | ✗ | 没有 apt 就没有 sources.list |
| `sshd` / `frpc` | ✗ | 需 glibc 用户态与完整 `/etc`；Android 是 bionic |

### 架构：纯逻辑在 `core:linux`，执行在 `:app`

沿用项目既有的「端口在里、实现在外」（与 `CommandRunner` / `ChatPersistence` 同套路）：

| 层 | 文件 | 职责 |
|---|---|---|
| `core:linux`（纯 JVM） | `UbuntuMirror.kt` | 6 个国内镜像 + 2 个 LTS 版本 + `ubuntu-base` 下载地址构造 |
| 同上 | `ProotCommand.kt` | proot 参数构造、换源脚本、DNS 脚本 |
| 同上 | `RemoteAccess.kt` | `SshConfig` / `SshdScript` / `FrpConfig` / `FrpScript` |
| `feature:settings` | `LinuxEnvScreen.kt`（472 行） | 界面（自写，miuix 控件） |
| 同上 | `LinuxEnvViewModel.kt` | 状态机 + `LinuxEnvPort` 端口 |
| `:app` | `ProotEnvPort.kt`（338 行） | 下载、toybox 解压、ProcessBuilder 起 proot |

### 已验证的硬前提（2026-10-21 实测）

| 前提 | 验证方式 | 结果 |
|---|---|---|
| `nativeLibraryDir` 里的文件可执行 | `ls -la /data/app/.../lib/arm64/` | `-rwxr-xr-x` ✅ |
| `useLegacyPackaging = true` | `praxis.android.application.gradle.kts:27` | 已设，native 库落地为真实文件 ✅ |
| toybox tar 支持 gzip 与忽略 owner | `toybox tar --help` | `-z` / `-o` 均支持 ✅ |
| 设备有 sha256sum / ps / kill | `toybox` applet 列表 | 有；**无** wget/curl/awk ⚠️ |
| 清华 ubuntu-base arm64 可下 | `curl -I` | HTTP 200，29,865,086 字节 ✅ |

### 三个 Android 特有的坑（都写进了代码注释与单测）

1. **端口必须 ≥ 1024**：普通应用无 `CAP_NET_BIND_SERVICE`，写 22 会让 sshd 直接退出，用户看到的是「已开启但连不上」。`SshConfig.portProblem` 当场提示。
2. **`UsePAM no`**：proot 里 `pam_loginuid` / `pam_systemd` 写不了 `/proc/self/loginuid`，会把**所有**密码登录拒掉。
3. **`sshd -D`**：不加 `-D` 它自己 fork 到后台，我们的进程句柄立即退出 → UI 永远显示「已停止」，但 sshd 其实在跑。

### 换源的两个必做细节

- **arm64 必须用 ports 池**（`ubuntu-ports`），用主池会报「找不到包」；
- **deb822 与传统 sources.list 都要写**：24.04 读前者、22.04 读后者，只写一个就会出现「换源后 apt 还在连官方」。

### 未做（如实列出）

| 项 | 状态 |
|---|---|
| proot 二进制本身 | **未随 APK 打包**（需上游二进制 + WeKit 那份 `android-ptrace-events.patch`）。缺它时页面明确显示「缺少 proot」而不是假装能用 |
| rootfs 预置 | 不做 assets 预置，一律运行时下载（APK 体积） |
| 终端页接入容器 | 容器的交互 shell 目前与系统终端**并存**；未做「在终端页里切换 shell」 |
| FRP 服务端 | 需用户自备公网 frps |

### 测试

`core:linux`：`UbuntuMirrorTest`（9 例）+ `ProotCommandTest`（15 例）+ `SshConfigTest`（11 例）+ `FrpConfigTest`（8 例）。
`feature:settings`：`LinuxEnvViewModelTest`（12 例，含「busy 必须复位」「端口 22 不许开启」「停止级联」）。

## 五之七、已知遗留

| 项 | 状态 |
|---|---|
| `PermissionTier` 不持久化 | `AppModule` 固定 `STANDARD`，重启回到标准档 |
| R32–R37 累积未编译 | 六轮按用户要求未构建，下轮统一验证 |
| 视觉观感验收 | 玻璃折射、顶栏渐变模糊、转场动画、底部让位四处均未做主观确认 |
| release 签名 | 仍缺 `PRAXIS_KEYSTORE` / `PRAXIS_STORE_PASS` / `PRAXIS_KEY_PASS` |

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
