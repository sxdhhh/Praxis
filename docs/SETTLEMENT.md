# Praxis 改造结算报告

> 生成时间：2026-11-03
> 分支：`master` → 远端 `main`
> 范围：P1 / P2 / P3 / P4 / P5a / P5b / P6 / P7 + 终端 proot 三问题

---

## 一、证据等级说明

本报告把每一项分成三档，**不把「CI 绿」当作「已解决」**：

| 等级 | 含义 |
|---|---|
| **已实证** | 有真机/数据库/像素级证据，可直接引用 |
| **仅 CI** | 编译通过 + 单测/护栏通过，**无运行时证据** |
| **未做/未验** | 代码没写，或写了但完全没验证 |

---

## 二、逐项状态

### P1 终端复制粘贴 —— 已实证（除帧耗时）

**用户原话**：「终端的复制粘贴应该用安卓自带的自写就太难用了」

**改法**：删掉自研 cell 坐标选择模型（`TerminalSelection` / `TerminalSelectionBar` /
`extractSelectionText` 及 11 条单测，共 -828 行），改为
「整屏拼一条 `AnnotatedString` → 单个 `Text` → 套 `SelectionContainer`」。

**已实证**（真机截图）：长按出现**系统选择手柄 + 系统浮动菜单**（翻译/复制/全选/朗读/⋮），
自研的选择条已消失。

**未验证**：帧耗时判据。目标要求「填满 scrollback（约 1000 行）后滚动/输入 > 16ms
则退回折中方案」——**这项从未测量**。

原因：APK 只打 arm64-v8a，模拟器（x86_64）装不上；真机只在夜间可用。

**补充**：补了 `buildAnnotatedScreen` 的 7 条单测（此前该函数一条测试都没有，
而改动恰恰集中在这里，属于测试覆盖倒退）。

---

### P2 侧边栏压暗 —— 已实证（像素级）

**用户原话**：「侧边栏是压暗背景而不是提亮背景」

**根因**（一行）：
```kotlin
.background(MiuixTheme.colorScheme.onSurface.copy(alpha = SIDEBAR_DIM_ALPHA))
```
`onSurface` 的语义是「表面上的**前景**色」——**深色主题下它是浅色**，
浅色半透明叠上去 = **提亮**，与「弹出层压暗背景」的层级语义正好相反。

**已实证**（真机像素采样，同一位置）：

```
              抽屉关闭    抽屉打开
用 onSurface     25.1       130.7     ← 变亮 5.2 倍（bug）
用 Color.Black   25.1        12.3     ← 变暗 51%（正确）
```

**护栏**：`sidebarScrim_darkensInsteadOfLightening`（新增）

---

### P3 侧边栏多选 —— 已实证（含级联安全）

**用户原话**：「侧边栏的管理应该像DeepSeek一样」

**改法**：
- `MiuixSidebar` 新增 `bottomBar` 槽（操作条必须在内容区**外面**，否则被列表滚走）
- `SidebarSessionRow` 新增 `selectionMode` / `checked`（自绘圆形复选框）
- `ChatSidebar` 新增多选状态、批量删除、批量置顶

**已实证**（真机）：
- 长按 → 标题变「选择对话」+ 圆形复选框（实心带勾 / 空心描边）+ 底部 `[全选][置顶 N][删除 N][完成]`
- 全选生效（按钮变「取消全选 / 置顶 2 / 删除 2」）
- 操作条样式从「四块铺满的刺眼红条」修成紧凑横排，仅删除用红色

**级联安全（用户红线）已实证**：

```
造数  seedA(3 条) / seedB(5 条) / seedC(7 条)
操作  长按 seedA → 多选 → 删除 1
结果  seedA 已删除 ✓
      seedB 消息完好 5 条 ✓
      seedC 消息完好 7 条 ✓
      App 存活 ✓
```

`ChatSidebar` 里 `upsert`/`ensureSession`/`REPLACE` 共 11 处，其中 9 处在注释，
2 处在代码，都是「新建会话」场景（库里本来没有那行，REPLACE 的 DELETE 删不到东西）。

**未验证**：批量置顶后的**顺序**（只验了置顶不报错，没验排序结果）。
代码审计确认 `onPin` 走 `setSessionPinned`（UPDATE，不碰 upsert）。

