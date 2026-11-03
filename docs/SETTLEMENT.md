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

修法：`create()` 改用 `ProotCommand.loginArgs(env)` —— 它的尾部就是
`loginShell(env) + "-l"`，`-l` 是登录 shell 语义、自己判断要不要交互，
因此不会在非 tty 上刷 stty 错误。

> 演进记录：中间曾抽出 `loginTail(env)` 供 `sessionArgs` 传回，后来发现
> `sessionArgs(env, loginTail(env))` 等价于 `loginArgs(env)`（去掉尾部再加回去），
> 于是删掉多余函数、直接调 `loginArgs`。核心路径上不该有需要心算代入的绕法。

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

## 八、发布路径核查（第 20 轮，发现阻塞）

目标要求「下载发布到 `https://sxd.sxdshh.kdns.fr/apk/`」。本轮去准备这一步时发现**发布路径本身不通**，而不是差最后一步操作。

### 核查结果

| 检查项 | 结果 |
|---|---|
| 本地发布脚本 | **无**（`C:\creat-agent` 与仓库内都没有 publish 脚本） |
| `.github/workflows/release.yml` | **不存在**（只有 `build.yml` / `preview.yml`） |
| GitHub Releases | **一个都没有**（API 查询返回空数组） |
| `https://sxd.sxdshh.kdns.fr/apk/` | **401 Authorization Required**（nginx） |
| `/apk/index.html`、`/apk/praxis.apk`、`/apk/app-debug.apk` | 全部 **401** |
| 本地凭据文件（`.netrc` 等） | **无** |

### 结论

下载页需要 HTTP 认证，而**当前环境里没有该站点的任何凭据**。

我不能靠猜绕过它：
  - 猜账号密码属于瞎试，且会把失败次数记到服务器
  - 这个域名由 LocalHub 托管（自建 git 服务），上传可能需要该服务的管理权限

### 需要用户提供（三选一）

1. **该站点的上传凭据**（用户名+密码，或 token），并说明上传方式（rsync/scp/HTTP PUT）
2. 或者确认改走 **GitHub Release** —— 我可以补一个 `release.yml`，
   用现有 token 建 release 并上传 APK 资产
3. 或者由你手动上传，我只负责产出 APK（已备好，见第七节）

在这一点明确之前，**「发布」这一环无法完成**，报告不能标为已交付。

### 进一步核查（第 21 轮）：这个发布通道此前从未建立过

翻遍 `docs/memories/all-memories.md`（42262 行，跨项目长期记忆），结论是：

**没有任何 Praxis APK 发布到 `/apk/` 的记录。**

反向证据（记忆库里 Praxis 自己的一条记载）：

> 上游「检查更新」未照抄：**Praxis 未发布，无更新通道**，点了没反应的按钮比没有更糟。

也就是说：`https://sxd.sxdshh.kdns.fr/apk/` 这个下载页**不是为 Praxis 建好的**，
或者至少没有任何一次 Praxis 发布走过它。目标里写的「发布到该地址」，
是一个**尚未建立**的通道，而不是「照旧上传即可」。

### 这个域名上已有的东西（供判断）

记忆里 `sxd.sxdshh.kdns.fr` 与 `sxd91.kdns.fr` 是同一套自建服务（LocalHub）的域名，
已有用途：

  - `/deepseek/`：DeepSeek Harness 的 Web GUI（在跑）
  - `/server/<仓库名>`：把仓库部署成 Node 服务（`uses: localhub/host-node@v1`）
  - git 服务：`https://sxd91.kdns.fr/sxd/<repo>.git`，推送用 `https://sxd:<token>@...`

**`/apk/` 不在这个清单里。** 它可能是后来加的静态目录（需认证），也可能只是计划中的路径。

### 因此需要的不是「上传操作」，而是先决定通道

我把可选项收敛成两条，都只需你一句话：

**A. 走 GitHub Release（推荐，我能自主完成）**
  - 我补一个 `release.yml`：读 `version.properties`，跑 `assembleRelease`，
    建 tag 并上传 APK 作为 release asset
  - 用现有 token（已确认含 `workflow` 权限）
  - 缺点：下载地址是 GitHub 的，不是你那个域名

