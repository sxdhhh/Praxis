# 插件 UI 渲染（compose_dsl）可行性评估

> R4 产物。结论先行，证据在后。
> 目标：让「兼容插件入口」页里的入口**点得开**——即插件提供的页面能被渲染出来。

## 一、结论

| 问题 | 判断 |
|---|---|
| 现在能点开吗 | **不能**。而且卡点不在渲染，在**没有 JS 运行时** |
| 渲染本身难吗 | 结构简单（JSON 树 + props），但**面很大**：参考实现 80 个组件、12,631 行 |
| 最小可用需要多少 | 估 **1500~2500 行**（含 JS 运行时那一层），可覆盖典型插件面板 |
| 建议顺序 | 先补 JS 运行时（插件跑不起来，渲染再全也没用），再按子集实现渲染 |

## 二、compose_dsl 是什么（契约，均有出处）

### 2.1 注册侧

包在 main 脚本里注册一个页面（`examples/sidebar_sillytavern/src/main.ts`）：

    ToolPkg.registerUiRoute({
      id, route: "toolpkg:<pkg>:ui:<id>",
      runtime: "compose_dsl", screen: <module>, keepAlive: true, title: {zh, en},
    })

`screen` 是一个**模块**（该插件是 `dist/ui/sillytavern_dashboard/index.ui.js`），
不是路径字符串 —— `JsExecutionScriptBuilder.kt:361` 明确报错
"composeDsl.screen must be a compose_dsl screen function, not a string path"。

### 2.2 渲染协议

`JsComposeDslRuntimeScript.kt` 暴露三个宿主调用入口：

| 函数 | 作用 |
|---|---|
| `__operit_render_compose_dsl(options)` | 首次渲染 |
| `__operit_rerender_compose_dsl(options)` | 状态变化后重渲染 |
| `__operit_dispatch_compose_dsl_action(actionRequest)` | 执行动作 |

入口函数解析规则（`:60-64`）：**模块 default export，或全局 `Screen` 函数**；
找不到就抛 "compose_dsl entry function not found"。

JS 侧需要宿主提供全局 `OperitComposeDslRuntime`（`createContext` /
`setCallRuntime` / `updateRuntimeOptions`）。

### 2.3 数据模型

`ToolPkgComposeDslParser.kt:7-18`：

    Node  = { type: String, props: Map<String,Any?>, children: [Node], slots: Map<String,[Node]> }
    返回值 = { tree: Node, state: Map, memo: Map }

`slots` 是具名插槽（如 Scaffold 的 topBar / floatingActionButton）。
动作靠 `__actionId`：`{"__actionId":"x"}`、字符串 `"__action:x"`、或裸字符串都认
（`:31-45`）。

### 2.4 组件面

`ToolPkgComposeDslGeneratedRegistry.kt`（**自动生成**，见其文件头）注册了
**80 个** Material3/Foundation 组件：Column / Row / Box / Text / Button / Card /
LazyColumn / Scaffold / NavigationBar / Tab / Chip / Dialog / Divider / Image …

## 三、参考实现的规模（实测）

| 文件 | 行数 | 作用 |
|---|---|---|
| `ui/common/composedsl/ToolPkgComposeDslScreen.kt` | 4734 | 宿主屏幕：状态、动作循环、布局 |
| `ui/common/composedsl/ToolPkgComposeDslGeneratedRenderers.kt` | 3382 | **生成**的 80 个组件渲染器 |
| `ui/common/composedsl/ToolPkgComposeDslWebView.kt` | 2315 | WebView 运行时宿主 |
| `core/tools/javascript/JsComposeDslBridge.kt` | 922 | props → Compose（dp/sp/颜色/修饰符） |
| `ui/common/composedsl/ToolPkgComposeDslDebugSnapshotStore.kt` | 443 | 调试快照 |
| `core/tools/javascript/JsComposeDslRuntimeScript.kt` | 356 | 注入 JS 的运行时脚本 |
| `core/tools/packTool/ToolPkgComposeDslParser.kt` | 208 | 树解析 |
| `ui/common/composedsl/ComposeDslTextInputSync.kt` | 138 | 输入框双向同步 |
| `ui/common/composedsl/ToolPkgComposeDslGeneratedRegistry.kt` | 92 | 组件注册表（生成） |
| `core/tools/packTool/ToolPkgComposeDslDebugDumpReceiver.kt` | 41 | 调试转储 |
| **合计** | **12,631** | |

