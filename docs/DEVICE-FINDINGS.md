# 真机实测结论（Redmi 25053RT47C / Android 17 / arm64 / 有 root）

## 环境

```
型号      Redmi 25053RT47C
Android   17 (SDK 37)
ABI       arm64-v8a
内核      6.6.118-android15-8 (preempt, 4k pages)
CPU       ARMv8.2-A (0xd81)，features 含 bti / i8mm / bf16 / sha512 / sve 无
已装 APK  dev.praxis.debug 1.0.0，lastUpdateTime 2026-11-02 21:46
```

## M5（终端）—— 实证结论

### proot 本身是好的 ✅

```
PID 18075: /data/app/.../lib/arm64/libproot.so --link2symlink --kill-on-exit -0 -v 0
  -b /dev -b /proc -b /sys ... -w /root
  -r /data/user/0/dev.praxis.debug/files/linux/shared /bin/sh -i
```

proot 从 `nativeLibraryDir` 正常执行、容器脚本起来了。**上一轮修的三层根因是对的。**

### 终端仍报 `can't access tty` —— 真因是 libpty.so 不可用

证据链：

1. 真机终端显示 `/bin/sh: 0: can't access tty; job control turned off`
   （注意 `0:` 前缀 = dash 格式，是**容器内**的 sh 说的）
2. `ps` 确认 proot 进程在跑、容器活着
3. `logcat -s ProotTermSession` **零条** → 没有判定会话死亡
4. 说明走的是**管道降级分支**（无 PTY）
5. `libpty.so` **确实在** APK 里（`lib/arm64/libpty.so`，8904 字节）
6. **决定性 A/B 对照**：

```
/system/bin/linker64 libproot.so  ->  正常进入 main（打出 usage）
/system/bin/linker64 libpty.so    ->  Illegal instruction
```

同样的测试方法、同一台设备：`libproot.so` 能跑，`libpty.so` 报 SIGILL。

### 已排除的原因

| 怀疑点 | 排除依据 |
|---|---|
| 指令集不兼容 | 解码 `.text`：libpty 689 条指令只有 BTI(6)+HINT(9)，libproot 37439 条也有 BTI(4)，**无 SVE/SME/i8mm** |
| BTI 本身有问题 | libproot.so 含 4 条 BTI 却正常运行 |
| CPU 不支持 | features 含 bti、i8mm、bf16、sha512 |
| 16KB 对齐 | 两个库 p_align 都是 0x4000 |
| ABI 不匹配 | 两个库 machine 都是 183 (AArch64) |
| 依赖缺失 | libpty 只依赖 liblog/libm/libdl/libc（系统库） |
| 符号表为空 | 是设备没有 `nm` 命令，不是真的没符号 |

### 尚未查明

`Illegal instruction` 发生在**动态链接/重定位阶段**（还没进 main）。
设备和 NDK 都没有 objdump，无法精确定位是哪条指令。

**最可能的原因**：NDK 版本与编译参数组合产出的产物，在这个 Android 17 + kernel 6.6
环境下不兼容。CI 里**没有显式指定 `ndkVersion`**（用 AGP 默认），这是已知的不确定性来源。

### 拟定修法（待验证）

1. **显式固定 `ndkVersion`** —— 消除 CI 上 NDK 版本漂移；
2. **关闭 BTI/PAC 分支保护**：`-fno-branch-protection` + `-Wl,-z,force-bti=0`（若需要），
   让产物回到最保守的基线；
3. **显式 CPU 基线**：`-march=armv8-a -mcpu=generic`；
4. **重编后立即在真机上用 `linker64 libpty.so` 验证**（这次有真机，能直接确认）；
5. 若仍失败，改用 **JNI 里自己调 `forkpty` 的 write/read 包装**（不依赖 .so 加载）
   或退到 `posix_openpt` 纯 Java/Kotlin 实现。

### 有一个漏洞要补