---

### P4 重进丢内容 + 命中率清零 —— 部分已实证

**用户原话**：「重进软件可能会出现部分内容消失和命中率清零」

**已实证**：真机杀进程重进后，进度条从 `0 ———— ?` 恢复成 `3.8k ———— 1.0M`
（`used` 与 `limit` 都回来了）。

**根因**：`ContextUsage.used` 只由 `publishUsage()` 写入，三个调用点全在发送路径，
`restoreIfNeeded()` 一次都不调 → `used` 停在 0。

**修法**：`restored = true` 之后补一次 `publishUsage(buildPreparedMessages(config), config)`
（用 `runCatching` 包住——算不出用量不该让整条恢复流程失败）。

**代码审计确认存在但未运行时验证**：
- 空正文 assistant 占位行在恢复时被丢弃（否则渲染成残缺气泡）
- 38 条裸 `tool` 消息曾污染 history 导致上游 400（已修）
- `compacted.coveredCount > history.size` 有越界防护

**关于「部分内容消失」**：数据库重启前后完全一致（`main`=2 行、`s1793628034799`=47 行），
**数据没丢**，是界面没加载出来。具体是哪些内容消失，我在多轮里问过但没得到确认，
**这一项仍未定位到根因**。

---

### P5a 加号菜单 —— 已实证

**用户原话**：「加号的菜单应该和模型和功能菜单的样式一致而不是最后一张图片的那种」

**改法**：`AttachmentPanel` 从自建 `MiuixAnimatedPopup + Card + 4 列九宫格`（305 行）
改成 `OverlayDropdownPopup + DropdownItem`（168 行，**-238 行**）。
图标走 `DropdownItem` 的 `icon` slot（已用 javap 核实官方 aar 有这个参数）。

**已实证**（真机截图）：8 项纵向排列、每项带图标，
容器与模型下拉同圆角同贴底位置；几何实测 `x=[726,885]`，与模型下拉同右边界。

---

### P5b 菜单动画 —— 仅 CI

**用户原话**：「只有加号的那个菜单有弹窗动画，模型和功能菜单没有弹出和关闭的动画」

**改法**：`MiuixSmallTitleDropDown` 从 `OverlayListPopup` 换成 `OverlayDropdownPopup`。

**已实证**：真机点开模型下拉，弹出 10 项、**每项右侧有单选圆点**、选中项高亮——
单选圆点是 `OverlayDropdownPopup` 的特征样式（旧组件没有），证明组件替换生效。

**未验证：动画本身**。静态截图证明不了「有弹出/关闭动画」，需要录屏或连拍帧。
**「动画已补上」目前只有代码依据，没有运行时视觉证据。**

---

### P6 全局视觉 —— 部分完成，未验证

**已完成**：
1. 新建 `PraxisRadius` 圆角令牌（与 `PraxisMotion` 同构同位置），首批收敛 **10/67 处**
2. 新增 `FloatingIconButton`，`AppScaffold` 的返回键从裸 `IconButton` 改成有底版本
   （圆形底 + 描边 + 内阴影）
3. 两条护栏

**未做**：页面排版、拖动/展开动画接入新场景。

**关键调查结论（纠正了我自己早先的错误判断）**：

- 我早先说「NexioSchedule 的圆角全来自 miuix squircleSurface」——**这是错的**。
  它 fork 了 miuix，用 `CollapsibleTopAppBar` + 自己的 capsule 几何实现，
  没有显式 squircle 调用。
- **不能全局换 squircle**：`ToolCallCard.kt:87` 有实测结论——
  `squircleSurface` 会为每个元素额外开离屏图层并跑 AGSL shader，
  列表里连续 5~10 张时滚动掉帧明显（用户原话「翻文字很丝滑，滑到工具的时候就变卡了」）。
- `DampedDragAnimation` **早就存在**（225 行），目标里写「需移植」但实际已移植过。

**未验证**：真机截图对比。全部只有 CI 证据。

---

### P7 终端可用性 + 用户新报的 proot 三问题