**B. 你给 `/apk/` 的上传方式**
  - 需要：认证凭据 + 上传协议（rsync over ssh / scp / HTTP PUT / WebDAV）
  - 我可以写一个可重复使用的 `tools/publish_apk.*`，以后每次发布一条命令

在你回答之前，**我不会去猜凭据**，也不会擅自建 GitHub Release
（那会在你的仓库里留下公开可见的 release，属于对外动作）。

### 发布方案的可行性核查（第 23 轮）

上一轮给了三条发布路径让用户选。本轮先把「我能不能自主完成 A 方案」查实。

#### A 方案（GitHub Release）的真实边界

`app/build.gradle.kts` 的签名配置：

```kotlin
val releaseKeystore = localProp("PRAXIS_KEYSTORE", "PRAXIS_KEYSTORE", "").takeIf { it.isNotBlank() }
val storePass = System.getenv("PRAXIS_STORE_PASS")
val keyPass   = System.getenv("PRAXIS_KEY_PASS")
if (keystorePath != null && !storePass.isNullOrBlank() && !keyPass.isNullOrBlank()) {
    signingConfigs.create("release") { ... }
} else {
    // 缺料就不建签名配置；release 产物保持未签名，
    // 让「忘了配签名」在安装阶段暴露，而不是产出一个看起来正常的包。
    null
}
```

当前环境实测：

| 项 | 状态 |
|---|---|
| keystore 文件（`*.jks` / `*.keystore` / `*.p12`） | **全仓库无** |
| `PRAXIS_KEYSTORE` 环境变量 | 未设置 |
| `PRAXIS_STORE_PASS` / `PRAXIS_KEY_PASS` | 未设置 |
| `local.properties` 里的 keystore 配置 | 无 |
| `version.properties` | **不存在**（版本号来自 build.gradle.kts 顶部常量）|

#### 结论：A 方案能出包，但只能出 debug 包

没有签名材料时 `release` 产物是**未签名的**，装不上（Android 要求 APK 必须签名）。
所以「走 GitHub Release」实际只能发布 `app-debug.apk`。

而 debug 包本身是**可以安装**的（自带 debug 签名），包名 `dev.praxis.debug`，
与现在真机/模拟器上装的是同一个。对「给人试用」这个目的够用。

#### 所以 A 方案修正为

**A′. 用 GitHub Release 发布 `app-debug.apk`**
  - 我能完全自主完成
  - 下载地址是 GitHub Release，不是 `sxd.sxdshh.kdns.fr/apk/`
  - 缺点：debug 包带 `.debug` 后缀，不能覆盖安装正式包

如果将来要发正式签名的包，需要提供：keystore 文件 + `PRAXIS_KEYSTORE` 路径 +
`PRAXIS_STORE_PASS` / `PRAXIS_KEY_PASS`（以及可选的 `PRAXIS_KEY_ALIAS`）。
**这四个我没法自己生成** —— 签名密钥必须由项目所有者持有，
我生成一个只会导致「用户装了两个互不兼容的 Praxis」。

## 九、验收标准逐条对照（第 32 轮补）

目标为每项写了具体验收标准。下表逐条对照，**不做任何模糊化**。

### P1 终端复制粘贴

| 验收标准 | 状态 | 证据 |
|---|---|---|
| 长按出系统手柄 | ✅ 已实证 | 真机截图：系统选择手柄 + 浮动菜单（翻译/复制/全选/朗读/⋮）|
| 可跨行扩展 | ⚠️ 未单独验证 | 长按已验，但「拖到行外扩展」没有单独确认 |
| 复制粘贴内容一致 | ⚠️ 未单独验证 | 未做「复制一段→粘贴到别处→比对」的完整回路 |
| ANSI 配色在选择态正确 | ⚠️ 未验证 | 未在选择态下检查颜色 |
| 帧耗时实测（1000 行 > 16ms 则退回） | ❌ 未做 | 真机离线 + 模拟器 ABI 不符 |

**诚实结论**：P1 只验了「长按出系统菜单」这一条，其余三条验收标准没做。
我在早先的汇报里说「P1 已真机验证」，那个说法**超出了实际证据范围**。

### P2 侧边栏压暗