App 里 `Pty` 的 `init { System.loadLibrary("pty") }` 抛 `UnsatisfiedLinkError` 时
只 `Log.e` 了一条 —— 但实测 **logcat 里连这条都没有**。

说明 `Pty` 类**从未被初始化**，连 `Pty.start` 都没调到。
这与代码里 `ProotTerminalSessionPort` 的 PTY 分支直接写
`dev.praxis.core.terminal.Pty.start(...)` 对不上，需要再查 —— 可能异常被上游 `runCatching` 吞了。

**这条必须查清**：否则就算 libpty 修好，PTY 分支可能仍然不生效。## 更重要的重新定位（推翻前一节的部分结论）

### 我前面两个判断都是错的，必须纠正

**错误一**：「`libpty.so` 是坏的（Illegal instruction）」——**错**。

我用 `/system/bin/linker64 libpty.so` 去「测试装载」，那实际上是在**执行**这个 .so。
JNI 库没有 `main` 入口，PC 落到偏移 0（ELF 头）当然 SIGILL：

```
signal 4 (SIGILL), code 1 (ILL_ILLOPC), fault addr 0x...7000
#00 pc 0000000000000000  libpty.so
#01 pc .../linker64 (__dl__start+8)
```

`pc=0x0` 就是铁证 —— 崩在 ELF 头，不是在合法代码里。
那两条 tombstone 是我自己制造的，与 App 无关。

**正确证据**：App 进程的 maps 里有完整的两段 libpty 映射：

```
74cccf0000-74cccf2000 r-xp  .../lib/arm64/libpty.so    ← 代码段已映射
74cccf5000-74cccf6000 r--p  .../lib/arm64/libpty.so
```

`System.loadLibrary("pty")` **成功了**，`Pty.available == true`。

### 真正的根因：PTY 分支被静默丢弃，降级到了管道

真机上 Praxis 的 proot 进程现状：

```
PID 28734  PPID 25657  libproot.so ... -r .../linux/shared /bin/sh -i

/proc/28734/stat  tty_nr = 0            ← 没有控制终端
/proc/28734/fd/0 -> pipe:[14618215]     ← stdin 是管道，不是 /dev/pts/N
/proc/28734/fd/1 -> pipe:[14618216]
```

**proot 确实在跑、容器是活的**，但它拿的是管道而不是 PTY。
所以 `/bin/sh -i` 打印 `0: can't access tty`（`0:` 是 dash 的格式，即容器内的 sh）。

### 为什么 logcat 一条日志都没有

`Pty.start()` 里所有失败路径都是**静默 return null**：

```kotlin
val result = runCatching { createSubprocess(...) }.getOrNull() ?: return null
if (result.size < 2) return null
if (fd < 0) return null
```

而 `ProotTerminalSessionPort` 里：

```kotlin
java.lang.Thread.sleep(200)
if (started.process.isAlive) { pty = started; break }
runCatching { started.destroy() }   // ← 死了就丢弃，不打日志
```

**所以「Pty 起子进程后 200ms 内死掉」这条路完全无声。**

### 最可能的具体失败点

`pty.c` 的子进程分支：

```c
if (pid == 0) {
    if (chdir(cwd) != 0) { fprintf(stderr, ...); _exit(1); }
    execve(argv[0], argv, envp);
    fprintf(stderr, "execve(%s) failed: %s\n", argv[0], strerror(errno));
    _exit(1);
}
```

`execve` 失败只会写 stderr（已重定向进 PTY），**不进 logcat**。
于是：forkpty 成功 → 子进程 execve 失败 → _exit(1) → 200ms 后 isAlive=false
→ 丢弃 → 降级管道 → 用户看到 `can't access tty`。整条链**没有任何日志**。

### 修法（必须做）

1. **给 `pty.c` 的子进程失败路径加 `LOGE`** —— `chdir` 与 `execve` 失败都要打，
   并带上 `argv[0]`/`cwd`/`errno`。这是唯一能看到真因的窗口。