**用户原话**：
> proot 应该持久化，ai 给我说它每次脚本都会起一个 proot 会话才行，
> 还有终端连接的应该是内置的 liunx 系统而不是 /bin/sh
> 更高效的是 ai 给我说 `stty: 'standard input': Inappropriate ioctl for device`

#### 已修（仅 CI）：`/bin/sh` 与 `stty`

根因是 `ProotTerminalSessionPort.create()` 里的
`sessionArgs(env, listOf("/bin/sh", "-i"))`：

- 硬编码 `/bin/sh` **绕过**了 `loginShell(env)` 的探测（容器里装了 bash 也用不上）
- `-i` 强制交互模式，交互 shell 会 `tcgetattr(stdin)`；
  落到管道降级分支时 stdin 不是 tty，于是刷 `stty: Inappropriate ioctl`

修法：新增 `loginTail(env)` = `loginShell(env) + "-l"`，`-l` 自己判断要不要交互。

**状态**：CI #131 通过。**真机未验证**（验证脚本 `tools/verify_device_terminal.py` 已就绪）。

#### 已查清根因，未修：proot 不持久化

系统有**两条并存路径**：

| 路径 | 实现 | proot 生命周期 | 状态保留 |
|---|---|---|---|
| `linux_exec` | `ProotEnvPort.exec()` | **每命令新起** | ❌ 全丢 |
| 终端会话 7 工具 | `ProotTerminalSessionPort.execute()` | **常驻 PTY** | ✅ 保留 |

**不是持久化实现坏了，是模型选了 `linux_exec` 这个无状态工具**——
它名字更直白且描述里没提「状态不保留」，模型优先用它。

**已做**：给 `linux_exec` 的 description 补上「⚠️ 无状态」说明并指明替代工具（风险最小）。

**未做（需要产品决策）**：让 `linux_exec` 内部改用常驻会话 / 移除或降权它。
两项都会改变模型可见的工具清单或已有语义。

---

## 三、过程教训（自曝问题）

本轮我犯了 **7 次同类错误**，全部是「照印象写 API，没核实」：

| # | 错误 | 真相 |
|---|---|---|
| 1 | `collapseSize`/`collapseContent` | 照 NexioSchedule 的 **fork 版**写，官方 miuix 0.9.4 没有 |
| 2 | `renderInRootScaffold` | 官方参数**无默认值**，必须显式传 |
| 3 | `MiuixIcons.Regular.Check` | 下载 aar 核实：那个包里**没有** Check 图标 |
| 4 | Canvas 里读 `MiuixTheme` | draw lambda 不是 Composable 上下文 |
| 5 | 漏 `Row` import | 补 import 时没核对完整清单 |
| 6 | `MiuixIcons.Regular.Back`「不存在」 | 改成裸 `Back`——**改错了** |
| 7 | 两个 import 缺一不可 | `extended.Back` 是扩展属性，负责把 `Back` 挂到 `MiuixIcons.Regular` 上 |

第 6、7 次尤其值得记：**报错说「X 未解析」不等于「X 不存在」**，
也可能是它依赖的某个 import 缺失。正确做法是找到同项目里**已经编译通过的相同用法**照抄。

另外两次「以为改了其实没改」：
- 一次 `run_code` 第一行就抛错，后面的替换没执行，我没回读就继续往下走
- 一次 `build.yml` 锚点缩进写错，脚本没匹配到却打印「已加说明」

**结论：字符串替换后必须回读验证，不能信分支打印。**

---

## 四、交付物清单

### 代码（已推送，CI 全绿）
```
71b7c29  ci: 路径过滤说明
aed2eac  tools: 真机验证脚本
03a303d  P2 护栏
aa5acc2  P1 单测（7 条）
01805d8  docs: 模拟器限制
c47ddf6  linux_exec 无状态说明
18832db  终端 loginTail 修复
da54393  P6 icon import 修正
e9a1265  P6 悬浮返回按钮
d8b0c7e  P6 圆角令牌
f0a8d20  docs: P5 验证
034d42d  docs: P3 验证
c240339  P3 操作条样式
49152d4  P2 压暗
d60b4ab  P3 多选批量
86088df  P5a 加号菜单
7a4d506  P5b 菜单动画
dd2e1d5  P4 用量重算
```

