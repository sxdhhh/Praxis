# Local Dream 生图内置到 Praxis —— 完整方案

> 目标：把 `C:\creat-agent\main\local-dream-master` 的**端侧出图**能力内置进 Praxis，
> 做到「AI 能自己调」+「结果直接出现在对话里」。

---

## 0. 依据与边界

### 0.1 Local Dream 的客观清单

| 项 | 值 |
|---|---|
| 语言 / 体量 | **76 个 Kotlin 文件 / 约 1.2 MB 源码**；另有 C++（`.cpp` 2 个 / `.hpp` 33 个）与大量子模块 |
| 总仓库 | 19.2 MB（含 6 个 `.mnn` 共 13 MB、2 个 `.json` 共 3.5 MB） |
| ABI | **仅 `arm64-v8a`** |
| SDK | `minSdk 28` / `compileSdk 37` / `targetSdk 36` |
| 版本 | `3.0.0-alpha.7` |
| 许可证 | **CC BY-NC 4.0（Attribution-NonCommercial）** —— 见 §1 的红线 |
| 推理栈 | Qualcomm **QNN**（NPU）+ 阿里 **MNN**（CPU）+ **stable-diffusion.cpp**（happyyzy 分支）+ xtensor / tokenizers-cpp / zstd |
| 支持的模型 | **SD1.5**（NPU 需 Hexagon V68+）、**SDXL**（8 Gen 3+）、以及 DiT 系（Z-Image Turbo / FLUX.2-Klein / Qwen Image 2.1，需 SM8750+） |
| 子模块 | MNN、stable-diffusion.cpp、tokenizers-cpp、xtensor(-blas)、xtl、xsimd、zstd、cpp-httplib、nlohmann/json、stb（共 11 个） |

### 0.2 读到的关键文件

| 文件 | 行/体量 | 作用 |
|---|---|---|
| `service/BackendService.kt` | 33.1 KB | **引擎进程的生命周期与启动参数**（`ProcessBuilder`、`--model_dir`、`--listen_all`、端口 8081） |
| `service/BackgroundGenerationService.kt` | 29.9 KB | 后台生成：构造 `/generate` 请求、读 NDJSON 流、进度与完成回调 |
| `service/RemoteHostService.kt` / `remote/RemoteHostServer.kt` / `RemoteProtocol.kt` / `RemoteApiClient.kt` | 15.9 / 9.3 / 9.6 / 3.9 KB | 「本机当生成服务器」的控制面协议（Praxis 不一定要） |
| `data/DitEngine.kt` | 36 行 | DiT 引擎（`libdit_engine.so`）与设备门控（SM8750+） |
| `data/Model.kt` | **51.5 KB** | 模型目录定义（内置模型、参数默认值、分辨率） |
| `data/ModelStorage.kt` | 19.9 KB | 模型存储布局 `models/<id>/` + `embeddings/`，含跨存储移动 |
| `data/HistoryManager.kt` / `HistoryBackup.kt` | 10.9 / 9.9 KB | 历史记录（生成图持久化） |
| `data/Preferences.kt` | 11.5 KB | 生成偏好（步数、CFG、调度器、预览开关…） |
| `utils/ImageUtils.kt` | 18.2 KB | 位图/EXIF/base64 处理 |
| `data/ParamShare.kt` | 5.1 KB | 参数分享/解析（提示词+参数的序列化格式） |

### 0.3 没读的

- `app/src/main/cpp/`（native 主体：引擎、QNN 适配、SD 实现）—— 只从 Kotlin 侧确认了它的**对外行为**；
- `ui/screens/*`（界面实现）—— 只取了能力清单，没逐屏读；
- 模型本身的许可证（SD1.5 / SDXL / FLUX 各有自己的条款，见 §12）。

---

## 1. 三条结论

### 结论一：**引擎不移植，按原样当独立进程跑**

Local Dream 的推理是一个**原生可执行文件**，不是 JNI 库：

```
BackendService: ProcessBuilder([nativeLibraryDir/<engine>, "--model_dir", <models>, ..., "8081", "--listen_all"])
                    ↓ 引擎在 localhost:8081 上开 HTTP
BackgroundGenerationService: POST http://localhost:8081/generate  →  NDJSON 流
```

所以 Praxis 侧要做的不是「改 C++」，而是：
1. 把引擎与 QNN 库按 `lib*.so` 放进 `jniLibs/arm64-v8a`（**Praxis 已经在用这条通道**：`libproot.so`）；
2. 用 `nativeLibraryDir` 的绝对路径 `ProcessBuilder` 起它；
3. 用 OkHttp 打 `127.0.0.1:8081`。

**为什么这是最优解**：推理层的难点（QNN FastRPC、MNN、张量调度、量化的 SD 实现）一行都不用碰；
引擎与 App 之间只有一条 HTTP + NDJSON 的窄契约，崩了也只崩引擎进程。

### 结论二：**AI 通过工具调用**，不靠解析自然语言

Praxis 已有完整的工具层（`core:tool`）：`Tool { spec: ToolSpec(name, description, parametersJson); suspend execute(...) }`
+ `ToolRegistry` + 三档权限（`STANDARD` / `DEBUGGER` / `ROOT`）+ `ToolResult(content: String, isError, stage)`。

所以生图就是一个**普通工具** `generate_image`：模型在对话里决定调用它，参数走 JSON Schema，
结果以 JSON 文本回给模型（路径、尺寸、seed），模型再自然地描述它。

**不要**做成「让模型输出一段特殊标记再解析」—— 那是没有工具层时的将就做法，
Praxis 已经有结构化调用，没必要退回去。

### 结论三：**结果以 `ChatImage` 落进对话**，复用现有气泡渲染

Praxis 的 `ChatMessage` 已经带 `images: List<ChatImage>`，`MessageBubble` 也能解码 base64 并渲染。
所以生成出来的图不需要新控件，走同一条渲染路径即可。

### 1.1 引擎的三种接入形态（**先定这个，其余都随之**）

用户的要求是「做成可选包，点一下就能装」。这条要求撞上一个 Android 硬约束，必须先讲清：

> **API 29+ 不能执行应用可写目录里的二进制。**
> Praxis 自己的测试注释原话：`setExecutable(true, false)` 改不了它。可执行的目录只有 `nativeLibraryDir`。
> 也就是说「下载一个 exe 到 `filesDir` 然后 `ProcessBuilder` 起它」**必然失败**，
> 这不是权限能解决的问题（SELinux 的 W^X）。

于是「可选包」只有三种能落地：

| 形态 | APK 里放什么 | 「点击安装」怎么做 | 许可影响 | 可行性 |
|---|---|---|---|---|
| **A. 伴生 App**（用户装 Local Dream 本体） | **不含任何引擎代码** | 按钮跳下载页/商店 → 用户装完回来点「连接」 | ✅ 最干净：Praxis 不含 NC 代码 | ⭐⭐⭐ 立刻可做，Local Dream 本身就有 host 模式 |
| **B. 容器内安装**（吃掉 Praxis 已有的 proot） | 只有已有的 `libproot.so`（本来就在） | 按钮下载引擎 zip → 解进容器 rootfs → **由 proot 执行** | ✅ 相对干净：引擎是用户下载的数据，不是随包代码 | ⭐⭐ 机制成立（proot 能跑 rootfs 里的任意 ELF），但**性能与 NPU 未验证** |
| **C. 直接打包**（jniLibs 里放 `.so`） | 引擎二进制 | 无需安装 | ❌ 公开分发即违反 CC BY-NC | ⭐⭐⭐ 技术上最简单，只适合自己用 |

**推荐：A 为主、B 为可选、C 留一个构建开关。**

理由：
1. **A 已经零成本**：Local Dream 自带「本机当服务器」模式（`--listen_all` + `8811/8808` 控制面），
   Praxis 只要当一个客户端 —— 连**引擎有没有装**都不用管，更不用碰许可；
2. **B 是真的「点一下就装」**，且 APK 不膨胀；proot 已经在跑 Linux 程序，理论上引擎同理，
   但 ptrace 开销与 QNN/OpenCL 的设备节点访问**必须实机验证**，不要先承诺；
3. **C 留给「自己用」**：把三个 `.so` 丢进 `jniLibs/arm64-v8a` 就能跑，不需要用户装任何东西。

### 1.2 代码上怎么统一：`EngineSource` 三态

**同一套 client 代码，三种来源**（`ImageGenClient` 只认 `127.0.0.1:<port>`）：

```kotlin
enum class EngineSource {
    /** A：外部 App 提供的引擎（Local Dream host 模式或任何兼容实现）。Praxis 不启动进程。 */
    External,
    /** B：容器内引擎。由 proot 在 rootfs 里启动，Praxis 只等端口就绪。 */
    Container,
    /** C：随包引擎。`EngineProcess` 从 nativeLibraryDir 起进程。 */
    Bundled,
}
```

| | External (A) | Container (B) | Bundled (C) |
|---|---|---|---|
| 谁启动进程 | 另一个 App | proot（Praxis 侧只是 `proot ... /root/engine`） | `EngineProcess` 自己 |
| 端口 | `127.0.0.1:8081`（对方用 `--listen_all` 时可达） | `127.0.0.1:8081`（同一网络命名空间） | `127.0.0.1:8081` |
| 就绪探测 | 轮询 `/health` + `GET :8808/info` 确认是哪个 App | 轮询 `/health` + 看 proot 进程是否活着 | 轮询 `/health` + 端口归属探测 |
| 模型目录 | 对方 App 管 | 容器 rootfs 内 | `filesDir/models` |
| 许可 | 无牵连 | 引擎是用户下载的数据 | ⚠️ 违反 NC（若分发） |

**关键设计**：`EngineLocator` 负责「现在能用哪种形态」，按 `External → Container → Bundled` 顺序探测，
全都没有就返回 `Unavailable` 并把三种办法都摆给用户（见 §7.4）。
这样同一份 APK 在三种设备状态下都能工作，而不是编译期二选一。

