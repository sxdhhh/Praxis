# 浏览器实时预览 UI

改一行 Compose 代码，在浏览器里看到渲染结果。不用模拟器、不用 adb、不用 Android Studio。

## 它怎么工作

```
       改 .kt 文件
            │
            ▼
  tools/preview_server.py          ← 监听源码 mtime
            │  触发
            ▼
  ./gradlew :preview:testDebugUnitTest
            │  调用
            ▼
  Paparazzi → layoutlib（Android 渲染引擎）→ PNG
            │  落在
            ▼
  preview/build/reports/paparazzi/images/*.png
            │  SSE 推送
            ▼
        浏览器自动刷新
```

关键点：**渲染出来的是真东西**。Paparazzi 用的是 layoutlib —— Android
自己的布局渲染引擎，也就是 Android Studio Preview 背后那套。所以字重、
圆角、miuix 的 squircle、深浅色，都和真机一致，不是模拟出来的近似结果。

## 一条必须先知道的限制

**layoutlib 的 native 库只有 x86_64。**

```
$ curl -o /dev/null -w '%{http_code}' \
    https://dl.google.com/dl/android/maven2/com/android/tools/layoutlib/layoutlib/16.2.3/layoutlib-16.2.3-linux-aarch64.jar
404
```

所以：

| 环境 | 能否渲染 |
|---|---|
| GitHub Actions `ubuntu-latest`（x86_64） | ✅ |
| 普通 x86_64 Linux / Intel Mac | ✅ |
| Apple Silicon Mac | ❌ |
| 本项目的 proot（aarch64） | ❌ |

在 aarch64 上跑，dev server 仍然能启动、能看已有的图，只是**重新渲染会失败**，
并且页面会把「平台不支持」明确显示出来，而不是静默。

## 用法

### 1. 拿到第一批图

第一次没有任何 PNG 时，需要先让 x86_64 那边跑一次。

**走 CI（推荐）**

推一次涉及 `feature/` / `ui/` 的提交，或手动触发：

```
Actions → preview → Run workflow
```

跑完在 run 页面底部下载 `ui-previews` artifact，解压后把 `images/` 放到：

```
preview/build/reports/paparazzi/images/
```

**或在 x86_64 机器上直接跑**

```bash
./gradlew :preview:testDebugUnitTest
```

### 2. 启动看板

```bash
python3 tools/preview_server.py
# → http://127.0.0.1:8793/
```

零依赖（只用标准库）。默认端口 8793：8791 被 MT APK MCP 占着，8792 是 pk-node。

之后改任意 `.kt` 保存，页面会自动重新渲染并刷新。在 x86_64 上这是秒级反馈；
在 aarch64 上会显示「平台不支持渲染」，需要重新走一遍第 1 步。

### 3. 只看不渲

```bash
python3 tools/preview_server.py --no-render
```

适合「图是从 CI 拉下来的、只想过一遍」的场景。

## 加了哪些界面

都在 `preview/src/test/java/dev/praxis/preview/ScreenPreviewTest.kt`：

| 名字 | 用途 |
|---|---|
| `chat-empty` | 空对话（首启态） |
| `chat-conversation` | 真实多轮对话，含流式中的那条 |
| `chat-conversation-dark` | 同上，深色主题 |
| `chat-scroll-affordance` | 长列表，专门看滚动条与回底按钮 |
| `model-settings` | 模型设置页（分区最多，折叠动画最容易出问题） |

加一屏就是在那个文件里加一个 `@Test`：

```kotlin
@Test
fun my_screen() {
    paparazzi.snapshot(name = "my-screen") {
        PraxisTheme(lightTheme) {
            MyScreen(/* 纯假数据 */)
        }
    }
}
```

## 加新界面时的三条规矩

**1. 只用假数据。** `@Preview` 跑在纯 JVM 上，没有 `Context`、没有网络、
没有数据库。任何需要真实依赖的东西都进不来。

**2. 别为了预览改产品代码。** 如果某个页面拿不到可构造的状态，那通常说明
它的参数依赖了具体实现（比如直接读 ViewModel）。正确做法是把它改成收
纯数据 + 回调 —— 那样预览能用，测试也能用。

**3. 设备参数写死在渲染测试里。** 不要做成命令行开关。一旦可变，
就会出现「你本地看是 420dp、CI 上是 360dp」，两张图没法对比。

## 为什么不选另外两条路

**Android Studio 的 Compose Preview + Live Edit**：实时性只在 AS 里成立，
且 Live Edit 要求 API 30+ 设备。本项目开发环境是无 GUI 的 proot，走不通。

**Compose for Web（wasmJs）**：理论上还原度最高、还是真交互
（Miuix 的 8 个模块确实都发布了 `wasmJs` 变体）。但需要把
`ui:design` / `ui:widgets` / `feature:chat` 三个模块从 android-library
迁到 KMP，并把 `GlassBackend`、`DeviceCornerRadius`、`BgEffect*`
四个文件的平台差异抽成 `expect/actual`。那是 2~3 天的工作量，
适合在需要「调滚动/动画手感」时再做。

现在这条（Paparazzi）是半天成本，能覆盖「看静态形态」这一类需求 ——
配色、间距、层级、深色对比度、以及像滚动条压住导航胶囊那种布局冲突。
