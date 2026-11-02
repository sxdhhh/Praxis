# Praxis 本轮改造计划（调查结论 + 实施方案）

> 计划基于对三件事的实际核查：Praxis 现有实现（读源码）、
> NexioSchedule 的技术栈（已 clone 到 `C:\creat-agent\ref-nexio`）、
> 以及用户真机截图。不确定的地方标了「待确认」，不猜。

## 调查结论（已核实的事实）

### 关键发现：两个项目用同一个 miuix 版本 0.9.4

```
NexioSchedule  gradle/libs.versions.toml:  miuix = "0.9.4"
Praxis         gradle/libs.versions.toml:  miuix = "0.9.4"
```

所以 NexioSchedule 用的 `top.yukonga.miuix.kmp.popup.OverlayDropdownPopup`
在 Praxis 里**可以直接用**，不需要移植、不需要升级依赖。这是本轮最大的有利条件。

### 现有实现与差距

| 项 | Praxis 现状 | 差距 |
|---|---|---|
| A 侧边栏 | `ChatSidebar.kt`（351 行）：长按 → 二次确认删除，仅单条 | 缺多选模式 + 底部批量操作条 |
| C 加号菜单 | `AttachmentPanel.kt`（305 行）：自建 `Popup` + `Card` + 4 列九宫格 | 与 miuix 下拉样式完全不同 |
| D 动画 | 模型/功能菜单走 `MiuixSmallTitleDropDown` → `OverlayListPopup`（**无尺寸插值**） | 缺收起↔展开的形变动画 |
| F UI 参考 | — | 未引入 NexioSchedule 的圆角/悬浮/动效体系 |

### B 项根因（已定位到行）

`ContextUsage.used` 由 `ChatViewModel.publishUsage()` 写入，而它的**三个调用点全在发送路径**：

```
ChatViewModel.kt:1677  publishUsage(buildPreparedMessages(config), config)
ChatViewModel.kt:1717  publishUsage(buildPreparedMessages(config), config)
ChatViewModel.kt:2633  publishUsage(buildPreparedMessages(config), config)
```

`restoreIfNeeded()`（重进 App 的入口）**不调用**它。所以：

- 重进后 `used` 停在 `0` → 进度条显示 `0 / 1.0M`；
- 用户看到的就是「命中率清零」。

「部分内容消失」另有嫌疑点：`ChatSidebar` 的 KDoc 里记着 `ensureSession` 走
`@Insert(onConflict = REPLACE)` → SQLite 先 DELETE 再 INSERT → `messages` 表
`ON DELETE CASCADE` → **整个会话的消息被级联删光**。历史上已踩过一次，
需要核查是否还有别的调用点在踩。

### E 项（终端）—— 本次调查没能定位，需要真机日志

用户截图里：Linux 环境页显示「容器运行中」，但报
`stty: 'standard input': Inappropriate ioctl for device`。

已核查：
- 源码里**没有** `stty` 字样 → 那是容器内 shell 启动脚本（bash 登录 profile）打的；
- 该路径走 `LinuxEnvViewModel.start()` → `port.startSession(env, loginArgs)`；
- 说明这条 `loginArgs` 起的 bash **拿不到 tty**。

**我上一轮在 x86 模拟器上验证的是管道降级分支（无 libpty），真机走的是 PTY 分支。**
所以真机的失败点我并没有覆盖到。这台模拟器上 `libpty.so` 只有 arm64 版、
x86 加载不了，无法复现 PTY 路径。

**需要用户提供**：真机点「启动」后的完整报错（最好含 `logcat -s ProotTermSession PraxisPty`）。
没有它，我只能靠读代码推测，那样修出来的东西不可信。
## 实施方案（按依赖顺序，逐项验证）

### 阶段 0：地基（先做，后面都依赖它）

**0.1 从 NexioSchedule 提炼可复用的 UI 基础**

需要读透并移植/借鉴的文件：