### 1.3 「一键安装」的 UX 状态机

```
设置 → 生图
├── 已连接（A/B/C 任一可用）
│     └── 显示来源徽标：外部应用 / 容器 / 内置   + 模型数 + [测试生成]
└── 未检测到引擎
      ├── [ 安装 Local Dream ]      → 打开下载页（A，推荐）
      ├── [ 在容器里安装引擎 ]      → 下载 zip → 解到 rootfs → proot 启动（B）
      └── [ 载入内置引擎 ]          → 仅 Bundled 构建可见（C）
           装完后自动重探，无需重启 App
```

**三条硬要求**
1. **每步都有明确结果**：装完要说「已连接，检测到 1 个模型」，不能只 toast「成功」；
2. **不静默降级**：A 不可用就直说「没装 Local Dream」，不要让用户以为在生成；
3. **可随时切源**：设置页能手动指定用哪种形态（调试用），默认自动。

### 1.4 引擎二进制：**已从官方 APK 提取**（实测数据）

来源：`C:\Users\sxd\Downloads\LocalDream_armv8a_2.8.1.apk`（**54.0 MB**）

| 内容 | 路径 | 大小 |
|---|---|---|
| **引擎** | `lib/arm64-v8a/libstable_diffusion_core.so` | **11.1 MB** |
| QNN 运行时（6 套 Hexagon 版本） | `assets/qnnlibs/libQnnHtpV{68,69,73,75,79,81}{,Skel,Stub}.so` | 每套约 20~22 MB，合计约 **127 MB** |
| QNN 公共库 | `assets/qnnlibs/libQnnHtp.so`（2.3 MB）、`libQnnSystem.so`（2.3 MB） | 4.6 MB |
| assets 合计（解压后） | 28 个条目 | **137.0 MB** |

