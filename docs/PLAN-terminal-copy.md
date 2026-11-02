# 终端复制粘贴改造方案（M2）

## 现状（已读代码确认）

`feature/terminal/.../TerminalScreen.kt`（1771 行）：

```
280  var selection by remember(state.active.id) { mutableStateOf<TerminalSelection?>(null) }
281  val clipboard = LocalClipboardManager.current
527  items(count = state.active.lines.size, key = { index -> index }) {
550      TerminalRow(line = ..., selectionRange = rowRange)   // 每行一个 Text
567  TerminalSelectionBar(hasRange, onCopy, onSelectAll, onDismiss)
1040 internal fun extractSelectionText(lines, normalized): String
```

即：**自研的 cell 坐标选择模型**（`TerminalSelection` 存起止行列），
拖动时按格子扩展选区，松手后底部弹出「已选中文本 / 复制 / 全选 / 完成」。

用户评价：**「自写就太难用」**，要求改用 Android 自带的复制粘贴。

## 为什么不能直接套 SelectionContainer

Compose 的 `SelectionContainer` **在选择上等同于一个文本节点**。它的选择范围
不能跨越 `LazyColumn` 的不同 item —— 而终端恰恰是「每行一个 item」。

所以 `SelectionContainer { LazyColumn { items { Text } } }` 里，
长按只能选中**当前那一行**，拖到行外就断了。这比现在还难用。

## 方案对比

### 方案 A：可见区渲染成单块文本 + SelectionContainer（推荐）

```
Box(rememberScrollState()) {
    SelectionContainer {
        Text(可见区的整段文本, style = 等宽)   // 一个文本节点
    }
}
```

- **优点**：真正用上系统选择（手柄、放大镜、「复制/全选/分享」浮动菜单、
  与其它 App 一致的触感），代码量与自研模型相当或更少；
- **代价**：失去 LazyColumn 的按需渲染。终端行是有上限的
  （`scrollback + buffer.rows`，几百到几千行），单块 Text 的排版成本要实测。
  可以先只对**可见窗口 + 少量余量**这样做，其余仍走虚拟滚动。
- **样式**：`Text` 支持 `AnnotatedString`，把每格的 `CellStyle`（前景/背景/粗体）
  转成 `SpanStyle` 即可保留 ANSI 配色。

### 方案 B：保留 LazyColumn，只在「进入选择模式」时切成单块

默认用 LazyColumn 滚动（性能最好）；长按进入选择态时，
把当前屏内容换成单块 `SelectionContainer`，退出选择态再换回来。

- **优点**：滚动性能不受影响；选择范围 = 当前屏（足够用）；
- **代价**：进入/退出选择有一帧布局切换，需要处理滚动位置同步。

### 方案 C：混合 —— SelectionContainer 包 LazyColumn（不推荐）

只解决单行选择，跨行仍失效。**直接排除。**

## 建议：先做方案 A，用实测数据决定是否退回 B

理由：方案 A 的代码最直白、最贴近「用系统自带的」，也最符合用户诉求；
唯一风险是单块 Text 的排版性能。而这个风险**可以量化**：

```
测量：终端填满 scrollback（比如 1000 行）后，滚动与输入的帧耗时
判据：单帧 > 16ms 就退回方案 B
```

真机（Redmi / Android 17 / 120Hz）就在手边，这个测量做得了。

## 输入框侧

输入框已经是 `BasicTextField`，长按本就有系统的「粘贴/全选」。
需要确认：当前是否覆写了 `contextMenu` 或禁用了长按。若无，这块不用改。

## 要删的东西

```
TerminalSelection / NormalizedSelection 数据类
TerminalSelectionBar（已选中文本/复制/全选/完成 那条）
extractSelectionText
TerminalRow 的 selectionRange 参数与高亮绘制
拖动扩选的手势逻辑
```

**注意**：`extractSelectionText` 有 11 条单测（`TerminalExtractSelectionTest`），
删除时那些测试要一并删除，不要留下「测一个不存在的函数」。

## 验收（真机）

1. 长按终端输出 → 出现**系统选择手柄**与浮动菜单（不是自研的底部四件套）；
2. 拖动手柄可跨行扩展；
3. 点「复制」后，去别处粘贴，内容与选中的终端文本一致；
4. 输入框长按 → 出现系统「粘贴」，能粘贴上一步复制的内容；
5. ANSI 配色在选择态下仍然正确显示。
## P1 终端复制粘贴（2026-11-03）

### 已完成的

  改造：自研 cell 坐标选择模型 -> SelectionContainer + 单块 AnnotatedString
  新增：buildAnnotatedScreen()（整屏拼接，逐行裁行尾空白）
  删除：TerminalSelection.kt、TerminalExtractSelectionTest.kt、TerminalSelectionTest.kt
        （共 -828 行）
  滚动：AutoFollowScrollLazy -> AutoFollowScroll(ScrollState)
  护栏：WiringGuardTest 那条选择护栏**反向**，改为断言
        「系统选择已接上 + 自研模型确实没了 + 输出区不再是 LazyColumn」

### 验证状态

  ✅ CI #105 / preview #16 全绿（编译 + 全部单测通过）
  ✅ 静态检查：371 个文件零问题；lexer 23/23
  ✅ 装到真机后 App 正常运行（对话页渲染正常，无崩溃）
  ⏳ **终端页的长按行为尚未在真机上看到**

  未看到的原因：真机的 uiautomator 在 02:05 左右停止响应
  （dump 返回空），无法可靠定位并点中底部导航的「终端」tab。
  这是排查手段受限，不是已知的功能缺陷。

### 途中踩的坑（已修，值得记住）

  CI #104 失败，63 条 Unresolved reference，根因是删除函数时留下结构残留：

    TerminalSelectionBar 的 KDoc（函数已删）
    孤立的 @Composable（原本属于被删的函数）
    TerminalRow 的 KDoc 片段（函数已删，且没有闭合的 */）

  那段未闭合的 `/**` 一直吃到下一个 `*/`，注释边界全乱、
  编译器恢复时丢掉后面的声明，于是 `TerminalUiState`（明明就在同文件）变成未解析。

  **这是我在这个项目里第三次栽在「多步字符串删除留下结构残留」上。**
  这次的收获是找到了可靠的复核手段，以后每次删声明都该跑一遍：

    1) `/**` 与 `*/` 数量必须相等（故障时 44 vs 43）
    2) 每个 `@Composable` 的下一个实质行必须是一个声明
    3) 不允许出现「*/ 之后紧跟 /**」的相邻 KDoc
