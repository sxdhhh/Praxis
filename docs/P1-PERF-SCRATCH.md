## 附：两个看似能优化、实则不能的做法（2026-11-03 分析后否决）

在等真机的空档里，我研究过 `buildAnnotatedScreen` 的性能优化空间。
有两个看起来合理的改法，**分析后都否决了**。记下来避免以后重复踩。

### 否决 A：把 `remember` 的 key 从 `lines` 换成 `contentVersion`

**动机**：`state.active.lines` 每次刷新都是新建的 `ArrayList`
（`linesOf()` 里 `val lines = ArrayList<TerminalLine>(...)`），
引用每次都变 —— 所以 `remember(state.active.lines, defaultFg)`
**几乎每次刷新都会重建整条 AnnotatedString**。

而 `contentVersion`（非空格字符总数）已经精确反映「内容是否真的变了」，
看起来是个更好的 key。

**否决理由**：场景 F ——

```
非空格字符数不变，但内容变了
例：某行原地把 a 改成 b（同长度原地覆写）
  -> 字符总数不变 -> contentVersion 不变 -> 不重建 -> 屏幕上显示旧内容
```

终端里同长度原地覆写并不罕见（进度条、`` 回退重写、
某些 TUI 的原地刷新）。**这个风险会导致显示错误内容，代价远大于收益。**

### 否决 B：按行缓存 AnnotatedString 再拼接

**动机**：只有变化的那几行需要重建，其余复用。

**否决理由**：`buildAnnotatedString { }` 是**顺序 append 的 builder**，
不能从「已缓存的整条 AnnotatedString」里增量替换某一段。
要做按行缓存，得改成：

```
每行缓存成独立的 AnnotatedString
-> 再逐行 append + append('\n') 拼成一条
```

但这样每行仍要 append 一次，且要额外维护一个行缓存数组 + 失效判定。
而 `SelectionContainer` 需要的是**一条**连续的 AnnotatedString，
所以拼接本身省不掉。**收益有限，复杂度明显上升。**

### 那么真正的出路是什么

如果真机实测帧耗时确实超标，按 [P1-FALLBACK-DESIGN.md] 的顺序走：

1. 先确认是「排版」慢还是「选择」慢（用 `dumpsys gfxinfo` 分段看）
2. 若是排版慢 -> 考虑调小 `scrollbackSize`（牺牲历史换流畅，零架构风险）
3. 若仍不够 -> 才上双渲染路径（有较多不确定性，见该文档）

**在没有实测数据前，任何优化都是猜测。** 这两个否决记录的价值在于：
下次再想到这两个点子时，能直接看到为什么不行。