| 文件 | 用途 |
|---|---|
| `effects/liquidglass/DampedDragAnimation.kt` | 拖动动画（拖动阻尼 + 回弹）|
| `effects/liquidglass/InteractiveHighlight.kt` | 按压缩放/高亮反馈 |
| `effects/motion/MorphTransition.kt` | 形变动画（收起态↔展开态的尺寸插值）|
| `components/BackToNowFloatingButton.kt` | 悬浮按钮（返回类控件背景）|
| `basic/OverlayDropdownMenu.kt` | miuix 下拉动画封装（D 项直接受益）|

**验收**：把这批代码读完，提炼成 `ui/design` 里 2~3 个可复用组件 + 一份对照说明。
不整包抄 —— NexioSchedule 是 KMP 多平台，Praxis 是纯 Android，直接抄会带进一堆
`expect/actual` 与平台抽象。只取「算法与视觉参数」。

### 阶段 1：D 项 —— 菜单动画统一（改动最小、见效最快）

把 `MiuixSmallTitleDropDown` 从 `OverlayListPopup` 换成 `OverlayDropdownPopup`，
让它具备「收起态胶囊 → 展开成面板」的形变。

**风险**：`OverlayDropdownPopup` 需要 `collapseContent` / `collapseSize` 等参数，
签名要按 NexioSchedule 的用法逐个对齐。miuix 0.9.4 同版本，API 存在性无疑，但
参数形状要核对。

**验收**：模拟器上点开模型菜单，录屏/连续截图确认有展开动画；关闭时有收起动画。

### 阶段 2：C 项 —— 加号菜单改成统一样式

把 `AttachmentPanel` 的九宫格 `Card` 换成与模型/功能菜单同源的 miuix 下拉/面板形态。

**待确认**：8 个附件项是「带图标的网格」语义，直接套 miuix 下拉（纯文字列表）会丢图标。
两种做法：
  (a) 用 `OverlayDropdownPopup` 的 `entries` 承载带图标的行（若 miuix 支持 icon slot）；
  (b) 保留网格布局，但容器/圆角/阴影/尺寸/动画全部对齐 miuix 下拉的视觉参数。
先读 miuix 的 `DropdownItem` 有没有 icon 位，再定。

**验收**：三块浮层（模型 / 功能 / 附件）同宽、同右边距、同底边；模拟器截图对比。

### 阶段 3：A 项 —— 侧边栏多选批量管理

参照 DeepSeek 截图（用户提供三张）：

```
长按某条 → 进入多选模式：
  · 每条左侧出现圆形复选框
  · 顶部标题变「选择对话」，右侧 ×
  · 底部出现 [置顶] [删除] 两个操作
```

改 `ChatSidebar.kt`：加 `selectionMode` / `selectedIds` 状态，行首复选框，
底部操作条（用统一后的 miuix 按钮），批量置顶/删除走现有 `ChatPersistence`。

**注意**：保留现有的 `justDeletedId` 守卫与「不 REPLACE 当前会话」的教训，
不要在多选删除里把那个 CASCADE 坑再踩一次。

**验收**：模拟器上多选 3 条 → 批量删除 → 直读数据库确认只剩预期的行。

### 阶段 4：B 项 —— 重进后恢复用量 + 修内容消失

**4.1 用量恢复**：在 `restoreIfNeeded()` 恢复完历史后调一次 `publishUsage`，
用恢复出来的历史重算 `used`。

**4.2 内容消失**：全仓搜索 `ensureSession` 的所有调用点，逐个核查是否可能在
「会话已有消息」时被调用（那就会 REPLACE → CASCADE 删消息）。有则改成
「先查存在性，仅不存在时插入」或改用 UPDATE。

**验收**：模拟器上发几条消息 → 记下 token 数 → 杀掉 App 重进 → 确认数字恢复、
消息仍在（直读数据库比对行数）。

