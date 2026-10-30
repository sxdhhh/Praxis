# Praxis

Android 平台上的 AI 助手客户端。带一个真正跑得起来的 Linux 容器（proot + Ubuntu/Kali），
模型可以直接在容器里执行命令、读写文件、装软件包。

内置状态：

| 项 | 值 |
|---|---|
| 包名 | `dev.praxis` |
| minSdk / targetSdk / compileSdk | 28 / 37 / 37 |
| JDK / Gradle / AGP | 21 / 9.7.0 / 9.4.1 |
| 模块数 | 18 个 Gradle 子项目 |
| 源码规模 | 约 150 个 `.kt`，19,000+ 行 |
| 单元测试 | 225+ 例 |
| 协议 | [MIT](LICENSE) |

---

## 目录

- [它是什么](#它是什么)
- [核心能力](#核心能力)
- [下载与安装](#下载与安装)
- [从 GitHub 构建](#从-github-构建)
- [本地构建](#本地构建)
- [项目结构](#项目结构)
- [模块地图](#模块地图)
- [设计约定](#设计约定)
- [第三方协议](#第三方协议)
- [已知限制](#已知限制)

---

## 它是什么

一个**能动手的** AI 客户端，不是一个聊天壳。三件事构成它的骨架：

1. **对话**：OpenAI 兼容协议（OpenAI / DeepSeek / Moonshot / 各类中转站），流式输出、
   工具调用、思考强度、上下文压缩、会话摘要。

2. **工具**：模型可以调用文件读写、终端执行、屏幕截图等能力，权限分档（默认 / 调试 / 超级）。
3. **Linux 容器**：proot + forkpty，在手机里跑一个真的 Ubuntu 或 Kali。`apt`、`python3`、
   `nmap`、`vim` 都能用；模型和用户可以进同一份文件系统。

设计上有一条固定的判断：**不摆「能点但存不进去」的假控件**。
每个设置项都必须在请求路径上真的有消费方，否则宁可不做。这条判断贯穿了整个仓库，
也是为什么很多看起来「应该有」的开关并不存在。

---

## 核心能力

### 对话

- 流式 SSE，非流式兜底（开关关闭时走一次性解析，不是「请求成功但回复空白」）
- 工具调用：模型原生 `tool_calls`，参数流式生成期间会显示**实时字数**而不是只有一个光标
- 上下文管理：token 预算估算 → 达到阈值自动压缩 → 摘要插在 system 之后、对话之前
- 功能位模型覆盖：对话 / 上下文总结 / 标题 / 记忆更新，各自可指向不同模型或跟随全局
- 多模型池：一个服务商下存多个模型 ID，用索引表达「当前用哪个」

### 模型配置

| 分区 | 内容 |
|---|---|
| 服务商 | 预置若干家（自动填地址与默认模型），也可完全自定义 |
| 接口地址 | OpenAI 兼容端点，自动补 `/v1/chat/completions` |
| 密钥 | AES/GCM 加密存 Android Keystore，不落 `data/data` 明文 |
| 模型 | 可从 `/models` 拉取（支持一键全选），也可手填 |
| 参数 | 温度 |
| 上下文 | 上限（token）与压缩阈值 |
| 思考 | 四档（关 / 低 / 中 / 高），由 provider 翻译成各家参数名 |
| 自定义请求头 | 中转站 / 企业网关要求的额外 HTTP 头（加密存储） |
| 请求队列 | 每分钟请求上限（滑动窗口）+ 最大并发数 |

### Linux 容器

- **rootfs**：按需下载 Ubuntu 24.04 或 Kali，两者**共用同一份文件系统**（切换发行版只换 apt 源与标记）
- **两种会话形态**：
  - 交互 PTY（终端页）：`vim` / `top` / `Ctrl+C` 正常
  - 管道泵（环境页 / SSH / FRPC / FTP）：后台服务用

- **终端**：多标签、逐键输入、窗口尺寸跟随、滚动缓冲区持久化
- **能跑的服务**：sshd、frpc（内网穿透）、vsftpd
- proot 跑不起来时终端会**明确告诉你当前在宿主环境**，不静默降级

### 终端

- 真 PTY：`forkpty` + `openpty`，不是把命令丢给 `sh -c` 再读输出
- 光标/ANSI 完整解析（`TerminalBuffer` + `AnsiScanner`）
- 虚拟键盘条：Ctrl / Alt / Esc / Tab / 方向键
- 会话快照：杀进程重开能恢复屏幕内容与命令历史

---

## 下载与安装

### 方式一：从 Actions 产物下载（推荐）

每次构建都会把 APK 挂在 Actions 的 Artifacts 里。

1. 打开仓库的 **Actions** 标签页
2. 左侧选 **build**
3. 选一次成功的运行（绿色对勾）
4. 拉到底部 **Artifacts** 区域，下载 `praxis-apk`
5. 解压得到 `.apk`，传到手机安装

> Artifacts 保留 14 天。过期就重新跑一次 workflow。

### 方式二：本地构建后安装

见 [本地构建](#本地构建)，产物在 `app/build/outputs/apk/debug/`。

### 安装注意

- `minSdk = 28`（Android 9）。低于此版本装不上。
- 首次启动需要授予存储/通知等权限，用于文件工具与消息提醒。
- Linux 容器是**首次使用时才下载 rootfs**，不是随 APK 打包。
  所以第一次进「Linux 环境」页会看到下载进度，需要网络。

- debug 包的 `applicationId` 带 `.debug` 后缀，可与 release 同时安装。

---

## 从 GitHub 构建

仓库已配好 workflow：`.github/workflows/build.yml`。两个 job **并行**跑：

| Job | 做什么 | 产物 |
|---|---|---|
| `verify` | `testDebugUnitTest` + `lintDebug` | 测试报告 / lint 报告（Artifacts） |
| `build` | `assembleDebug`（或手动选 release） | `praxis-apk`（Artifacts） |

### 手动触发

Actions → build → **Run workflow**，有两个输入：

- `task`：`assembleDebug`（默认）或 `assembleRelease`
- `upload_artifact`：是否上传 APK，默认是

### 自动触发

推到 `main` 或 `master`，且改动落在会影响 APK 的路径（`app/` `core/` `feature/` `ui/`
`third_party/` `gradle/` `build-plugins/` 以及三个构建脚本）时自动跑。
纯文档提交不占用 runner。

### 为什么 workflow 里要装 SDK 37

`build-plugins/PraxisBuildConfig.kt` 里 `COMPILE_SDK = 37`。GitHub 的 `ubuntu-latest`
预装 SDK 通常停在 34/35，直接构建会报 `Failed to find target with hash string android-37`。
所以 workflow 里显式 `sdkmanager "platforms;android-37"`。

同理 JDK：`JDK_VERSION = 21`，所有模块 `jvmToolchain(21)`。CI 用 17 会在**配置阶段**就报
`No matching toolchains found`，连编译都到不了。

### release 构建的限制

仓库里没有 keystore（也不该有）。`assembleRelease` 能跑通，但产出的是**未签名** APK，
无法直接安装。要装请用 debug 包，或在本地配好签名后自行构建。

---

## 本地构建

### 需要

- JDK **21**
- Android SDK **platform 37** + build-tools 37.0.0
- 网络（首次拉依赖）

### 步骤

```bash

# 1. 告诉 Gradle SDK 在哪

echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 构建 debug 包

./gradlew assembleDebug

# 3. 产物

ls app/build/outputs/apk/debug/

# 4. 装到连着的设备

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 常用任务

```bash
./gradlew testDebugUnitTest      # 单元测试
./gradlew lintDebug              # lint（abortOnError 已开）
./gradlew assembleRelease        # release（未签名）
./gradlew :feature:chat:test     # 单模块测试
```

### 仓库自带的静态检查

`tools/` 下几个脚本在 CI 之外也能用：

```bash
python3 tools/check_kt_files.py <文件...>   # 括号/引号平衡
python3 tools/audit_unwired.py              # 找「有 UI 没接线」的回调
python3 tools/check_apk_alignment.py        # 检查 APK zipalign
```

> 注意 `check_kt_files.py` 对含三引号长文本的文件会误报 `+1`。它按行计数，
> 三引号里的 `(` 会被算进去。人工核对括号平衡即可，不要盲信。

---

## 项目结构

```
Praxis/
├── app/                     组装层：Application、DI、导航、各 Tab
├── core/                    基础层（多数无 Android 依赖）
│   ├── ai/                  流式事件、SSE 解析、上下文预算、限流器
│   ├── data/                DataStore 设置、加密、会话存储
│   ├── linux/               proot 命令构造、环境契约、发行版表
│   ├── model/               纯数据契约（ModelSettings / FunctionSlot / …）
│   ├── memory/  tool/  plugin/  workflow/  terminal/
├── feature/                 功能层
│   ├── chat/                对话页、输入栏、设置条、定位器、滚动
│   ├── settings/            设置宿主 + 各二级页（模型 / 环境 / 能力 / 外观）
│   ├── terminal/            终端页、虚拟键盘、PTY 端口
├── ui/                      设计层
│   ├── design/              动效刻度、滚动跟随、玻璃
│   ├── theme/               Miuix 主题、取色
│   ├── widgets/             通用控件（FoldableContent、动画弹窗…）
├── build-plugins/           自定义 Gradle 约定插件
├── third_party/             入库的 Miuix AAR（见协议说明）
├── scripts/                 CI 脚本 + rootfs 拉取
├── tools/                   静态检查脚本
└── docs/                    审计报告、协议说明
```

---

## 模块地图

依赖方向是**单向**的：`app → feature → ui → core`。core 之间只允许 `core:model` 被任何层依赖。

| 模块 | 职责 | 不该做的事 |
|---|---|---|
| `core:model` | 纯数据类与枚举 | 不依赖 Android，不做 IO |
| `core:ai` | 协议与算法（SSE、预算、限流） | 不碰 OkHttp，不碰 Context |
| `core:data` | 持久化（DataStore / Room / 加密） | 不画 UI，不知道页面存在 |
| `core:linux` | proot 参数构造、发行版元数据 | 不起进程（那是 `:app` 的实现） |
| `feature:*` | 页面 + ViewModel + 端口 | 不直接依赖 Android Context（用端口注入） |
| `ui:*` | 控件与主题 | 不依赖任何 feature |
| `app` | DI 接线、导航、真实实现 | 不写业务逻辑 |

**端口模式**：`feature` 模块通过 interface 声明它需要什么（`LinuxEnvPort`、`PtySessionPort`、
`ModelCatalogFetcher`…），`:app` 提供实现并用 Koin 注入。这样 feature 能在纯 JVM 单测里跑，
不必拖进 Android 框架。

---

## 设计约定

### 一、折叠与弹层必须有动效

项目里所有折叠面板走 `FoldableContent`（`expandVertically` + `fadeIn`），
所有 Dialog 走 `MiuixAnimatedDialog`，贴边浮层走 `MiuixAnimatedPopup`。

共同约束：**调用点必须常驻挂载，用 `visible` 表达显示与否**。

```kotlin
// 错：visible 变 false 那一刻组件被移除，退场动画来不及播
if (visible) { MiuixAnimatedDialog(visible = visible) { ... } }

// 对：组件一直在，visible 只是参数
MiuixAnimatedDialog(visible = visible) { ... }
```

动效刻度集中在 `PraxisMotion`，刻意不对称（进 300 / 出 250，弹窗进 240 / 出 160）：
用户点关闭时已经做完决定，多等一帧就嫌慢。

### 二、设置项必须有消费方

每个存在 DataStore 里的字段，都必须在请求路径或渲染路径上被读到。
做不到就别加这个开关 —— 「能填但不生效」比「没有这个选项」更糟：
用户会以为是 bug，然后反复折腾。

### 三、接线护栏

`app/src/test/.../WiringGuardTest.kt` 里有一批**源码断言**测试。它们不测行为，
只 grep 源码，确认某条接线还在：

```kotlin
assertTrue(
    "SettingsBackedProvider 必须 resolve FunctionSlot.CHAT",
    provider.contains("resolve(FunctionSlot.CHAT, settings)"),
)
```

为什么需要这种测试：`FunctionSlot.CHAT` 一度在枚举里、在设置页可见可编辑可保存，
但**全仓没有一处 resolve 它**。编译过、lint 过、运行时也不报错，只是改了没反应。
行为测试抓不到「压根没接线」，只有源码断言能。

### 四、错误必须可归因

两条硬规矩：

1. 不要用 `runCatching { }.getOrNull()` 把异常吞成 null 就完事 —— 至少把 message 留下来。
2. 降级要有提示。终端开会话失败退回宿主 shell 时，屏幕会直接写
   「当前不是 Linux 容器 / 原因：…」，而不是让你以为自己在容器里。

---

## 第三方协议

本项目 **MIT**。第三方组件清单、协议与来源见 [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)。

要点：

- **Miuix（`fan.miuix:*`）**：一组 AAR 内置在 `third_party/maven`，协议以上游声明为准
- **AndroidX / Kotlin / OkHttp**：Apache 2.0，从官方仓库拉取，不在仓库内分发
- **proot**：GPL v2，以独立可执行文件随 APK 分发、通过 `ProcessBuilder` 调用，不链接
- **Ubuntu / Kali rootfs**：用户设备上按需下载，不随 APK 打包

---

## 已知限制

如实列出，不是待办清单：

| 限制 | 原因 |
|---|---|
| release 包未签名 | 仓库不放 keystore |
| 只支持 OpenAI 兼容协议 | Anthropic / Gemini 原生协议未实现（可用中转站绕过） |
| 无 API Key 池 | 密钥是单个加密值，多 Key 轮询要改存储结构 |
| 环境页容器与终端 PTY 是两套会话 | 前者管道泵供后台服务，后者要 PTY；共用同一 rootfs 但进程不同 |
| 没有多配置档案 | 单一 `baseUrl` + 模型池，未做「配置 A / 配置 B」切换 |
| 部分附件类型仅 UI | 只有图片有下游消费方，其余显示为禁用态而非假装可用 |

---

## 许可

[MIT](LICENSE)。