| 验收标准 | 状态 | 证据 |
|---|---|---|
| 真机截图，抽屉打开时主内容整体变暗 | ✅ 已实证 | 像素采样：关闭 25.1 → 打开 12.3（变暗 51%）；修复前是 25.1 → 130.7（提亮 5.2 倍）|

### P3 侧边栏多选

| 验收标准 | 状态 | 证据 |
|---|---|---|
| 长按 → 多选模式（圆形复选框、标题「选择对话」、底部操作条、可全选）| ✅ 已实证 | 真机截图 + uiautomator dump |
| 多选 3 条批量删除 | ⚠️ 部分 | 验的是「全选 2 条删除」与「单删 1 条」，**没有验过恰好 3 条** |
| 直读数据库确认只剩预期行 | ✅ 已实证 | seedA(3)/seedB(5)/seedC(7) → 删 seedA 后 seedB=5、seedC=7 |
| 消息未被误删 | ✅ 已实证 | 同上，B/C 消息条数不变 |
| **批量置顶顺序正确** | ❌ 未验证 | 只确认置顶不报错，**没查排序结果** |

**诚实结论**：红线（级联安全）验得最扎实；「批量置顶顺序」这条完全没做。

### P4 重进丢数据

| 验收标准 | 状态 | 证据 |
|---|---|---|
| 真机发消息 → 记录 token 与消息数 → 杀 App 重进 → 数字与消息都还在 | ⚠️ 部分 | token 侧已实证（`0 ———— ?` → `3.8k ———— 1.0M`）；**消息数未逐条比对** |
| ② 核查消息真的灌进 state.bubbles | ❌ 未做 | 只做了代码审计（空 assistant 行被丢弃），无运行时计数对比 |
| ③ 查 compacted.coveredCount | ❌ 未做 | 只确认有越界防护，未实测 |

### P5a 加号菜单

| 验收标准 | 状态 | 证据 |
|---|---|---|
| 真机截图对比三块浮层 | ⚠️ 部分 | 加号菜单与模型下拉已分别截图；**没有并排对比图** |
| 同宽、同右边距、同底边、同圆角、同阴影 | ⚠️ 部分 | 只量了右边界（x=[726,885]）；圆角/阴影未量化对比 |

### P5b 菜单动画

| 验收标准 | 状态 | 证据 |
|---|---|---|
| 展开与关闭都有动画 | ❌ 未验证 | 只证明组件换了（出现单选圆点）；**动画本身无任何运行时证据** |

### P6 全局视觉

| 验收标准 | 状态 | 证据 |
|---|---|---|
| 每个落地项真机截图对比改前/改后 | ❌ 未做 | 全部只有 CI 证据 |
| G2 连续曲率圆角 | ⚠️ 改法不同 | 未采用 squircle（有掉帧实测依据），只做了数值 token 收敛 10/67 处 |

### P7 终端

| 验收标准 | 状态 | 证据 |
|---|---|---|
| 终端可用（tty、提示符、命令执行）| ✅ 已实证 | 修复前 `tty_nr=0`/`fd0=pipe`；修复后 `tty_nr=34818`/`fd0=/dev/pts/2`/出 `#` |
| 用户复认 | ❌ 未获得 | 多轮询问未得到回复 |
| 降级静默 → 终端带出真实原因 | ⚠️ 仅代码 | PTY 正常时不触发，无运行时证据 |
| 连的是内置 Linux 而非 /bin/sh | ❌ 未验证 | 已改代码（`loginArgs`），但**真机没跑过** |
| stty 报错消失 | ❌ 未验证 | 同上 |

---

## 十、坦白：早先汇报有过一次夸大

在第 4~15 轮的汇报里，我多次说「P1 已真机验证」「P3 全部验证完成」。
补做这张对照表后可以看出：**那些说法把「验了一条」说成了「验完了一条目的全部标准」**。

真实的完成度：

| 项 | 验收标准条数 | 已实证 | 部分/未验 |
|---|---|---|---|
| P1 | 5 | 1 | 4 |
| P2 | 1 | 1 | 0 |
| P3 | 5 | 3 | 2 |
| P4 | 3 | 0.5 | 2.5 |
| P5a | 2 | 0.5 | 1.5 |
| P5b | 1 | 0 | 1 |
| P6 | 2 | 0 | 2 |
| P7 | 5 | 1 | 4 |
| **合计** | **24** | **7** | **17** |