### 文档
- `docs/DEVICE-FINDINGS.md`
- `docs/PLAN-next.md`
- `docs/VERIFICATION.md`
- `docs/TERMINAL-PROOT.md`（终端三问题的完整排查）
- `docs/EMULATOR-LIMITS.md`（模拟器装不了本 APK 的实证）

### 工具
- `tools/verify_device_terminal.py`

### 护栏测试（WiringGuardTest，共 38 条）
本轮新增 5 条：`bottomSheets_shareSameComponent`、`attachmentPanel_usesAnimatedDropdown`、
`cornerRadius_goesThroughToken`、`appScaffold_backButtonHasBackground`、
`sidebarScrim_darkensInsteadOfLightening`

---

## 五、阻塞与待办

### 阻塞：真机连续离线 12 小时

从 2026-11-03 06:45 起 `192.168.1.199:5555` 一直是 `offline`，至今未恢复。

### 阻塞：模拟器装不了本 APK

`INSTALL_FAILED_NO_MATCHING_ABIS` —— APK 只打 arm64-v8a，模拟器是 x86_64。
**所有终端相关功能（P1/P7）在模拟器上完全测不了。**

### 待办（设备恢复后，按此顺序）

1. `python tools/verify_device_terminal.py` —— 终端是否连上容器 bash、stty 是否消失
2. P6 悬浮返回按钮截图对比
3. P1 帧耗时（scrollback 1000 行 + `dumpsys gfxinfo`）
4. P3 批量置顶顺序
5. P4 气泡加载 / coveredCount 运行时确认
6. 全仓静态检查 → 一次性 commit → CI 绿 → 构建 APK → 发布 → 本报告补完
7. **发布**：`https://sxd.sxdshh.kdns.fr/apk/`（当前最新仍是旧 commit `2f0339a`，
   `e666c5b` 之后的所有改动都还没发布）

### 需要用户决策

- `linux_exec` 是否降权/移除（影响模型可见工具清单）
- 「部分内容消失」具体指什么（问过多轮未得到确认）

---

## 六、一句话总结

**代码侧能做的已经做完，CI 全绿，但「已实证」只覆盖 8 项中的 4.5 项。**

发布被两件事挡住：真机离线、模拟器装不了 APK。
在没有真机验证的情况下我不会声称任何一项「完成」——尤其是终端那两个修复，
它们恰恰是最需要跑起来看的。

## 七、本轮（第 19 轮）新增：预构建就绪

真机仍离线，但把**发布前的准备工作全部做完**，设备一上线即可直接验证 + 发布。

### 全仓静态检查：通过

```
Kotlin 文件总数              374
括号/括号/方括号不平衡        0
字面量检查                   374 个文件，0 个有问题
词法分析器自测               23/23 通过
```

（结构检查改为在进程内直接调 `kt_lexer.strip_noncode`，
避免逐文件启动解释器 —— 374 次进程启动会超时，
而一次性传 374 个路径会超出 Windows 命令行长度上限。）

### APK 已构建并下载

```
commit    e5019ef（最新）
CI        #136 build success
大小      88.1 MB
md5       097c86f5aa5f24612d385e0af89d0a79
zip 完整性 OK
ABI       ['arm64-v8a']
路径      C:\creat-agent\apk-in\v136\app-debug.apk
```

**这个包包含全部改动**（P1/P2/P3/P4/P5a/P5b/P6/终端 loginTail），
真机一上线就能直接装，不用再等十几分钟构建。

### 真机安装注意

无线 ADB 传 88MB 大文件会丢数据（实测：`adb install` / `adb push` /
`--no-streaming` 三种方式都报 write failed）。
**必须分块推送再合并**：

```powershell
# 切成 3 段（30MB/30MB/29.5MB）
adb push part0.bin /data/local/tmp/p0.bin
adb push part1.bin /data/local/tmp/p1.bin
adb push part2.bin /data/local/tmp/p2.bin
# 设备端合并
adb shell 'cat /data/local/tmp/p0.bin /data/local/tmp/p1.bin /data/local/tmp/p2.bin > /data/local/tmp/v136.apk'
adb shell pm install -r -d /data/local/tmp/v136.apk
```
