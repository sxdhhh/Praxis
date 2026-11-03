# P1 退路方案设计（仅设计，未实现）

> 目标对 P1 写了一条判据：**「填满 scrollback（约 1000 行）后滚动/输入 > 16ms
> 则退回『仅选择态切单块、平时仍虚拟滚动』的折中方案」**。
>
> 当前实现是**无条件整块渲染**（所有行拼成一条 `AnnotatedString`）。
> 如果真机实测超 16ms，就需要退路。本文档先把这个退路设计清楚，
> 但**没有写代码** —— 原因见最后。

---

## 当前实现（TerminalScreen.kt:427-454）

```kotlin
val fullText = remember(state.active.lines, defaultFg) {
    buildAnnotatedScreen(state.active.lines, defaultFg)
}
SelectionContainer(...) {
    Text(text = fullText, ...)
}
```

所有行都参与排版。好处是长按能跨行拖选（`SelectionContainer` 把一个 `Text` 视为单个文本节点）。
代价是失去 `LazyColumn` 的按需渲染。

## 退路设计

### 形态

```
平时：LazyColumn + 每行一个 Text（虚拟滚动，只渲染可见行）
选择态：切到 SelectionContainer + 整块 Text（可跨行拖选）
```

### 关键难点：怎么知道「进入了选择态」

这是整个退路的**唯一难点**。Compose 的 `SelectionContainer` 没有对外暴露
「当前是否正在选择」的公开状态。

候选方案，按可靠性排序：

| 方案 | 做法 | 风险 |
|---|---|---|
| A. `LocalTextSelectionService` | 读 Compose 内部的 selection service 状态 | **需先核实该 API 在 Compose 版本里是否存在、是否 public** |
| B. 自建 `detectTapGestures(onLongPress)` | 在外面套一层，长按即切选择态 | 会与 `SelectionContainer` 内部的长按识别冲突，可能导致手柄不出现 |
| C. 手动切换按钮 | 工具栏加一个「选择文本」按钮，点了才切 | 不是系统原生交互，偏离 P1 的初衷 |

方案 A 最理想但**未核实**；方案 B 有冲突风险；方案 C 违背 P1 目标。

### 另一个难点：切态时的滚动位置

`LazyColumn` 的 `LazyListState` 与 `ScrollState` 是两套滚动模型。
切换时要保证「用户看到的那一行」不跳，需要：

```
LazyColumn 的 firstVisibleItemIndex + offset
  -> 换算成 ScrollState 的像素偏移
  -> 切回来时反向换算
```

两边的行高必须一致才换算得准。终端是等宽等行高的，这一点满足。

### 成本估算

| 部分 | 工作量 |
|---|---|
| 双渲染路径 | 中等（两套布局都要维护）|
| 选择态检测 | **不确定**（取决于方案 A 是否可用）|
| 滚动位置同步 | 中等 |
| 回归风险 | 高（两条路径行为要一致）|

---

## 为什么现在不实现

三个理由：

**1. 前提未成立。** 退路只在「实测 > 16ms」时才需要。目前一次都没测过，
现在实现等于为一个可能不会发生的问题增加一倍代码量和回归面。

**2. 关键 API 未核实。** 方案 A 依赖 `LocalTextSelectionService`，
我在本地查不到 Compose 的 jar，无法确认它在当前版本里是否 public。
这个项目已经因为「照印象写 API」返工过 7 次（见 SETTLEMENT.md 第三节），
不该再犯。

**3. 退路本身会让 P1 变差。** 双路径意味着：
  - 平时用 LazyColumn，用户长按某行时只能选中那一行（旧问题回来了）
  - 必须先进入「选择态」才能跨行选，多一步操作

这是目标允许的折中，但**只在性能确实不达标时才值得**。

---

## 如果实测超 16ms，按这个顺序做

1. 先确认 Compose 版本，用 javap 或源码核实 `LocalTextSelectionService` 的可见性
2. 若可用 -> 走方案 A
3. 若不可用 -> 重新评估：能否接受「选择态需点按钮进入」（方案 C）
4. 无论走哪个方案，都要重新跑一遍 `docs/ACCEPTANCE-CHECKLIST.md` P1 的 5 条

---

## 另一个可能的优化（不改变架构）

如果在 16ms 附近而不是远超，也许不用上双路径，只做这几件事就能压下去：

  - `buildAnnotatedScreen` 的结果已经 `remember` 了，但它 key 是
    `state.active.lines` —— 每次内容变都会重建整条 AnnotatedString。
    可以只重建变化的那几行（按行缓存 AnnotatedString，再拼接）。
  - 终端行数上限可以调小（`scrollbackSize`），牺牲历史换流畅。

这两条都**不改变渲染架构**，风险远低于双路径。实测后优先考虑。