### 阶段 5：F 项 —— UI 借鉴 NexioSchedule

**明确范围**（用户要求）：
- ✅ G2 圆角（连续曲率圆角）
- ✅ 悬浮控件背景（返回按钮等）
- ✅ 页面排版、控件样式
- ✅ 拖动动画、展开动画、滑动选择动画
- ❌ **底部导航栏 / 标签栏不改**

落地清单：
1. 圆角体系：把现有 `RoundedCornerShape` 的硬编码值收敛成一组 token，
   关键容器用 G2 连续曲率（NexioSchedule 的 `FusionPairShape` / `LiquidBottomBarShape` 有实现思路）。
2. 悬浮控件：返回按钮、置底按钮等改成带描边/内阴影的悬浮胶囊。
3. 动效：接入 `DampedDragAnimation`（拖动）与 `MorphTransition`（展开）。
4. 滑动选择：长按拖动选择（侧边栏多选、消息选择可用）。

**验收**：每个落地项在模拟器上截图对比改前/改后。

### 阶段 6：E 项 —— 终端（阻塞，需用户日志）

在没有真机日志之前**不动手改**。可以做的准备：
- 读 DIY Sandbox 参考实现（`C:\creat-agent\ref-diy-sandbox`）的 PTY 用法，
  对比 Praxis 的 `Pty.kt`；
- 核查 `loginArgs` 起的 bash 是否真的需要 tty（`stty` 来自 profile）。

**如果用户能提供日志**，优先做这一项 —— 它是用户最直接的痛点。

### 阶段 7：一次性构建 + 发布 + 结算报告

所有项改完、模拟器验证通过后：
1. 全仓静态检查（`check_kt_files.py` / `check_kt_literals.py` / `test_kt_lexer.py`）；
2. **一次性** commit + push（不逐条 push，避免触发风控）；
3. 等 CI 绿；
4. 触发一次 APK 构建，下载、发布到 `https://sxd.sxdshh.kdns.fr/apk/`；
5. 写结算报告：改了什么、怎么验的、还有什么没验。

## 顺序理由

0 → 1 → 2 → 3 → 4 是**强依赖**：菜单动画（1）是加号菜单（2）的前提；
G2 圆角体系（0.1）是多选操作条（3）与所有新控件的前提。

F（阶段 5）放在功能改完之后，因为它要动全局视觉 token —— 先改功能再统一视觉，
比先统一视觉再塞功能少返工。

E（阶段 6）独立，可以随时插入 —— 取决于能否拿到真机日志。

## 需要用户确认/提供

1. **E 项真机日志**（最重要）：装上当前 APK，点「启动容器」，然后
   `adb logcat -s ProotTermSession PraxisPty > log.txt`，或截图终端页完整报错。
2. C 项的取舍：附件菜单要「带图标的网格但视觉对齐下拉」还是「纯 miuix 下拉列表」？
   我倾向前者（图标对附件功能很重要），但想确认。
3. 「部分内容消失」具体指什么：是消息气泡消失，还是侧边栏列表条目消失？
   这决定我查哪条路径。
## 阶段 0.1 的调查结果（已完成）

### NexioSchedule 的技术栈已摸清

关键结论：**它的动效能力几乎全部来自 miuix 0.9.4 自带 API，不是自研**。

```
dragSelectState        -> top.yukonga.miuix.kmp.interactive.DropdownPanelDragSelectState
dropdownPanelDragSelect-> top.yukonga.miuix.kmp.interactive.dropdownPanelDragSelect
dropdownPanelEntry     -> top.yukonga.miuix.kmp.interactive.dropdownPanelEntry  （滑动选择）
OverlayDropdownPopup   -> top.yukonga.miuix.kmp.popup.OverlayDropdownPopup     （形变动画）
```

Praxis 是同一个 miuix 0.9.4，这些**全都能直接用**。