已解到 `C:\creat-agent\work\ld2108\`（引擎 + 全部 qnnlibs）备用。

**体积账（决定内置能不能接受）**

| 方案 | APK 增量 |
|---|---|
| 只带引擎 + 单套 QNN（限定机型） | 约 11.1 + 21 = **32 MB** |
| 引擎 + 全部 6 套 QNN（Local Dream 的做法） | 压缩后约 **40 MB**，解压后 137 MB 占存储 |

> **QNN 的正确做法**（Local Dream 就是这样）：APK 里带全部版本，
> 运行时**只把匹配本机 Hexagon 版本的那一套**解到 `filesDir`，不要全解。
> 解压目标目录还要带版本戳，升级时整目录删掉重解。
### 1.4.1 自编 3.0.0 引擎：**已编成**（2026-11-08）

| 项 | 结果 |
|---|---|
| 产物归档 | `C:\creat-agent\work\engine-3.0.0\`（**167.5 MB**） |
| 引擎 | `arm64-v8a/libstable_diffusion_core.so` **11.42 MB**，ELF64 / AArch64 / PIE |
| QNN 运行时 | `qnnlibs/` **20 个文件 / 156.1 MB**（QnnHtp + QnnSystem + V68/69/73/75/79/81 各 3 件） |
| 源码 | `C:\creat-agent\work\local-dream-git`，tag `v3.0.0-alpha.7`（HEAD `2f1f3ba`），11 个子模块全部对齐钉位 |
| 工具链 | NDK `28.2.13676358` + QAIRT `2.50.40.260831` + Rust `1.88.0-x86_64-pc-windows-gnu` |
| 复现命令 | `.\build-engine.ps1 -QnnSdkRoot <QAIRT 路径>` |
| **未产出** | `libdit_engine.so`（DiT 引擎）—— 见下 |

**编的过程中踩了 4 个坑，全部已修**（照抄结论，别重踩）：

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `CMakeLists.txt:20` 报 SampleApp 补丁打不上（3 文件全失败） | Windows 版 QAIRT 的 SampleApp 是 **CRLF**，补丁按 LF 生成 | `git apply` 加 **`--ignore-whitespace`**（实测 10 个 hunk 干净应用，只有行尾差异） |
| 2 | `libtokenizers_c.a` 构建失败，报「系统找不到指定的文件」 | ninja 起的是 `cmd.exe`，**PATH 里没有 cargo** | 构建脚本把 `%USERPROFILE%\.cargo\bin` 加进 PATH |
| 3 | tokenizers-c 报 `implicit autoref ... raw pointer`（硬错误） | 本机 rustc **1.99**，而项目 MSRV 是 **1.88**，新编译器把旧写法判成 error | 装 `1.88.0-x86_64-pc-windows-gnu` + `aarch64-linux-android` target，设 `RUSTUP_TOOLCHAIN` |
| 4 | `foreach(arch … 85)` 拷不到 hexagon-v85 | 本地 QAIRT 2.50.40 只到 v81 | 去掉 85（**源码零引用**，只影响 V85 机型 NPU；2.8.1 正式版也只到 V81） |

**为什么 zip 不能用**：`CMakeLists.txt` 会 `git apply SampleApp.patch`，没有 `.git` 直接失败；且 zip 里 11 个子模块全是空目录、钉位也丢了。

**DiT 引擎（`libdit_engine.so`）单独一个 CMake 工程**（`cpp/dit/CMakeLists.txt`），
配置时报 `Make sure HEXAGON_SDK_ROOT point to the correct Hexagon SDK installation` ——
它需要 **Qualcomm Hexagon SDK**（与 QAIRT 是两个不同的包）。缺它只影响 **Z-Image Turbo / FLUX.2-Klein / Qwen Image 2.1**，
而且那几类模型还要 SM8750+ 机型；SD1.5 / SDXL 的主线**不受影响**。
### 1.5 ⚠️ 版本差异：**APK 是 2.8.1，源码是 3.0.0-alpha.7**

这是本次最容易埋雷的地方。我把 2.8.1 的引擎二进制抽出来做了字符串核对，
**它的契约与本地那份 3.0.0-alpha.7 源码并不一致**：

| 项 | 2.8.1（**二进制实测**） | 3.0.0-alpha.7（源码） |
|---|---|---|
| `--type` 可选值 | `sd15cpu` / `sd15npu` / `sdxl` / `anima` | 另有 `sdxlmnn` 与 DiT 系（`zimage` 等） |
| `--qnn_lib_dir` | **不存在** | 有（DiT 拆分后需要） |
| `libdit_engine.so` | **没有** | 有（Z-Image / FLUX.2 / Qwen Image） |
| 请求字段 `reference_images` | **不存在** | 有（参考图编辑） |
| 内建模型转换 | **有**：`--convert <dir> [--clip_skip_2]`（safetensors → MNN） | 有 |
| `--anima_seq_dit` | 有（anima+lowram，12 GB 设备用） | 有 |
| `ultrafix` / `tile_size` | 有 | 有 |

**因此**：

1. 若用 2.8.1 的引擎，**实现必须按上一列的实测契约**，不要照抄 3.0.0 源码里的 `--qnn_lib_dir` / `reference_images`；
2. 若要用 3.0.0 的能力（参考图编辑、DiT 系模型），得换 3.0.0 的 APK/引擎；
3. 建议把「引擎版本」做成运行时探测（`GET /health` 或启动日志里的版本串），
   **能力位按版本降级**，而不是编译期假定。
### 1.6 三种形态各自的坑

| 形态 | 坑 | 对策 |
|---|---|---|
| A 外部 | 对方 App 要在前台/被系统杀；8081 只在对方开 host 模式时才听 | 连接前先 `GET /health`；断开后 UI 明确显示「引擎离线」 |
| A 外部 | 用户装的是旧版本协议 | `GET :8808/info` 读 `protocol` 字段，不匹配就提示升级 |
| B 容器 | **proot 的 ptrace 开销**（计算密集型影响待实测） | P0 只做「能出图」；慢就退 A/C |
| B 容器 | **NPU/OpenCL 设备节点**（`/dev/adsprpc-smd`）能否透传 | 标为**未验证**；先只支持 CPU 后端，NPU 另测 |
| B 容器 | 内核/glibc 版本与引擎要求的匹配 | 容器 rootfs 版本要与引擎编译环境对齐 |
| C 内置 | 违反 CC BY-NC | 仅自用构建；发布版删掉 `jniLibs` 里那份即可 |

---

### ⚠️ 红线：许可证 CC BY-NC 4.0
### ⚠️ 红线：许可证 CC BY-NC 4.0

| 事实 | 后果 |
|---|---|
| Local Dream 是 **CC BY-NC 4.0**（禁止商业使用） | **内置进 Praxis 只能自用**；一旦随 App 分发（应用商店/公开下载），就违反 NonCommercial |
| Praxis 侧先前已决定转 **AGPL-3.0** | AGPL 不允许在协议上叠加额外限制；CC BY-NC 的「禁商用」正是一条额外限制 → **两者不能合并分发** |
| 模型权重另有许可 | SD1.5 是 CreativeML OpenRAIL-M、SDXL 是 OpenRAIL++、FLUX 系有自己的条款；**随 App 分发权重**要逐一看 |

**结论**：这条红线**只对形态 C（随包内置）生效**。形态 A（连外部 App）与 B（容器内安装）不把 NC 代码放进 Praxis 的 APK，因此不触发——
这也是 §1.1 把 A 列为推荐形态的原因。
如果将来要公开发布，只有三条路：① 只内置**自研/宽松许可**的推理实现；② 把引擎做成**用户自行安装**的可选包；③ 干脆不做本地生图，改走网关的 `/v1/images/*`（我们也刚接完）。

---

## 2. 两端现状对照

### 2.1 Local Dream 的分层（读代码得出）

| 层 | 内容 | 能不能直接搬 |
|---|---|---|
| 推理层 | 原生可执行 + QNN/MNN/SD.cpp | ✅ **整块搬**（二进制，不改） |
| 进程管理 | `BackendService`（启动参数、就绪判定、停止宽限、端口占用探测） | ✅ 逻辑可照抄 |
| 协议层 | `/generate` + NDJSON、`/tokenize`、`/health` | ✅ 原样用 |
| 模型层 | `Model.kt`（目录）+ `ModelStorage`（布局/移动/空间） | ⚠️ 目录定义可简化，存储布局要照抄（引擎认这个路径） |
| 历史层 | `HistoryManager` + Room | ✅ 可复用思路 |
| UI 层 | Compose 屏幕 | ❌ 不搬（Praxis 有自己的样式体系） |
| 远程控制 | `RemoteHostServer` / 控制面协议 | ❌ 不搬（Praxis 不需要手机当服务器） |

### 2.2 Praxis 侧的现成积木

| 需求 | Praxis 已有 | 位置 |
|---|---|---|
| AI 调用工具 | `Tool` / `ToolSpec` / `ToolRegistry` / 三档权限 | `core/tool/` |
| 原生可执行从 APK 跑 | `libproot.so` 走 `nativeLibraryDir` | `app/src/main/jniLibs/arm64-v8a/` |
| 前台服务（长任务） | `ScreenCaptureService` 已有 `startForeground` 先例与 manifest 声明 | `app/data/attachment/ScreenCapture.kt` |
| 消息带图 | `ChatMessage.images: List<ChatImage>` | `core/ai/AiContract.kt` |
| 气泡渲染图 | `MessageBubble` 里 `BitmapFactory.decodeByteArray` + `Image(...)`，且带位图缓存 | `feature/chat/widget/MessageBubble.kt` |
| 图片保存到相册 | 已有（附件/截图相关） | `app/data/attachment/` |
| HTTP + 流式读取 | OkHttp 4.12 + 现有 SSE 解析 | `app/data/ai/` |
| 设置页骨架 | `SettingsSectionHeader` + `Card` + Miuix 控件 | `feature/settings/` |

> 结论：**Praxis 侧几乎没有新地基要打**，缺的只有「引擎进程封装」与「生图设置页」。

---

## 3. 引擎接口（最关键的一节，照抄即可）

### 3.1 启动参数（**2.8.1 引擎内建 usage，实测**）

引擎 `--help` 的输出就在二进制里（我把它抽出来了），**这是权威版本**：

```
Usage:
  stable_diffusion_core --type <sd15cpu|sd15npu|sdxl> --model_dir <dir> [--lib_dir <dir>] [options]
  stable_diffusion_core --upscaler_mode [--lib_dir <dir>] [options]
  stable_diffusion_core --convert <dir> [--clip_skip_2]

Modes:
  --type <type>          Model format: sd15cpu (MNN), sd15npu (QNN), sdxl (QNN), anima (QNN)
  --upscaler_mode        Upscale-only server, no diffusion model
  --convert <dir>        Convert model.safetensors in <dir> to MNN and exit
Paths:
  --model_dir <dir>      Directory with the fixed per-type model files
  --lib_dir <dir>        Directory with libQnnHtp.so / libQnnSystem.so (QNN types)
  --patch <file>         zstd resolution patch for unet.bin (sd15npu)
  --safety_checker <f>   NSFW checker MNN model
Options:
  --port <n>             HTTP port (default 8081)
  --listen_all           Listen on 0.0.0.0 instead of 127.0.0.1
  --no_img2img           Do not load the VAE encoder
  --use_v_pred           v-prediction model
  --lowram               (sdxl/anima) load/release models per stage
  --anima_seq_dit        (anima+lowram) never keep both DiT halves resident; for 12GB devices
  --clip_skip_2          (convert) export CLIP with skip 2
  --log_level <n>        QNN log level
  --version              Print QNN SDK build id
  --help                 Show this help
```

**Praxis 的起法**（形态 C）：

```kotlin
val engine = File(applicationInfo.nativeLibraryDir, "libstable_diffusion_core.so")
val command = listOf(
    engine.absolutePath,
    "--type",      "sd15npu",                       // 或 sd15cpu / sdxl / anima
    "--model_dir", File(modelsRoot, modelId).absolutePath,
    "--lib_dir",   runtimeDir.absolutePath,          // 解出来的 QNN（单版本）
    "--port",      "8081",
    "--patch",     File(modelsDir, "512x768.patch").absolutePath,  // 仅 sd15npu 非 512
    "--lowram",                                        // 仅 sdxl / anima
)
ProcessBuilder(command).redirectErrorStream(true).start()
```

**两个容易忽略的点**

1. `--patch` 是 **zstd 压缩的分辨率补丁**（补 `unet.bin`），文件名形如 `512x768.patch`；
   **文件不存在时 Local Dream 回落到 512 且不报错** —— Praxis 要自己显式检查并提示；
2. `--lib_dir` 指向的目录必须同时有 `libQnnHtp.so` 与 `libQnnSystem.so`（实测报错文案：`Missing --lib_dir for QNN`）。
### 3.2 `POST /generate` —— 请求字段全表

| 字段 | 类型 | 说明 |
|---|---|---|
| `prompt` | string | 正向提示词 |
| `negative_prompt` | string | 负向提示词 |
| `steps` | int | 步数 |
| `cfg` | float | CFG scale |
| `width` / `height` | int | 像素尺寸 |
| `scheduler` | string | 调度器（见引擎支持的枚举） |
| `seed` | long? | 省略 = 随机；结果里会回传实际 seed |
| `denoise_strength` | float | 图生图的去噪强度（文生图一般 1.0） |
| `use_opencl` | bool | GPU 后端开关 |
| `preview_format` | string | 每步预览编码，`jpeg`（小）或原始 RGB（大） |
| `show_diffusion_process` | bool | 是否推每步预览帧 |
| `show_diffusion_stride` | int | 每 N 步推一帧 |
| `aspect_ratio` | string | 走「固定长边 + 比例」时的档位 |
| `image` | string? | 图生图的初始图（base64） |
| `mask` | string? | 局部重绘蒙版（base64） |
| `reference_images` | array? | 参考图（多张，base64）—— ⚠️ **仅 3.0.0 引擎有；2.8.1 实测无此字段** |
| `ultrafix` / `tile_size` | bool / int | 分块超分模式（注意：该模式**不推预览帧**） |
| `output_format` | string | 完成图的编码，`jpeg` 或原始 RGB |

### 3.3 响应：**NDJSON 流**（一行一条 JSON 消息）

| `type` | 字段 | 用途 |
|---|---|---|
| `progress` | `progress`(0~1)、`image`(base64，可选) | 进度条 + 每步预览。**预览可关**（省内存/带宽） |
| `complete` | `image`(base64)、`seed`、`width`、`height` | 最终图。`output_format=jpeg` 时是 JPEG，否则是原始 RGB 字节 |

读法（与 `BackgroundGenerationService` 一致）：`BufferedReader.readLine()` 逐行 → `JSONObject` → 按 `type` 分发。
**不要**用 SSE 解析器（这不是 `data:` 前缀的 SSE）；也**不要**一次性 `readBytes()`（会吃掉整个生成过程的进度）。

### 3.4 其余端点（2.8.1 二进制里实测到的全集）

| 端点 | 用途 |
|---|---|
| `GET /health` | 就绪探测 |
| `POST /generate` | 出图（NDJSON 流） |
| `POST /tokenize` | 分词（提示词长度预检） |
| `POST /upscale` | 超分（`--upscaler_mode` 时的主要入口） |
| `GET /finished` | 引擎侧任务结束标记（Local Dream 的停止流程会用到，Praxis 可忽略） |

> 实测方式：对 `libstable_diffusion_core.so` 提取 ASCII 串，命中 `"/generate"` `"/health"` `"/tokenize"` `"/upscale"` `"/finished"`。


| 端点 | 用途 |
|---|---|
| `GET /health` | 就绪探测 |
| `POST /tokenize` | 分词（提示词长度预检：超长会被 CLIP 截断，最好提前告诉用户） |

### 3.5 Praxis 不需要的部分

`RemoteHostServer` + 控制面（`/info` `/models` `/select` `/status` `/stop`，端口 8808）是「手机 A 给手机 B 当服务器」用的。
Praxis 自己就是唯一调用方，**全部不要**；只保留引擎自己的 8081。

---

## 4. 数据模型

### 4.1 模型包布局（**必须与引擎约定一致，不能改**）

```
<root>/
├── models/
│   └── <model id>/             # 每个模型一个目录
│       ├── ...                 # 引擎认的权重与配置（MNN 包 / QNN 权重）
├── embeddings/                 # 文本反演 .safetensors
└── .nomedia                    # 别让相册扫描几个 GB
```

⚠️ **`embeddings/` 必须与 `models/` 同级**：`ModelStorage.kt` 的注释写明「引擎会去 `--model_dir` 的上两级找 embeddings」。
挪位置就等于 embedding 全部失效，而且不报错。

`<root>` 有两种选择（Local Dream 都支持）：

| 位置 | 权限 | Praxis 建议 |
|---|---|---|
| App 私有目录 `filesDir/models` | 无需权限 | ✅ **默认**（卸载即清，隐私更好） |
| `Download/LocalDream` | 需要 **All files access**（API 30+） | 可选（模型几个 GB 时方便用户自己管理） |

> Praxis 建议**先只做私有目录**：`MANAGE_EXTERNAL_STORAGE` 是要上架审核的高危权限，
> 为了「模型放 Download」付这个代价不值当。

### 4.2 生成参数

```kotlin
data class ImageGenParams(
    val prompt: String,
    val negativePrompt: String = "",
    val steps: Int = 20,
    val cfg: Float = 7f,
    val width: Int = 512,
    val height: Int = 512,
    val scheduler: String = "dpm",
    val seed: Long? = null,          // null = 随机，结果回传实际值
    val denoise: Float = 1f,         // 图生图 <1
    val useOpenCL: Boolean = false,
    val showPreview: Boolean = false,
    val previewStride: Int = 1,
)
```

### 4.3 历史（Room，建议）

```kotlin
@Entity(tableName = "image_gen")
data class ImageGenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val modelId: String,
    val prompt: String, val negativePrompt: String,
    val steps: Int, val cfg: Float, val seed: Long,
    val width: Int, val height: Int, val scheduler: String,
    val filePath: String,            // 图落磁盘，库里只存路径
    val thumbPath: String?,          // 列表用缩略图
    val durationMs: Long,
    val sourceMessageId: String?,    // 由哪条对话产生（可回跳）
)
```

**图不进数据库**：一张 1024×1024 JPEG 约 300 KB，但 base64 会再涨 37%，且 SQLite 里的大 BLOB 会拖慢整库。
对话里的 `ChatImage` 用**缩略图**（长边 ≤ 512）内联，原图只在历史表里留路径。

### 4.4 DataStore 键

```
imagegen_enabled          bool      总开关（关掉 = 工具不注册、设置页只剩说明）
imagegen_model_id         string    当前模型
imagegen_backend          string    cpu | opencl | npu（npu 看机型）
imagegen_width/height     int       默认画布
imagegen_steps            int       默认步数
imagegen_cfg              float     默认 CFG
imagegen_scheduler        string    默认调度器
imagegen_negative         string    默认负向
imagegen_show_preview     bool      对话里是否显示每步预览（默认关，省电）
imagegen_auto_save        bool      生成后自动存相册
imagegen_target           string    生图槽位的选择（JSON：{kind:local,modelId} 或 {kind:remote,provider,baseUrl,model}）
imagegen_base_url         string    模型下载源（默认官方；国内镜像）
imagegen_wifi_only        bool      仅 Wi-Fi 下载（默认 true）
imagegen_parallel         int       并发下载数（默认 2）
imagegen_soc_suffix       string    本机芯片档位缓存（Build.SOC_MODEL → 8gen3/min…）
```

---

## 5. 工具设计（AI 怎么调）

### 5.1 声明与参数（**AI 要能调参，不是只能出图**）

工具分两个：**`generate_image`（本次生成）** 与 **`set_image_gen_config`（改配置）**（见 §5.6）。
把「这次画大一点」和「以后都这么画」分成两件事，模型就不会用一次性的参数悄悄改掉用户的全局设置。

```kotlin
object GenerateImageTool : Tool {
    override val spec = ToolSpec(
        name = "generate_image",
        description = "在本机或通过服务商生成图片。用户要求画图/出图时调用。" +
                      "这些参数只对本次生效；要改默认值请用 set_image_gen_config。" +
                      "返回图片的本地路径与 seed，不要编造画面内容。",
        parametersJson = SCHEMA,
        tier = PermissionTier.STANDARD,
        risk = ToolRisk.Moderate,
    )
}
```

**完整参数表**（都是引擎真支持的，不是凑数）：

| 参数 | 类型 | 范围 | 说明 |
|---|---|---|---|
| `prompt` | string | — | **必填**；正向提示词（英文更佳） |
| `negative` | string | — | 负向提示词 |
| `width` / `height` | int | 256~1024 | 须匹配当前模型可用分辨率（见下） |
| `steps` | int | 1~60 | 步数 |
| `cfg` | number | 1~20 | CFG scale |
| `scheduler` | string | 枚举 | 调度器；**只在本地目标有效** |
| `seed` | int | — | 固定种子；省略 = 随机（结果里回传实际值） |
| `denoise` | number | 0~1 | 仅图生图（P5） |
| `count` | int | 1~4 | 连出几张（默认 1；每张都算一次生成） |

```json
{
  "type": "object",
  "properties": {
    "prompt":    { "type": "string" },
    "negative":  { "type": "string" },
    "width":     { "type": "integer", "minimum": 256, "maximum": 1024 },
    "height":    { "type": "integer", "minimum": 256, "maximum": 1024 },
    "steps":     { "type": "integer", "minimum": 1, "maximum": 60 },
    "cfg":       { "type": "number",  "minimum": 1, "maximum": 20 },
    "scheduler": { "type": "string", "enum": ["dpm", "euler", "euler_a", "lcm"] },
    "seed":      { "type": "integer" },
    "count":     { "type": "integer", "minimum": 1, "maximum": 4, "default": 1 }
  },
  "required": ["prompt"]
}
```

**为什么 `scheduler` 要标注「只在本地有效」**：远端 OpenAI 兼容的 images 端点没有这个概念，传了也不生效。
与其让模型以为调了有用，不如在 description 里说清。

**分辨率必须校验**：SD1.5 的 NPU 只能跑 patch 覆盖的档位（512 / 512x768 …），
请求一个没有 patch 的尺寸会**静默回落 512**。所以工具入口先按当前模型的可用档位校验，
不合法就返回 `invalid_size` 并列出可用档位 —— 而不是悄悄画出一张尺寸不对的图。
### 5.2 返回值（`ToolResult.content`）

`content` 是**给模型读的文本**，所以用紧凑 JSON：

```json
{ "ok": true, "path": "/data/.../image_gen/1730_12.jpg",
  "width": 512, "height": 512, "seed": 2001048, "model": "sdxl-base",
  "ms": 18432 }
```

失败时**不要抛异常**（`Tool` 契约明确要求），按 `ToolResult(content, isError = true)` 返回可读原因：

| 情况 | 返回 |
|---|---|
| 没装模型 | `{"ok":false,"error":"no_model","hint":"先在设置→生图里下载一个模型"}` |
| 设备不支持 | `{"ok":false,"error":"unsupported_device"}` |
| 显存/内存不足 | `{"ok":false,"error":"oom","hint":"把尺寸降到 512"}` |
| 引擎启动失败 | `{"ok":false,"error":"engine_start_failed","detail":"…"}` |

**为什么要区分 `no_model` / `oom` / `unsupported_device`**：模型看到笼统的「失败了」只会重试同样的参数；
看到 `oom` 才会自己把尺寸调小。这与网关那边「错误分类决定重试策略」是同一条经验。

### 5.3 工具注册条件

```kotlin
if (settings.imagegenEnabled && ImageGenAvailability.isUsable(context)) {
    registry.register(GenerateImageTool(...))
}
```

**不要无条件注册**：没装模型、设备不支持时注册一个必然失败的工具，会让模型每次被问到画图都白调一次。

### 5.4 按「生图」槽位分发（本地 / 远端）

工具本身**不知道**图从哪来 —— 它只读 `imagegen_target`（见 §7.3）然后分发：

```kotlin
suspend fun execute(args: String, ctx: ToolContext): ToolResult {
    val target = settings.imageGenTarget() ?: return noTargetError()
    return when (target) {
        is ImageGenTarget.Local  -> localEngine.generate(target.modelId, params)   // §3 的 8081 引擎
        is ImageGenTarget.Remote -> remoteImages.generate(target, params)          // /v1/images/generations
    }
}
```

| 目标 | 走哪条 | 注意 |
|---|---|---|
| `Local` | 本机引擎（§3），NDJSON 流 | 要先确保引擎已就绪；换模型要重启（§8.6） |
| `Remote` | 用户 provider 的 OpenAI 兼容 images 端点；**若那台是 Agent2API 网关，就是 `/v1/images/generations`** | 非流式 JSON；结果可能是 `b64_json` 或 URL，两种都要处理 |

**两条路的结果都要落到同一种东西**：一个本地文件路径 + 一张缩略图 `ChatImage`（§6.3）。
这样聊天里看不出差别，也不用在 UI 上写两套。

**远端失败也要分类**（与本地同一套语义）：`no_api_key` / `model_not_image_capable` / `quota_exceeded` / `content_blocked`。
网关那侧本来就有这些分类（`supports_image_generation`、内容拦截），直接映射即可。
### 5.5 模型怎么「知道」图长什么样

这是最容易做错的地方。工具返回的是**文本**，图在 UI 里显示。所以：

1. 工具结果里给**可读的描述性事实**（尺寸、seed、耗时），不给 base64；
2. 图片以 `ChatImage`（缩略图）挂到**同一条 assistant 消息**上，供 UI 渲染；
3. 系统提示词里加一句「你刚生成了一张图，路径在结果里；描述它时只描述你能确定的内容」。

否则会出现「模型言之凿凿地描述一张它没看见的图」——多模态模型能看图，但**生图工具的输出不会自动回喂**，
除非我们再发一轮带图的消息。若以后要做「看图改图」，那一步要显式加。

---

### 5.6 让 AI 改配置：`set_image_gen_config`（+ 只读的 `list_image_gen_models`）

只让模型「出图」不够 —— 用户会说「以后都画大一点」「换成那个动漫模型」「步数别那么高」。
这些是**持久配置**，语义与 `generate_image` 的一次性参数不同，所以单独一个工具。

#### `set_image_gen_config`

| 参数 | 类型 | 说明 |
|---|---|---|
| `target` | object | `{kind:"local", modelId}` 或 `{kind:"remote", provider, model}` —— 切换生图目标 |
| `width` / `height` | int | 默认画布 |
| `steps` / `cfg` | int / number | 默认采样参数 |
| `scheduler` | string | 默认调度器（仅本地目标） |
| `negative` | string | 默认负向提示词 |
| `autoSave` | bool | 生成后自动存相册 |
| `showPreview` | bool | 是否显示每步预览 |

```json
{
  "type": "object",
  "properties": {
    "target":     { "type": "object" },
    "width":      { "type": "integer", "minimum": 256, "maximum": 1024 },
    "height":     { "type": "integer", "minimum": 256, "maximum": 1024 },
    "steps":      { "type": "integer", "minimum": 1, "maximum": 60 },
    "cfg":        { "type": "number",  "minimum": 1, "maximum": 20 },
    "scheduler":  { "type": "string", "enum": ["dpm", "euler", "euler_a", "lcm"] },
    "negative":   { "type": "string" },
    "autoSave":   { "type": "boolean" },
    "showPreview":{ "type": "boolean" }
  },
  "required": []
}
```

**只传要改的字段**（`required: []`）—— 模型说「步数改成 30」时不该被迫把别的也报一遍。

#### 返回值：给 diff，不是给「已保存」

```json
{ "ok": true, "changed": {
    "steps":  { "from": 20,   "to": 30 },
    "target": { "from": "local:anythingv5", "to": "local:cuteyukimix" }
} }
```

**为什么要 diff**：模型需要据此回复用户（「已经把步数从 20 改成 30」）。只回 `ok` 的话，
模型只能含糊地说「改好了」，而用户无法核对 —— 这与「不要谎报验证状态」是同一条原则。

#### 边界：**哪些不许 AI 改**

| 不许改 | 为什么 |
|---|---|
| API Key / 服务商凭证 | 凭据属于账户层；让模型能改凭据等于给它一个外发数据的通道 |
| 下载源 baseUrl | 网络面 + 安全面（能把模型下载指向任意主机） |
| 引擎来源（外部/容器/内置） | 是运行形态，不是画图参数 |
| 删除模型 / 下载模型 | 破坏性或大流量操作，必须由用户点 |

越界的请求一律返回 `{"ok":false,"error":"not_allowed","hint":"这类设置请用户在设置页改"}`。

#### `list_image_gen_models`（只读，让模型先看再选）

`risk = ToolRisk.Safe`、无参数，返回：

```json
{ "local":  [ { "id": "anythingv5", "name": "AnythingV5", "backend": "sd15npu",
                 "sizes": [[512,512],[512,768]], "installed": true } ],
  "remote": [ { "provider": "agent2api", "model": "flux-schnell", "installed": true } ],
  "current": { "kind": "local", "modelId": "anythingv5" } }
```

**没有这个工具会怎样**：模型只能盲猜模型 id，或者反复试探 `invalid_target`。
有了它，「换个模型」这类请求才能一次做对。
## 6. 聊天里怎么呈现

### 6.1 生成中

| 阶段 | UI |
|---|---|
| 引擎启动（首次 3~10 s） | 气泡里一个「正在准备模型…」的小行 + 不确定进度条 |
| 生成中 | 进度条（`progress` 消息）+ 可选每步预览（`preview_format=jpeg`，小图） |
| 完成 | 缩略图替换进度条，底下三行小字：`512×512 · 20 steps · seed 2001048 · 18.4s` |
| 失败 | 红色一行 + 「重试」按钮（带着原参数重发） |

**进度必须显示**：20~60 秒没有任何反馈，用户会以为卡死并杀掉 App。

### 6.2 完成后

复用 `MessageBubble` 现有的图片渲染路径（`BitmapFactory.decodeByteArray` + `Image`），所以：

- 图**内联**在 assistant 消息里（和用户上传图同款观感）；
- 长按 → 「保存到相册 / 复制提示词 / 用这个 seed 再来一张 / 以图生图」；
- 点击 → 全屏查看（Praxis 已有图片查看器的话直接用）。

### 6.3 base64 还是文件路径？

| 用途 | 存什么 | 理由 |
|---|---|---|
| 对话里的展示 | **缩略图 base64**（长边 ≤ 512，JPEG q80） | `ChatMessage.images` 的既有约定就是 base64；缩略图约 30~60 KB，可接受 |
| 原图 | **文件路径**（`filesDir/image_gen/`） | 原图 1~2 MB，且在历史里可复查、可分享；不进 DB、不进消息 |
| 相机/相册来源的输入图 | 仍是 base64（既有行为） | 不改动现有约定 |

> 一句话：**消息里放缩略图，原图落磁盘**。这与 Praxis 现有「发送的图存 base64」并不冲突 ——
> 那是用户主动发的（要固定内容），这是本地生成的（可随时从磁盘重取）。

---

## 7. UI 落点（沿用 Praxis 现有样式与控件）

### 7.0 UI 实现约束（硬性，先读这条）

#### ① 一律 Miuix，不自写控件

| 用途 | 用什么 |
|---|---|
| 分区 | `SettingsSectionHeader`（图标 + 标题 + 副标题）+ `Card` —— 与 `ModelScreen` 完全一致 |
| 文本 / 输入 | miuix `Text` / `TextField`，或项目的 `MiuixTextField` |
| 数值 | `MiuixNumPickerWidget`（步数 / CFG / 尺寸） |
| 下拉 | `MiuixDropDownItem` / `MiuixSmallTitleDropDown`（调度器、后端、模型） |
| 选择行 | `MiuixSelectableRow`；多选列表 `MiuixDraggableList`（拖动排序） |
| 状态徽标 | `MiuixBadge`（NPU / CPU / 已下载 / 需下载） |
| 进度 | `ProgressButton`（下载按钮内嵌进度）、`MiuixBlockingLoadingDialog`（解压） |
| 弹窗 / 浮层 | `MiuixDialog` / `MiuixAnimatedDialog` / `MiuixAnimatedPopup` |
| 下拉刷新 | miuix `PullToRefresh` |
| 顶部栏 | miuix `SmallTopAppBar`（右上角齿轮放这里） |

**禁止**：自写 `Canvas` 画控件、自定颜色 / 圆角 / 阴影、自写弹窗与动画。

#### ② 开关必须用项目封装（= `LiquidToggle`）

```kotlin
// 对：项目里的 Switch 封装，内部就是 LiquidToggle（液态玻璃滑块）
Switch(checked = ..., onCheckedChange = ...)

// 错：直接用 miuix 的 Switch —— 观感与其它设置页不一致
top.yukonga.miuix.kmp.basic.Switch(...)
```

理由：`MiuixBaseWidget` 已经把 miuix Switch 换成液态玻璃开关，全 App 26 处调用点都走它；
生图页新加开关（仅 Wi-Fi 下载、自动存相册、显示每步预览、预热）必须跟着走同一条路。

**按钮不用液态玻璃**（`LiquidGlassButton` 零调用点，本项目不引入）：用 miuix `Button` / `TextButton`，
带进度的用 `ProgressButton`。

#### ③ 颜色走主题（莫奈自动跟随）

只用 `MiuixTheme.colorScheme.*`。**任何写死的 `Color(0x…)` 都算 bug** ——
项目已接 `ColorSource.Wallpaper`（= 莫奈动态取色）+ `materialKolor 5.0.0`，换壁纸整套页面跟着变。

#### ④ 滚动：单一 `LazyColumn`，别嵌套

```kotlin
LazyColumn {
    item { SettingsSectionHeader(icon = …, title = "本地生图", subtitle = …) }
    item { Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { … } }
    // 下一个分区同理；间距与 ModelScreen 一致（12dp / 12dp）
}
```

**四条纪律**
1. 页面根只用一个 `LazyColumn`；**不要**在它里面再套 `Column(verticalScroll)` —— 嵌套滚动会互相抢手势；
2. 列表项给**稳定 key**（`items(models, key = { it.id })`）：否则下载进度刷新会让整行重建，动画抖；
3. 会持续增长的内容（下载日志、生成日志）用 `AutoFollowScrollLazy`（终端页已有先例），不要自己写「滚到底」逻辑；
4. 长列表（模型库，几十项）用 `LazyColumn` 而不是 `Column` —— 每个模型行都带缩略图与进度条，`Column` 会一次性组合全部。

#### ⑤ 下级页面路由：两处都要改，缺一页就崩

Praxis 的约定（`PraxisNavDisplay.kt` 的注释写明）：**`Routes.kt` 的声明与 `entry<...>` 必须一一对应**。

```kotlin
// ① Routes.kt —— 每个 Route 都要 @Serializable（NavKey 要求）
@Serializable data object SettingsImageGen : PraxisRoute
@Serializable data object SettingsImageGenModels : PraxisRoute
@Serializable data class SettingsImageGenModelDetail(val modelId: String) : PraxisRoute
@Serializable data object SettingsImageGenProvider : PraxisRoute      // 右上角齿轮

// ② PraxisNavDisplay.kt —— 逐个注册；漏一个就是「点了没反应」
entry<PraxisRoute.SettingsImageGen>(swipeDismiss = NavSwipeDirection.None) { … }
entry<PraxisRoute.SettingsImageGenModels>(swipeDismiss = swipe) { … }
entry<PraxisRoute.SettingsImageGenModelDetail> { route -> … route.modelId … }
entry<PraxisRoute.SettingsImageGenProvider>(swipeDismiss = swipe) { … }
```

| 页面 | swipeDismiss | 为什么 |
|---|---|---|
| 本地生图（主页） | `None` | 与 `SettingsModel` 一致：主设置页不允许侧滑返回，避免误退 |
| 模型库 / 模型详情 / 提供方 | `swipe` | 二级页，侧滑返回符合预期 |

**不要自己拦返回键**：返回由 `NavDisplay` 统一处理（`MainScreenBackHandler` 只管 Tab 级）。

#### ⑥ 动画：该有的必须有

| 场景 | 用什么 | 备注 |
|---|---|---|
| 分区标题折叠 | **`FoldableContent(visible = …)`** | Praxis 有 `WiringGuardTest` 专门检查这类接线，别绕过 |
| 页面跳转 / 侧滑返回 | 全局 `NavTransitions` | **不要**自定义转场，否则与其它页不一致 |
| 弹窗 / 浮层 | `MiuixAnimatedDialog` / `MiuixAnimatedPopup`，**常驻挂载 + `visible` 参数** | 禁止 `if (visible) { Dialog() }` —— 退场动画来不及播 |
| 列表增删（模型下载完成、删除） | `Modifier.animateItem()` | 与稳定 key 配套 |
| 状态徽标变色（未下载→下载中→已安装） | `animateColor` | 不要瞬间跳色 |
| 下载 / 解压进度 | `ProgressButton` / `MiuixBlockingLoadingDialog` | 也是「进度必须有反馈」的落点 |
| 图片出现 | `Crossfade` 或现有加载占位 | 生成完成的图不要硬切 |

#### ⑦ 对齐 Praxis 现有样式

| 维度 | 约定 |
|---|---|
| 卡片间距 | `padding(horizontal = 12.dp)` + `padding(bottom = 12.dp)`（与 `ModelScreen` 一致） |
| 分区标题 | 每个分区都有 `SettingsSectionHeader`（图标 + 标题 + 一句副标题），不省略 |
| 深色模式 | 只依赖主题色，不自造色板 |
| 字号 | 用 Miuix 排版，不写死 `sp` 绕过主题缩放 |
| 文案 | 中文，与设置页现有措辞风格一致（短句 + 说明第二行） |
### 7.1 设置 → **本地生图**（新增二级页，入口名就叫「本地生图」）

```
设置 → 本地生图                    PraxisRoute.SettingsImageGen      [⚙ 右上角]
├── 引擎状态卡片                    见 §7.4
├── 当前模型                        名字 + 后端徽标（NPU/CPU/OpenCL）+ [更换]
├── 模型库                          → 模型列表页（§7.5）：内置清单 + 已下载 + 导入
├── 画布                            宽 / 高（预设档 + 自定义）
├── 采样                            步数 / CFG / 调度器 / 负向提示词
├── 性能                            后端选择 + 预热 + 并发
├── 输出                            自动存相册 / 显示每步预览
└── 诊断                            上次启动耗时 / 引擎日志尾巴 / 模型目录路径
```

**右上角 ⚙** 进的是「模型提供方」设置（§7.2）—— 与 Local Dream 的模型页右上角齿轮一致。

控件沿用（不要自写）：`SettingsSectionHeader` + `Card` + `MiuixTextField` / `MiuixNumPickerWidget` /
`MiuixDropDownItem`（调度器、后端）/ 项目里的 `Switch` 封装（= `LiquidToggle`）/ `ProgressButton`（下载）/
`MiuixBadge`（NPU/CPU 徽标）/ `MiuixBlockingLoadingDialog`（解压）。

### 7.2 右上角「设置」→ 模型提供方

| 项 | 控件 |
|---|---|
| 下载源 baseUrl | `MiuixTextField` + [恢复默认] + [测试连通]（HEAD 一个已知文件，显示延迟） |
| 仅 Wi-Fi 下载 | 开关（默认开） |
| 并发下载数 | `MiuixNumPickerWidget`（默认 2） |
| 代理 | 跟随 Praxis 全局代理 / 自定义 |
| 模型目录 | 只读展示路径 + [打开目录] |

### 7.3 功能模型：新增**「生图」槽位**（关键改动）

Praxis 的 `FunctionSlot` 枚举（在 `FunctionModelSettings.kt` 里）现在是 6 项：
`CHAT` / `CONTEXT_SUMMARY` / `TITLE` / `MEMORY_UPDATE` / `TRANSLATION` / `GREP`。
**新增第 7 项**：

```kotlin
IMAGE(
    key = "image",
    title = "生图",
    description = "生成图片；可选本地已下载模型，或任意服务商的生图模型",
),
```

> 该枚举的注释写着「列出来却点不动，就是假入口」—— 这一项**有真实调用点**（`generate_image` 工具），
> 所以加它是合规的，不违反那条约定。

#### 候选来源：**本地 ∪ 远端**

「生图」槽位的候选项由两处合并，UI 上分成两个分组：

| 分组 | 内容 | 来源 |
|---|---|---|
| **本地模型** | 已下载 + 已导入的模型（`models/<id>` 里有标记文件的） | §8 的模型库 |
| **服务商模型** | 用户配置的 API 里**带生图能力**的模型 | 各 provider 的模型目录里 `supportsImageGeneration` 为真（Praxis 已有能力位概念；网关的 `/v1/models` 也直接给这个字段） |

#### 数据形状（不污染现有 `FunctionModelOverride`）

现有 `FunctionModelOverride(model, provider, baseUrl)` 是「远端模型」语义，塞不进本地模型 id。所以：

```kotlin
sealed interface ImageGenTarget {
    /** 本地已下载/导入的模型。 */
    data class Local(val modelId: String) : ImageGenTarget
    /** 服务商的生图端点（OpenAI 兼容 /v1/images/generations）。 */
    data class Remote(val provider: ApiProvider, val baseUrl: String, val model: String) : ImageGenTarget
}
```

存到独立的 DataStore 键 `imagegen_target`（JSON），**不写进 `FunctionModelOverride`** ——
避免给一个「模型 → 服务商」的结构硬塞第三种语义。功能模型页里 `IMAGE` 这一行只是**入口**，
点进去选完写回 `imagegen_target`。

#### 「本地下载好的模型自动放进去」

下载完成（写了标记文件）后：

```kotlin
when {
    imagegenTarget == null -> imagegenTarget = Local(newModel.id)        // 首个模型：自动选中
    imagegenTarget is Local && !File(modelsDir, id).exists() ->
        imagegenTarget = Local(newModel.id)                             // 原模型被删：接管
    else -> { /* 不动用户的选择 */ }
}
```

再加一条 UI 提示：本地模型列表刷新后，`IMAGE` 那一行**立即**显示新的模型名（不用重启 App）。

### 7.4 引擎来源卡片（本地生图页顶部）

对应 §1.3 的状态机。三态：

| 状态 | 卡片内容 |
|---|---|
| 已连接 | 来源徽标（外部应用 / 容器 / 内置）+ 模型数 + [测试生成] + [切换来源] |
| 未检测到 | 三行办法（安装 Local Dream / 容器内安装 / 载入内置），各自一行按钮 + 一句说明 |
| 连接中 / 出错 | 具体原因（端口不通 / 协议版本不符 / proot 启动失败）+ 重试 |

**注意**：槽位选的是**服务商模型**时，这张卡片应当显示「当前走远端，不需要本地引擎」，
不要因为本地引擎没就绪就报红 —— 那是两件事。

### 7.5 模型库页（从本地生图页进入）

| 区域 | 内容 |
|---|---|
| 顶部 | 已用空间 / 剩余空间 / 本机芯片档位（`Build.SOC_MODEL` → 档位）+ 「只显示本机可跑」 |
| 内置清单 | 名称、体积、量化档、后端徽标（NPU/CPU）、分辨率档 |
| 行操作 | 下载（带进度）/ 取消 / 删除 / 设为当前 / 校验（有哈希时） |
| 已导入 | 用户自建的模型，同等待遇 |

### 7.6 对话页

不加新按钮。生图由模型发起（用户说「画一只猫」）。
**但**留一个显式兜底：输入框工具菜单里加「生成图片」，点开是小表单（提示词/尺寸），
生成结果直接进当前对话 —— 模型抽风不调工具时用户也能用。
## 8. 模型目录与下载（照 Local Dream 的做法移植）

### 8.1 模型目录（内置清单）

Local Dream 的每个模型是一条 `Model`（`data/Model.kt`，1231 行）—— 移植时保留同样的字段：

```kotlin
data class ImageModel(
    val id: String,                  // 目录名，也是 models/<id>
    val name: String, val description: String,
    val fileUri: String,             // 相对 baseUrl 的路径，如 "xororz/sd-qnn/resolve/main/AnythingV5_qnn2.28_8gen3.zip"
    val generationSize: Int = 512,   // 默认画布边长
    val approximateSize: String = "1GB",
    val backendType: String,         // 直接喂给引擎 --type：sd15cpu / sd15npu / sdxl / anima
    val runOnCpu: Boolean = false,
    val isSdxl: Boolean = false, val isAnima: Boolean = false,
    val ditKind: String = "",        // 非空 = DiT 系（ZIMAGE/KLEIN/…）
    val packageFiles: List<String> = emptyList(),  // 非空 = 多文件包（见 8.3）
    val markerFile: String,          // 装完写进目录的标记（SDXL / ANIMA / finished …）
    val isCustom: Boolean = false,   // 用户自己导入的
)
```

**内置清单里现成的家**（Local Dream 的 `predefinedModels`）：SDXL 系（`cyber_realistic_v10` / `illustrious_v16` 及其 dmd2 变体）、SD1.5 NPU 系（`anythingv5` / `qteamix` / `cuteyukimix` / `absolutereality` / `chilloutmix`）、
SD1.5 CPU 系（同名的 `*cpu`）、DiT 系（`z_image_turbo` / `flux2_klein_4b|9b` / `flux3_image_klein` / `qwen_image_2_1(_turbo)` / `krea_2_turbo`）、超分（`upscaler_anime` / `upscaler_realistic`）。

### 8.2 按芯片档位选变体（**不能照抄固定文件名**）

文件名里有芯片档位：`..._qnn2.28_$suffix.zip`，`suffix` 来自 `Model.getChipsetSuffix(soc)`（读 `Build.SOC_MODEL`），拿不到就回落 `min`。

```kotlin
val suffix = ImageModelCatalog.chipsetSuffix(Build.SOC_MODEL) ?: "min"
val url = "$baseUrl/xororz/sd-qnn/resolve/main/AnythingV5_qnn2.28_$suffix.zip"
```

**照抄固定后缀 = 在别的机型上下载到跑不动的包**。移植时这一步必须保留，并在 UI 上显示「本机档位：8gen3」。

### 8.3 两种包型（**大模型必须走多文件**）

| 包型 | 判据 | 下载方式 |
|---|---|---|
| 单 zip | `packageFiles` 为空 | 下整包 → 解压 → `rename` 进 `models/<id>/` |
| **多文件** | `packageFiles` 非空 | 逐个文件下到 `models/<id>/<name>.part`，成功后 `rename` 去掉 `.part` |

Local Dream 的注释说明了为什么：DiT 包有**好几 GB**，解压需要同时占住压缩包和展开后的空间；
多文件方式让中断后**按文件粒度续传**（「8 GB 下了 6 GB」不会从零开始）。

**注意**：它的续传是**文件级**，不是字节级 —— 每个 `.part` 都从头写，重新开始时会先删掉残留的 `.part`。
Praxis 移植时可以先照抄这个语义（简单可靠），要字节级续传再自己加 `Range`。

### 8.4 下载流程（含前台服务与通知）

```
点「下载」 → 启前台服务（照 Praxis 的 ScreenCaptureService）
  ① HEAD 取远端大小 → 算总进度（多文件包按各文件之和）
  ② 下载到 <models>/<id>/ 下的 .part（或 temp 里的 zip）
  ③ 单 zip：ZipInputStream 解到临时目录 → rename 进模型目录；多文件：逐个 rename
  ④ 写标记文件（markerFile）→ 模型才算「已安装」
  ⑤ 通知栏进度；完成/失败都要有终态（别留一个永远转的圈）