值得注意：3,474 行是**代码生成**的（Registry + Renderers），源头是
`tools/compose_dsl/generate_compose_dsl_artifacts.py`。也就是说组件面是
「按需生成」，不必手写 80 个。

## 四、Praxis 现状：缺的是更前面的一环

| 需要的东西 | Praxis 现状 |
|---|---|
| JS 运行时（跑包脚本） | **没有**。无 quickjs、无 WebView 实现 |
| `PluginScriptRuntime` 实现 | **只有测试假实现**（`PluginRuntimeHostTest`） |
| compose_dsl 解析 | 没有 |
| compose_dsl 渲染 | 没有（`McpAndSandbox.kt:153` 已写明「渲染不在本轮」） |

`PluginRuntimeHost.kt` 的注释写着「:app 用 WebView 实现」，但那个实现**从未写**。
所以现在：包装得上、清单读得出、入口列得出来，但**包脚本一次都没跑过** ——
工具没注册过，运行时入口也没有过（因为压根没有捕获实现）。

## 五、最小可行子集（建议）

### 5.1 契约面（必须完整，不能省）

- 三个入口函数：render / rerender / dispatchAction
- 返回值结构：`{ tree, state, memo }`
- `OperitComposeDslRuntime` 全局桥（至少 createContext / setCallRuntime）
- `__actionId` 三种写法

省掉任何一条都会有插件「跑不起来但看不出为什么」。

### 5.2 组件子集（建议 13 个）

`Column` / `Row` / `Box` / `Spacer` / `Text` / `Button` / `TextButton` /
`IconButton` / `Card` / `LazyColumn` / `Icon` / `Divider` / `Scaffold`

覆盖典型插件面板（列表 + 文本 + 按钮 + 卡片 + 顶部栏）。不支持的组件必须
**显式渲染成「不支持的组件 X」占位**并记日志 —— 静默跳过会让用户以为布局坏了。

### 5.3 props 子集

`text` / `content` / `onClick`(`__actionId`) / `modifier`（padding、fillMaxWidth、
size）/ `style`（字号、粗体）/ `color` / `enabled`。其余 props 记一次警告后忽略。

## 六、工作量判断

| 块 | 估行数 | 说明 |
|---|---|---|
| WebView JS 运行时（`PluginScriptRuntime` 实现） | 400~700 | 模块加载 + 三个注入函数 + 回调桥 + 生命周期 |
| compose_dsl 解析（Kotlin） | 150~250 | 照抄形状即可，纯逻辑可单测 |
| 组件渲染器（13 个） | 600~900 | 手写，不再引代码生成 |
| props → Compose 解析 | 250~400 | dp/sp/颜色/修饰符白名单 |
| 页面宿主（状态、动作循环、重渲染） | 250~400 | |
| **合计** | **1650~2650** | 对比参考实现 12,631 |

**不确定性**：以上是「能跑通典型面板」的估计。真实插件会不会用到子集外的组件，
取决于市场里包的实际用法 —— 我没有统计数据，这一点必须承认。

## 七、顺序建议

1. **先 JS 运行时**。没有它，R1 的捕获通路永远拿不到数据，渲染也无从触发。
   它的收益与渲染无关也成立：工具注册（模型能用插件工具）同样卡在这里。
2. 运行时通了之后，捕获桥才谈得上「对齐 JsToolPkgRegistration 的 19 个注册名」。
3. 最后做 compose_dsl 渲染子集，把入口点开。

## 八、没做的事

- 没有实机跑过任何 compose_dsl 页面（Praxis 侧无运行时，Operit 侧未部署）。
- 没有统计真实插件用到哪些组件，子集是按「典型面板」推的，不是实测分布。
- `JsComposeDslBridge.kt` 的 922 行只看了职责定位，未逐行读 —— 如果 props 语义
  比估计的复杂（尤其 modifier 与颜色），第 4 块工作量会上浮。