NexioSchedule 自己写的只有 `DampedDragAnimation.kt`（160 行，纯动画算法，
无平台依赖，可直接移植）与若干 shape 实现。

### 这改变了实施策略

原以为要「移植 NexioSchedule 的动效体系」，实际是：

| 原计划 | 实际做法 |
|---|---|
| 移植 `OverlayDropdownMenu`（24KB）| 只把 Praxis 的 `MiuixSmallTitleDropDown` 从 `OverlayListPopup` 换成 `OverlayDropdownPopup`，几十行 |
| 自研滑动选择 | 用 miuix 的 `dropdownPanelEntry` / `dropdownPanelDragSelect` |
| 移植拖动动画 | 移植 `DampedDragAnimation`（160 行，纯算法）|

所以 C/D/F 三项的工作量比预估小得多，风险也低（都是官方 API，同版本已验证存在）。

## 修正后的优先级

1. **E 终端**（最高，用户最痛）—— 阻塞在真机日志，但可以先读 DIY Sandbox 参考实现做静态对比
2. **D 菜单动画**（改几十行就有明显效果）
3. **C 加号菜单统一样式**
4. **A 侧边栏多选**
5. **B 重进丢数据 + 用量清零**
6. **F 全局视觉统一**
7. 静态检查 → 一次性提交 → 构建 → 发布 → 结算报告

## 我现在就要开始的

E 项虽然缺真机日志，但我可以做**静态对比**：读 `ref-diy-sandbox` 的 PTY 实现，
对照 Praxis 的 `Pty.kt` 与 `loginArgs`，找出 PTY 路径上可疑的差异。
这个不需要真机，且如果找到明确 bug 就能直接修。
## P5b 真机验证（2026-11-03 03:17）

### 证据：模型下拉弹出，样式已是 OverlayDropdownPopup

点底部「deepseek-v4.1-flash」后弹出模型列表，可见：

  - 每个选项**右侧有单选框圆点** —— 这是 `OverlayDropdownPopup` 的样式，
    旧的 `OverlayListPopup` 没有这个；
  - 选中项（deepseek-v4.1-flash）高亮为橙色；
  - 10 个选项全部来自新包装的 `DropdownEntry`（每项 onClick 负责选中并收起）。

说明组件替换生效、数据通路正确。

### 没验证到的：动画本身

截图是**静态的**，证明不了「有弹出/关闭动画」。要证明需要录屏或连拍帧。
而真机的 `uiautomator` 本轮全程 `could not get idle state`，无法自动驱动
「点开→关闭」并连续取帧。

**所以「动画已补上」目前只有代码依据（换成了带逐项动画的组件），
没有运行时视觉证据。** 这一条要如实标为未验证。

### 一个必须记下的错误（同一个错犯了两次）

第一次：在计划里写「两项目 miuix 版本相同，OverlayDropdownPopup API 直接可用」。
第二次：在实现里照着 NexioSchedule 的调用写 `collapseSize` / `collapseContent`，
         CI #109 直接编译失败。

真相：NexioSchedule **vendored（fork）了整个 miuix 模块**，
它调的是 `miuix/src/commonMain/.../popup/LiquidOverlayDropdownPopup.kt`（292 行），
参数比官方多 4 个。官方 0.9.4（javap 从 Maven Central aar 核实）只有 8 个参数：

  entries / show / onDismiss / onDismissFinished / maxHeight
  dropdownColors / renderInRootScaffold / collapseOnSelection

**教训：抄第三方项目的 API 调用前，先 javap 一遍自己依赖的那个 aar。
版本号相同不代表 API 相同。**

### 「胶囊长成面板」的形变仍做不到

那需要 `collapseSize` / `collapseContent`，官方没有。想要只能：
  (a) fork miuix（像 NexioSchedule 那样），或
  (b) 自己手写形变插值。
归到 P6 范围，本轮没有硬塞。
## P2 真机验证通过（2026-11-03 04:51）