2. **给 `Pty.start()` 的每个 `return null` 加日志**（哪个分支、为什么）；
3. **给 `ProotTerminalSessionPort` 的「200ms 判死丢弃」加日志**（含退出码）；
4. 重编后上真机，一次就能定位。

**这三条加日志本身就是必须的修复** —— 一个能让整个终端静默降级的代码路径，
不该一行日志都没有。这是可观测性缺陷，不是调试手段。

## M4 真机实证（2026-11-03）—— 修正假设

### 数据没有丢，是界面没加载

真机数据库直读，重启 App 前后对比：

```
              重启前              重启后
sessions:     main / s1793628034799   （不变）
  main         2 条消息            2 条消息
  s1793628034799  47 条消息         47 条消息
```

**一条都没少。** 所以用户说的「部分内容消失」不是数据丢失，
而是**重进后界面没能把已有内容渲染出来**。

### 与「命中率清零」是同一个根因

`ContextUsage.used` 只在 `ChatViewModel.publishUsage()` 里写，三个调用点全在发送路径：

```
ChatViewModel.kt:1677  publishUsage(buildPreparedMessages(config), config)
ChatViewModel.kt:1717  publishUsage(buildPreparedMessages(config), config)
ChatViewModel.kt:2633  publishUsage(buildPreparedMessages(config), config)
```

`restoreIfNeeded()`（重进入口）不调用它 → 重进后 `used` 停在 0 → 进度条显示 `0 / 1.0M`。

两者指向同一个缺陷形态：**恢复路径只恢复了一半状态**。

### 修法

`restoreIfNeeded()` 恢复完历史后，把「由历史派生的状态」全部重算一遍：

1. `publishUsage(恢复出的历史, config)` → 恢复 `contextUsage.used`；
2. 检查恢复后是否真的把消息灌进了 `state.bubbles`（对比数据库条数）；
3. 若消息没渲染，查恢复流程里对 `compacted.coveredCount` / `history` 的处理 ——
   压缩摘要覆盖的部分是否被正确重建。

### 验证方式（真机可做）

```
基线：拉库 -> 记 sessions 与每会话消息数、记界面进度条读数
重启 App -> 再拉库对比（数据层）+ 截图看进度条与消息气泡（UI 层）
```

数据层已确认不会丢，所以**验收重点是 UI 层**。
## M1 真因已定位并修复（2026-11-03，真机实证）

### 决定性日志

装上带日志的 APK（run #101）后，一次就抓到：

```
E PraxisPty: java.lang.UnsatisfiedLinkError: No implementation found for
  int[] dev.praxis.core.terminal.Pty.createSubprocess(java.lang.String[], ...)
  (tried Java_dev_praxis_core_terminal_Pty_createSubprocess and
         Java_dev_praxis_core_terminal_Pty_createSubprocess___3Ljava_lang_String_2...)
E PraxisPty:   at dev.praxis.core.terminal.Pty$Companion.start(Pty.kt:211)
E PraxisPty:   at ProotTerminalSessionPort$create$2.invokeSuspend(...:181)
```

### 因果链（完整）

```
Kotlin 侧 5 个方法写成 companion 里的 @JvmStatic external fun
  -> @JvmStatic 在外层类 Pty 上生成静态桥接方法
  -> JVM 解析 native 实现时优先按外层类名查找：Java_..._Pty_<name>
  -> 但 pty.c 里 4 个方法写的是 Java_..._Pty_00024Companion_<name>（伴生形式）
  -> UnsatisfiedLinkError
  -> Pty.start() 每次返回 null
  -> ProotTerminalSessionPort 200ms 判死，静默丢弃
  -> 降级到 ProcessBuilder 管道
  -> /bin/sh -i 无 tty
  -> 用户看到 `0: can't access tty; job control turned off`