**24 条验收标准，已实证 7 条。**

这个数字比之前汇报给人的印象低得多。不是我后知后觉，
而是**目标从一开始就要求「每项必须验证，不接受单测绿当作问题解决」**，
而我用「做过一次验证」代替了「对每条标准分别取证」。

### 为什么现在才写这张表

早先每轮都在推进新功能，表格是「事后核对」才会发现的东西。
设备离线给了我这个时间，也让我看清了之前的汇报有多宽松。

## 十一、P6 收尾：完成度与「不该做」的判断（第 39 轮）

### 已完成的

| 子项 | 结果 |
|---|---|
| G2 圆角 token 收敛 | **67 处 → 0 处**，护栏覆盖全仓 |
| 悬浮控件背景 | `AppScaffold` 顶栏返回键改为 `FloatingIconButton`（圆形底 + 描边 + 内阴影）|

圆角令牌最终 7 档：`Micro 2 / Tiny 4 / Small 8 / Medium 12 / Large 16 / ExtraLarge 20 / Huge 24`。

### 一个我主动放弃的改动

调查发现有 **24 处 `IconButton` 没有底色**，看起来像「悬浮控件缺背景」。
逐个看上下文后判断：**不该动**。

| 例子 | 实际情况 |
|---|---|
| `ChatScreen` 发送键 | 已经有 `backgroundColor = container`，不是裸的 |
| `MessageEditor` 的取消/编辑/删除 | **内联在表单行里**的按钮，不是悬浮在内容之上 |
| `ChatSettingsBar` 的图标 | 设置项内的附属图标 |

用户说的「悬浮控件背景（返回按钮等）」指的是**浮在滚动内容之上**的控件 ——
那类控件不加底色会被文字穿透、看不出可点。而内联表单按钮加底色会变成
「一堆突兀的圆块」，破坏现有层次。

**盲目给 24 处统一加底色是错的**，所以只做了顶栏返回键。

### 未做的两项，以及为什么现在不做

**页面排版与控件样式**

目标写的是「借鉴 NexioSchedule 的页面排版」，但没有给出具体要改哪里。
我调查过参考项目：它用的是 miuix 的 `CollapsibleTopAppBar`（折叠式 AppBar），
而 Praxis 用的是 `SmallTopAppBar`。

这个改动会**改变所有页面的顶栏形态**，属于最需要真机截图对比的一类。
当前没有真机，改了也无法判断是否更好看 —— 只能产出「CI 绿但没人看过」的改动。

**拖动/展开/滑动选择动画**

`DampedDragAnimation` 早已存在且被 4 处使用：
`FloatingBottomBar` / `LiquidToggle` / `LowLiquidToggle` / `DragGestureInspector`。
基础设施不缺，缺的是「往哪些新场景接」。

而目标有一条硬约束：**「底部导航栏与 tab 栏保持不变」**。
这两处正是拖动动画最见效的地方，却被明确排除。剩下能接的场景（列表滑动选择等）
同样需要真机判断手感。

### 结论

P6 的「能确定做什么」的部分已经做完；剩下两项是「需要看效果才能决定」的，
**不是没做，是没有判断依据**。

## 十二、删除符号的残留审计（第 47 轮，结论：干净）

这次改造删除了不少东西。担心文档或注释里留下过时引用，做了一次全仓审计。

审计对象（6 个已删除/改名的符号）：

| 符号 | 删除原因 |
|---|---|
| `TerminalSelection` / `TerminalSelectionBar` / `extractSelectionText` | P1 改用系统原生选择 |
| `AttachmentCellView` / `MiuixAnimatedPopup` | P5a 改用 miuix 下拉 |
| `loginTail` | 发现是绕路，改用 `loginArgs` 直调 |

### 结果

代码侧**无残留**。所有命中都在两类地方：

**1. 负向断言（应保留）**

```kotlin
// WiringGuardTest.kt:1014-1017
"自研的 TerminalSelection 模型又回来了 —— 会与系统选择打架",
!flat.contains("TerminalSelection(") &&
!flat.contains("TerminalSelectionBar(") &&
!flat.contains("extractSelectionText("),
```