### 证据：像素亮度采样（同一位置、同一条会话）

```
                抽屉关闭    抽屉打开      结论
v112（修复前）    25.1        130.7     变亮 5.2 倍 —— 提亮（bug）
v123（修复后）    25.1         12.3     变暗 51% —— 压暗（正确）
```

采样点：右侧主内容区 (1150,600) / (1200,900) / (1150,1300) / (1220,1600)，
取 `0.299R + 0.587G + 0.114B` 平均。

### 根因（一行代码）

```kotlin
// 修复前
.background(MiuixTheme.colorScheme.onSurface.copy(alpha = SIDEBAR_DIM_ALPHA))
```

`onSurface` 的语义是「表面上的**前景**色」——**深色主题下它是浅色**。
浅色半透明叠上去 = 提亮。与「弹出层压暗背景」的层级语义正好相反。

改成 `Color.Black.copy(alpha = ...)`，与全仓其它遮罩口径一致
（FloatingBottomBar / InnerShadow / LiquidToggle 都用 Black）。

## P3 真机验证通过（2026-11-03 04:53-04:57）

### 证据一：多选模式外观（对齐 DeepSeek）

长按会话后：

  - 标题从「对话历史」变成 **「选择对话」** ✓
  - 每条左侧出现**圆形复选框**：已选 = 实心带勾，未选 = 空心描边 ✓
  - 底部出现操作条：**[全选] / [置顶 N] / [删除 N] / [完成]** ✓
  - 行内图钉按钮隐藏（避免与批量置顶语义打架）✓

### 证据二：全选与批量删除（直读数据库）

```
点「全选」  -> 按钮变「取消全选 / 置顶 2 / 删除 2」   ✓
点「删除 2」-> App 存活（pid 13214）               ✓

删除后数据库：
  sessions:  s1793653106785  新对话   （App 自动兜底创建）
  messages:  （空）
```

两条旧会话（`main` 2 条消息、`s1793628034799` 47 条消息）都被正确删除，
用户可见行为正确。

### ⚠️ 一个尚未验证的红线

目标里明确要求：「多选删除绝不能走 ensureSession/@Insert(REPLACE)... 
历史踩过两次」并用「批量删除后消息未被误删」作验收。

**代码层面**：批量删除只调 `chatPersistence.deleteSession(id)`，
没有走 ensureSession / upsert（见 ChatSidebar 的 onDelete 实现，注释里也写死了这条红线）。

**但运行时层面我还没证明**「删 A 不会连带删 B 的消息」——
上面那次是全选全删，两条都被有意删掉了，无法区分
「正确删除」与「CASCADE 误删」。

要做这个区分，需要一个「只删一部分、另一部分消息必须完好」的实验。
当前数据库已被清空，下一轮需要先造数据再验。**这一条留待补验。**
## 本轮（第 6 轮）完成与受阻

### ✅ P3 级联安全红线：已实证（这是本轮最重要的产出）

上一轮用的是「全选全删」，无法区分「正确删除」与「CASCADE 误删」。
本轮造了精确测试数据后只删一条：

```
造数（root 拉库 → PC 上 sqlite3 插入 → push 回设备）：
  seedA 待删  3 条消息
  seedB 保留  5 条消息
  seedC 保留  7 条消息（且 pinned=1）

真机操作：长按 seedA → 多选 → 点「删除 1」

结果（直读数据库）：
  sessions:  s1793653106785(当前对话) / seedB / seedC
  messages:  seedB 5 条 ✓   seedC 7 条 ✓
  seedA 已删除 ✓        App 存活（pid 18042）✓
```

**PASS**：批量删除只影响目标会话，其它会话消息未被级联删除。
目标里那条「多选删除绝不能走 ensureSession/@Insert(REPLACE)」的红线守住了。

### ✅ P3 操作条样式：代码已改，真机未验（受阻）