```

**四个必须做的细节**
1. **空间预检**：剩余 < 包体 × 1.3 直接拒绝，并告诉用户差多少；
2. **失败清理**：`.part` / 临时解压目录都要清掉，否则下次预检会误判空间不足；
3. **安装判据分两种**（2026-11-09 更正：本文原先把「标记文件」写成唯一判据，那是把 DiT 的规则套到了所有包上）：
   - **多文件包（DiT）**：标记文件（`ZIMAGE` / `KLEIN` / `QWEN_IMAGE_2_1` / `KREA_2_TURBO`）必须存在，
     且 `packageFiles` 里每个本地文件都非空 —— 少一个文件时引擎会在加载阶段报一个很难懂的错误；
   - **单 zip 包（SD1.5 / SDXL）**：上游的判据就是「目录存在且非空」（解压成功后目录必有文件，
     解压失败时上游会把目录删掉）。所以这类没有标记文件也不会误判。
   实现见 `core:imagegen/ImageModelCatalog.kt` 的 `isInstalled()`，13 条单测覆盖两种判据；
4. **不边下边用**：没有标记文件就当作未安装，不给引擎起（半成品包会让引擎报错很难懂）。

### 8.5 模型提供方（右上角「设置」）

Local Dream 把下载源做成可配的 `baseUrl`（`GenerationPreferences.getBaseUrl()`），默认官方仓（`xororz/sd-qnn` 等）。
Praxis 移植成设置页右上角齿轮进入的「模型提供方」：

| 项 | 说明 |
|---|---|
| **下载源** | 默认官方源；可填镜像（国内直连慢，这是刚需）。字段就是 baseUrl，`fileUri` 相对它解析 |
| 连通性测试 | 一个「测试」按钮：HEAD 一个已知文件，显示延迟与是否可达 |
| 并发下载数 | 多文件包可并行下 N 个（默认 2，别贪心） |
| 仅 Wi-Fi 下载 | 默认开（几个 GB 的事） |
| 代理 | 复用 Praxis 现有代理设置（若已接网关的代理池，可直接用） |

> 这也解释了 §1.1 的形态 A/B/C 为什么都要有这一层：**引擎来源可换，模型源也要可换**。

### 8.6 换模型必须重启引擎

`--model_dir` 是启动参数，所以：

```
切模型 → 停引擎（宽限 2s）→ 起新引擎（新 model_dir / 新 --type）→ 等 /health → 标记就绪
```

这段过程（几秒到十几秒）里来的生成请求要排队或返回 `engine_starting`，**不要静默失败**。

### 8.7 用户自建 / 导入模型

Local Dream 允许把模型目录丢进 `models/` 手动导入，靠标记文件识别类型（`scanCustomModels()`）。
Praxis 版建议：设置页提供「导入」入口（选 zip 或选目录），导入后同样校验标记文件与必需文件清单；
**导入的模型也进功能模型的候选**（与内置模型一视同仁）。
## 9. 硬件门控（不做这段会得到一堆「装了不能用」）

| 维度 | 判据 | 来源 |
|---|---|
| ABI | **`arm64-v8a`** | `build.gradle.kts` 的 `abiFilters` |
| 系统 | `minSdk 28` | 同上 |
| NPU（SD1.5） | Snapdragon **Hexagon V68+** | README |
| NPU（SDXL） | **Snapdragon 8 Gen 3+** | README |
| DiT（FP8） | `Build.SOC_MODEL` 形如 `SM8750+`（`DitEngine.isSupportedDevice()` 的判据） | `data/DitEngine.kt` |
| 内存 | SD1.5 约需 2~3 GB 空闲；SDXL 更高 | 实践值，需在目标机上实测 |

**落地方式**：启动时算一次 `ImageGenAvailability`（ABI + SoC + 空闲内存），
不满足就把工具**不注册**，设置页顶部显示一行明确原因（「这台机器的 SoC 是 SM8550，DiT 需要 SM8750+」），
而不是让用户点了之后等 30 秒再失败。

---

## 10. 模块与依赖

```
新增 jniLibs/arm64-v8a/
  ├── lib<engine>.so            推理引擎（可执行，必须以 lib*.so 命名）
  ├── libdit_engine.so          DiT 引擎（若启用 DiT 系模型）
  └── libQnn*.so …              QNN 运行时与 FastRPC skels