```

### 为什么以前没人发现

旁证很硬：同一份 `pty.c` 里 `setPtyWindowSize` 当初写的是
`Java_..._Pty_setPtyWindowSize`（**无** Companion），是唯一可能对的；
另外四个都带了 `_00024Companion`。两套命名混在一个库里，
说明这个前缀写法从未在真机上验证过。

还有一处：C 侧伴生版本叫 `waitFor`，Kotlin 方法叫 `waitForPid`，差一个后缀。

### 修法：两套命名都提供实现

不赌 `@JvmStatic` 的生成规则，而是两边都补：

```
伴生形式  Java_..._Pty_00024Companion_<name>   原有，保留
外层形式  Java_..._Pty_<name>                   新增
```

总计 11 个导出符号，5 个方法 × 2 种命名全覆盖。

### 这次能破案，靠的是上一轮补的日志

上一轮把三处静默点（pty.c 子进程、Pty.start 四个 return null、判死丢弃）
全部加上日志。这一轮装上后**一条日志就定位了**。

反过来印证：这个 bug 之所以能活这么久，正是因为整条链零日志。
可观测性不是辅助手段，它本身就是缺陷的一部分。

### 待验证

修好的 APK（commit 57a14e5）构建中，装上后应看到：

- `logcat -s PraxisPty` 出现 `forkpty ok: pid=... fd=...`
- proot 进程 `tty_nr != 0`、`fd/0 -> /dev/pts/N`
- 终端出现真实提示符、Ctrl+C 生效
## M1 修复已验证（2026-11-03 真机实测，收尾）

### 修复前 vs 修复后（同一台设备、同一个位置）

| | 终端页显示 | proot 进程属性 |
|---|---|---|
| 修复前 | `/bin/sh: 0: can't access tty; job control turned off` | `tty_nr=0`，`fd/0 -> pipe:[]` |
| **修复后** | `# pwd > /tmp/.praxis_cwd 2>/dev/null` → `PRAXIS_CR` | **`tty_nr=34818`，`fd/0 -> /dev/pts/2`** |

### 三条独立证据

**一、日志（commit 57a14e5 的 APK）**

```
D PraxisPty: forkpty ok: pid=402 fd=180 size=60x80
I ProotTermSession: PTY 会话建立成功 pid=402 argv[0]=.../lib/arm64/libproot.so
```

而修复前是同一条链上的：

```
E PraxisPty: java.lang.UnsatisfiedLinkError: No implementation found for
  int[] dev.praxis.core.terminal.Pty.createSubprocess(...)
```

**二、进程属性（最硬的一条）**

```
PID 402  tty_nr=34818                    <- 不再是 0
/proc/402/fd/0 -> /dev/pts/2            <- 真 PTY，不再是 pipe
```

**三、界面**

终端页不再是 `can't access tty`，而是真实提示符 `#` + Praxis 自己的 cwd 探测
命令执行结果（`PRAXIS_CR` 标记）。**容器内的 shell 真的在跑。**

### 修复内容回顾（三处，缺一不可）

1. **JNI 符号名**（关键）：`@JvmStatic external fun` 会在外层类生成静态桥接，
   JVM 优先按 `Java_..._Pty_<name>` 查找，而 C 侧写的是
   `Java_..._Pty_00024Companion_<name>` → `UnsatisfiedLinkError`。
   修法：两套命名都导出（11 个符号覆盖 5 方法 × 2 命名）。
2. **可观测性**：整条失败链（pty.c 子进程 / Pty.start 四个 return null /
   判死丢弃）原本零日志。补上后，**一次就定位了**。
3. （上一轮）proot 三层根因：loader 执行位 / 动态依赖 / loader 位置。

### 一条可复用的教训

这个 bug 活了很久，不是因为它复杂 —— 它只有一行名字不匹配。
它活下来是因为**整条链沉默**：库加载成功但方法绑不上，失败被四处
`return null` 吃掉，最后降级到管道只表现为一句看似无关的 `can't access tty`。

先把日志补上，再谈修 —— 这次两小时定位的 bug，前面几轮摸索了几十小时。