真机截图显示多选操作条是**四块铺满宽度的刺眼红块**，与 DeepSeek 不符。
根因：用了 `MiuixTextActionButton`（内部 fillMaxWidth + 写死 errorContainer）。

改法：新增 `SidebarActionButton(text, onClick, modifier, danger)`，
宽度由调用方控制、语气由 danger 决定。操作条改成一行横排。

**CI #124 / preview #26 全绿，但装不上真机**（见下）。

### ❌ 受阻：无线 ADB 无法稳定传输 88MB APK

本轮装了 5 次全部失败，三种方式都试过：

```
adb install              -> failed（无错误信息）
adb push + pm install    -> adb: error: 30534-byte write failed: No error
adb install --no-streaming -> adb: error: 65544-byte write failed: No error
```

已排除的原因：
  - APK 本身没问题（zip 完整性 OK、1265 条目、88.1MB，与能装上的 v123 同尺寸）
  - /data/local/tmp 可写（touch 测试通过）
  - 磁盘有空间（此前 df 显示 19G 可用）
  - 不是 PackageManager 拒绝（日志里没有对应的 install 记录）

**结论：无线 ADB 传输 88MB 文件时数据丢失**。之前几次成功属于侥幸，
本轮彻底不稳定（连 push 都报写失败）。

### 这也解释了之前几次「装上了」的差别

v123 在 04:50 装成功；v125 从 05:26 起连试 5 次全失败。
差别不是代码，而是 ADB 通道状态随时间恶化。

### 下一步建议

1. **USB 连接**：最可靠。用户在家时可以插线，`adb devices` 会显示 USB 序列号。
2. 或重启手机的无线调试（重新配对）后再试。
3. 或把 APK 放到设备可访问的位置（如 `/sdcard/Download`）再用设备上的文件管理器安装。

**这属于测试基础设施问题，不是代码缺陷。**
## P3 样式修复 + 级联安全：真机验证通过（2026-11-03 05:49-05:52，v125）

### 突破：解决了无线 ADB 传不了大文件的问题

上一轮 5 次安装全失败（`adb install` / `adb push` / `--no-streaming` 都报 write failed）。

本轮改用**分块推送 + 设备端合并**：

```
APK 88.1MB -> 切成 3 段（30MB + 30MB + 29.5MB）
逐段 adb push -> 全部成功（每段 20-80 秒）
设备端 cat p0 p1 p2 > v125.apk -> 92413669 字节（与源一致）
pm install -r -d -> Success
```

**单次大文件传输会丢数据，分段就稳定。** 这是可复用的经验。

### 证据一：操作条样式已修复

| | 修复前（v123） | 修复后（v125） |
|---|---|---|
| 形态 | 四块铺满宽度的刺眼红条 | 紧凑横排，贴底 |
| 配色 | 全部红色 | 只有「删除」红色，其余中性 |
| 布局 | 竖排占满整屏宽 | `[全选] [置顶 N] [删除 N] [完成]` 一行 |

### 证据二：批量删除 + 级联安全（最终版本重测）

造数（tA 3 条 / tB 5 条 / tC 7 条）→ 长按 tA → 点「删除 1」：

```
sessions:  s1793653106785(当前对话) / tB / tC
messages:  tB 5 条 ✓   tC 7 条 ✓
tA 已删除 ✓        App 存活 pid 23653 ✓
```

**PASS**：批量删除只影响目标会话。目标里「绝不能走 ensureSession/@Insert(REPLACE)」
的红线在最终版本上再次确认。

### 经验：真机 UI 自动化可行了

之前 `uiautomator dump` 一直报 `could not get idle state`。
**加 `--compressed` 参数就能绕过 idle 等待**：

```
uiautomator dump --compressed /data/local/tmp/x.xml
```

本轮的按钮坐标（全选 140,2676 / 删除 688,2676 等）就是这么拿到的。
之前靠猜坐标点偏了好几次。