新增 core:imagegen（纯 Kotlin，无 Android）
  ├── ImageGenParams.kt         参数与默认值
  ├── ImageGenRequest.kt        → /generate 的 JSON 序列化
  ├── ImageGenEvent.kt          progress / complete 两种事件
  └── EngineProtocol.kt         端点与字段名的唯一出处

新增 app/data/imagegen/
  ├─ ImageModel.kt             模型目录条目（§8.1，含 backendType / markerFile / packageFiles）
  ├─ ImageModelCatalog.kt      内置清单 + 芯片档位后缀 + 自定义模型扫描（§8.1/8.2/8.7）
  ├─ ModelDownloadService.kt   前台服务下载：单 zip / 多文件、.part、HEAD 取大小、解压、写标记（§8.3/8.4）
  ├─ ModelProviderSettings.kt  下载源 baseUrl / 并发 / 仅 Wi-Fi / 连通性测试（§8.5）
  ├─ ImageGenTarget.kt         生图槽位的选择：Local | Remote（§7.3）
  ├── EngineSource.kt           External / Container / Bundled 三态（§1.2）
  ├── EngineLocator.kt          按 External → Container → Bundled 探测，返回可用来源 + 不可用原因
  ├── EngineProcess.kt          ProcessBuilder 生命周期 + /health 就绪（仅 Bundled）
  ├── ContainerEngineLauncher.kt  经 proot 在 rootfs 里起引擎（仅 Container）
  ├── ImageGenClient.kt         OkHttp + NDJSON 逐行解析 → Flow<ImageGenEvent>（三种来源共用）
  ├── ImageGenRepository.kt     模型切换、历史落库、文件落盘
  └── ImageGenAvailability.kt   设备门控（ABI / SoC / 空闲内存）