这类断言**必须**写出已删除符号的名字 —— 它守的就是「不许把它们加回来」。

**2. 历史说明（应保留）**

```
SETTLEMENT.md / PLAN-terminal-copy.md / TERMINAL-PROOT.md / README.md
TerminalScreen.kt:978 KDoc / WiringGuardTest.kt:680 KDoc
```

这些在陈述「改造前是什么样」，是给后来人看的演进记录。

### 一个值得记的判断

「已删除符号出现在代码里」看起来像 bug，但**负向断言必须提到它**。
审计脚本最初把这类命中也标成「代码行!」，需要人工区分：

```
出现 X（正向使用）      -> 残留，要删
!contains("X")         -> 护栏，要留
KDoc 里提 X            -> 历史说明，可留
```

这三者从文本上完全一样，只能靠上下文判断。

## 十三、正式签名包已发布（第 66 轮）

### 发布信息

```
标签    v1.0.0-51479a8
页面    https://github.com/sxdhhh/Praxis/releases/tag/v1.0.0-51479a8
下载    https://github.com/sxdhhh/Praxis/releases/download/v1.0.0-51479a8/app-release.apk
```

### 逐项验证（本地下载后复验，不只是信 CI 日志）

| 项 | 结果 |
|---|---|
| 包名 | `dev.praxis`（**无 `.debug` 后缀**）|
| 版本 | `1.0.0` (versionCode 10) |
| 应用名 | Praxis |
| minSdk / targetSdk | 28 / 37 |
| 签名 | `CN=Praxis`，SHA-256 `f0910a01...2f61ef97` |
| 签名是否与 keystore 一致 | **是**（与本地 keytool 读出的指纹逐字相同）|
| ABI | arm64-v8a |
| 大小 | 7.5 MB（R8 压缩后；debug 包是 88.1 MB）|
| zip 完整性 | OK |

### 为此做的事

**1. 生成 release keystore**（仓库此前一份签名材料都没有）

```
C:\creat-agent\keys\praxis-release.jks
SHA256 F0:91:0A:01:30:A9:BF:AE:1D:2F:CE:3D:C7:26:07:6D:
       D3:C3:E3:E5:4E:13:CC:1C:ED:93:06:B1:2F:61:EF:97
```

**2. 写入 4 个 GitHub Secrets**（API 加密上传，已回读确认）：
`PRAXIS_KEYSTORE_BASE64` / `PRAXIS_STORE_PASS` / `PRAXIS_KEY_PASS` / `PRAXIS_KEY_ALIAS`

**3. 新增 `release.yml`**：还原 keystore → `assembleRelease` → **验签** → 建 Release

### 关键防护：验签失败就不发

缺任一 secret 时 AGP 会**静默产出未签名包**，构建照样 SUCCESS。
所以发布前跑 `apksigner verify --print-certs`，签名无效直接 fail。

不检查的话，就会把一个装不上的包发出去 —— 而 CI 是绿的，很难察觉。

### 一个只在正式包才暴露的问题

第一次 release 构建失败：

```
ERROR: R8: Missing class com.android.internal.view.menu.MenuBuilder
       Missing class miui.util.HapticFeedbackUtil
> Task :app:minifyReleaseWithR8 FAILED
```

**Kotlin 编译错误 0 条。**

原因：debug 变体 `isMinifyEnabled = false`、**从不跑 R8**，这些引用从没被检查过。
release 一开 minify，R8 的缺失类检测把它们全报出来。

它们是 MIUI / AOSP 的**私有 API**，App 通过反射访问（拿不到就降级），
编译期本就不需要存在。补了 `-dontwarn` + `-keep` 规则。

**教训：debug 全绿不代表 release 能过 —— 两者走不同的构建路径。**

### ⚠️ keystore 必须备份

签名身份一旦发布就不能更换 —— 换了之后用户无法覆盖安装。
请备份 `C:\creat-agent\keys\praxis-release.jks` 与口令。
**丢失 = 以后所有版本必须换包名重新开始。**

### 验收状态说明

这个包通过了 **R8 / 编译 / 验签**，但**功能验收仍是 7/24 条**（真机仍离线）。
发布不等于验证通过 —— 用户实际使用后的反馈才是最有价值的验证。