新增 app/tool/
  ├─ GenerateImageTool.kt        本次生成（§5.1）
  ├─ SetImageGenConfigTool.kt    改持久配置，返回 diff（§5.6）
  └─ ListImageGenModelsTool.kt   只读：本地∪远端的可用模型（§5.6）

UI
  ├── feature/settings/imagegen/      设置二级页 + 模型管理页
  └── feature/chat/                    生成进度卡片 + 结果气泡（复用 MessageBubble）
```

**依赖**：**不新增**。HTTP 用现有 OkHttp 4.12，流式读取用现有 `BufferedSource`，
JSON 用现有 kotlinx.serialization，前台服务照 `ScreenCaptureService` 的写法。

---

## 11. 分阶段（P0 ~ P5）

| 阶段 | 范围 | 产出 | 验收 |
|---|---|---|---|
| **P0a** | **形态 A：连外部引擎**（推荐先做） | `EngineLocator` 探测 `:8081/health` + `:8808/info` 版本校验；`ImageGenClient` 读 NDJSON | 装 Local Dream 并开 host 模式后，Praxis 能列出模型并出图 |
| **P0b** | **形态 C：内置引擎**（自用构建） | 引擎 + QNN 库进 `jniLibs`；`EngineProcess` 起停 | 真机 `POST 127.0.0.1:8081/generate` 返回一张能打开的图 |
| **P0c**（实验） | **形态 B：容器内引擎** | 下载 zip → 解进 rootfs → `proot` 启动 → 等端口就绪 | 容器内 CPU 后端能出图；**NPU 与 ptrace 开销另测** |
| **P1** | **工具接上（含调参）** | `GenerateImageTool`（全参数）+ `SetImageGenConfigTool`（带 diff）+ `ListImageGenModelsTool` + 注册条件 + 失败分类 | 对话里说「画一只猫」，模型正确调用工具，日志里能看到生成的图落盘 |
| **P2** | **结果进对话** | 进度卡片 + 完成后 `ChatImage` 缩略图 + 长按保存 | 生成中能看到进度，完成后图显示在气泡里，可保存到相册 |
| **P3** | **「本地生图」设置页 + 模型库** | 设置入口「本地生图」+ 右上角模型提供方 + 内置清单下载（单 zip/多文件、`.part` 续传、芯片档位后缀）+ 解压写标记 + 空间预检 | 真机能下载一个内置模型并出图；下载中断后重进能续；换源能生效 |
| **P3.5** | **生图槽位接入功能模型** | `FunctionSlot.IMAGE` + `ImageGenTarget`（Local/Remote）+ 下载完自动选入 + 服务商生图模型进候选 | 功能模型页出现「生图」；本地模型下完自动出现在该行；切到服务商模型后能走远端出图 |
| **P4** | **设备门控与降级** | `ImageGenAvailability` + 不可用原因展示 + OOM 自动降尺寸重试一次 | SM8550 上明确显示「不支持」，而不是失败 |
| **P5**（可选） | 图生图 / 局部重绘 / 超分 / 历史页 | 复用同一引擎端点（`image` / `mask` / `ultrafix`） | 各跑通一次 |

**每阶段都可单独交付**：P0 完成就已经能手工出图；P1 之后 AI 可用；P2 之后体验完整。

---

## 12. 风险与坑（按踩中概率）

| 风险 | 后果 | 对策 |
|---|---|---|
| **CC BY-NC 4.0 许可** | 随 App 分发即违反禁商用条款；与 AGPL 也不兼容 | 只做自用构建；发布版要换实现或让用户自装 |
| **模型权重许可** | SD1.5 = OpenRAIL-M、SDXL = OpenRAIL++、FLUX 各有条款 | 随包分发权重前逐个确认；最稳的是**不预置权重，让用户下** |
| 引擎从可写目录起不来 | `Permission denied`（API 29+ 的 W^X） | 必须放 `nativeLibraryDir`（`lib*.so` + `extractNativeLibs=true`） |
| 把 NDJSON 当 SSE 解析 | 永远拿不到结果 | 按行 `readLine()` + `JSONObject`，不看 `data:` 前缀 |
| 一次性读完响应体 | 没有进度、内存爆 | 流式逐行读，读完一行立刻分发 |
| 每步预览全开 | 内存与带宽翻倍（2048² 原始 RGB 约 12 MB/帧） | 默认关；开了也强制 `preview_format=jpeg` |
| 换模型不重启引擎 | 生成的还是上一个模型的图（不报错） | 引擎与 `model_dir` 绑定；切换必须重启并重新 `/health` |
| 8081 上是**别人的**引擎 | 状态机错乱 | 启动前探测端口占用，用完即停；Local Dream 也是这么处理的 |
| 后台被杀 | 生成中断、白等 | 前台服务（照 `ScreenCaptureService`）+ 进度通知 + 允许取消 |
| 发热 / 降频 | 第二张比第一张慢一倍 | 连续生成之间给冷却；UI 上说明「长时间生成会发热」 |
| OOM | 整机卡死或进程被杀 | 预检空闲内存；OOM 时自动降一档尺寸重试**一次**，再失败就报错 |
| 模型目录放 `Download/` | 需要 `MANAGE_EXTERNAL_STORAGE`，上架审核高危 | 默认私有目录；下载存盘不做成默认 |
| `embeddings/` 位置放错 | 文本反演静默失效 | 保持与 `models/` 同级（引擎按上两级找） |
| 首字延迟误判为卡死 | 用户杀掉 App | 引擎启动阶段就有明确的「正在准备模型」状态 |

---

## 13. 验收清单

**P0 引擎来源**

- [ ] `EngineLocator` 能正确报告 A / B / C 哪种可用，都没有时给出三种办法
- [ ] 形态 A：外部 App 停掉后 UI 从「已连接」变为「引擎离线」，不静默失败
- [ ] 形态 C：`arm64-v8a` 真机上引擎从 `nativeLibraryDir` 启动，`/health` 返回 200
- [ ] `POST /generate`（512×512 / 20 步）拿到可打开的图，`complete` 的 `seed` 与实际一致
- [ ] 引擎异常退出时 App 不崩，状态变 `Error` 并可重启

**P1 工具（含 AI 调参）**

- [ ] 未安装模型时工具**不注册**；装好后注册
- [ ] 说「画一只猫」→ 正确调用，参数透传（日志可见 JSON）
- [ ] 说「换成竖图 512x768」→ 只影响本次，**不改默认值**
- [ ] 说「以后步数都用 30」→ 调 `set_image_gen_config`，返回的 diff 与用户预期一致
- [ ] 说「换个模型」→ 先 `list_image_gen_models` 再设，**一次成功不试探**
- [ ] 越界请求（改 API Key / 下载源 / 删模型）返回 `not_allowed` 而不是照做
- [ ] 请求未 patch 的尺寸 → 返回 `invalid_size` 并列出可用档位，不静默出 512
- [ ] `no_model` / `oom` / `unsupported_device` 三种失败各自返回可读原因
- [ ] 工具执行期间不阻塞 UI（主线程无长任务）

**P2 对话**

- [ ] 生成中显示进度；预览关闭时不推帧也不卡
- [ ] 完成后图出现在 assistant 气泡，长按可保存到相册
- [ ] 对话重建（杀进程重进）后图片仍能显示（缩略图在消息里）

**P3 设置页与模型库**

- [ ] 设置里出现「本地生图」入口；右上角能进「模型提供方」并测试连通性
- [ ] 下载内置模型：进度可见、可取消；中断后重进按文件粒度续（`<name>.part`）
- [ ] 芯片档位后缀取对本机（UI 显示档位）；换机型不会下到跑不动的包
- [ ] 下载完写标记文件才算已安装；半成品不给引擎起
- [ ] 空间不足时拒绝并给出差额；失败时清理 `.part` 与临时解压目录
- [ ] 切换模型后引擎自动重启，下一次生成用的是新模型

**P3.5 生图槽位**

- [ ] 功能模型页出现「生图」一行，候选分「本地模型 / 服务商模型」两组
- [ ] 下载完成后该行自动显示新模型（无需重启 App）
- [ ] 原选中模型被删除时自动接管；用户手动选过则不覆盖
- [ ] 选服务商模型后工具走 `/v1/images/generations`，结果同样落成聊天里的图
- [ ] 本地引擎未就绪但选了服务商模型时，**不报「引擎不可用」**

**P4 门控与降级**

- [ ] 非 arm64 / 低 SoC 设备上明确提示原因，而不是失败
- [ ] OOM 自动降尺寸重试一次，再失败给出可操作建议

**UI 一致性（对 §7.0 逐条，P0 起每阶段都要过）**

- [ ] 所有开关走项目 `Switch` 封装（= `LiquidToggle`），**没有**直接调 miuix `Switch`
- [ ] 按钮用 miuix `Button` / `TextButton`；进度用 `ProgressButton`（未引入液态玻璃按钮）
- [ ] 页面根是单个 `LazyColumn`，无嵌套滚动；列表项有稳定 key（下载进度刷新不抖）
- [ ] 每个新页面在 `Routes.kt` 有 `@Serializable` 声明，且在 `PraxisNavDisplay.kt` 有对应 `entry<...>`
- [ ] `swipeDismiss` 按 §7.0 的表设置；没有自己拦返回键
- [ ] 折叠用 `FoldableContent(visible=)`；弹窗常驻挂载 + `visible`；列表增删有 `animateItem()`
- [ ] 换壁纸后整套页面跟着变色（莫奈），无写死 `Color(0x…)`
- [ ] 与 `ModelScreen` 的分区间距一致（`horizontal = 12.dp` / `bottom = 12.dp`），每区都有 `SettingsSectionHeader`

---
## 14. 附录

### 14.1 Local Dream 源码位置（对照实现）

| 想看什么 | 文件 |
|---|---|
| 引擎启动参数与状态机 | `app/src/main/java/io/github/xororz/localdream/service/BackendService.kt` |
| `/generate` 请求构造 + NDJSON 解析 | `service/BackgroundGenerationService.kt`（约 264–470 行） |
| 端口/端点常量 | `remote/RemoteProtocol.kt` |
| DiT 引擎与设备门控 | `data/DitEngine.kt` |
| 模型目录与默认参数 | `data/Model.kt`（51 KB，最全） |
| 存储布局与移动 | `data/ModelStorage.kt` |
| 历史落库 | `data/HistoryManager.kt` |
| 参数分享格式 | `data/ParamShare.kt` |
| 原生构建 | `app/build.gradle.kts`（`abiFilters`）+ `app/src/main/cpp/CMakeLists.txt` |

### 14.2 Praxis 侧改动点

| 位置 | 改什么 |
|---|---|
| `core/tool/ToolContract.kt` | 不改（`generate_image` 就是一个普通 `Tool`） |
| `core/ai/AiContract.kt` | 不改（`ChatMessage.images` 已够用） |
| `feature/chat/widget/MessageBubble.kt` | 加「生成结果」这一种消息形态的进度态 |
| `feature/settings/SettingsEntry.kt` | 加「生图」入口 |
| `app/di/AppModule.kt` | 注册 `ImageGenRepository` 与工具 |
| `app/src/main/jniLibs/arm64-v8a/` | 放引擎与 QNN 库 |
| `app/src/main/AndroidManifest.xml` | 生成服务声明（照 `ScreenCaptureService`） |

### 14.3 一句话总览

1. **引擎不移植，当独立进程跑**（Praxis 已有 `nativeLibraryDir` 通道），App 只用 HTTP + NDJSON 跟它说话；
2. **AI 调它就是一个普通工具**（`generate_image`），失败要分类返回，模型才知道该调参数而不是瞎重试；
3. **图以缩略图进消息、原图落磁盘**，渲染复用现有气泡，不新增控件；
4. **配置落在「设置 → 本地生图」**：模型列表 + 下载 + 右上角「模型提供方」（换源）；
   功能模型新增 **「生图」槽位**，候选 = 本地已下载 ∪ 各服务商带生图能力的模型，
   本地模型下完**自动选入**；工具按槽位分发给本机引擎或远端 `/v1/images/*`；
5. **UI 一律 Miuix + 项目封装**：开关用 `LiquidToggle`（项目 Switch 封装）、按钮用 miuix、
   颜色只走 `MiuixTheme`（莫奈自动）；滚动单一 `LazyColumn`、路由两处都要注册、动画按 §7.0 清单；
6. **许可是红线**：CC BY-NC 只能自用，发布版要么换实现、要么让用户自装、要么走网关的 `/v1/images/*`。
