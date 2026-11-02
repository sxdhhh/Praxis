# 终端与 proot 的三个问题（2026-11-03，用户真机反馈）

用户原话：

> proot 应该持久化，ai 给我说它每次脚本都会起一个 proot 会话才行，
> 还有终端连接的应该是内置的 liunx 系统而不是 /bin/sh
> 更高效的是 ai 给我说 `stty: 'standard input': Inappropriate ioctl for device`

三个问题对应三个不同的根因，逐个说清楚。

## 问题 1 & 3：终端连的是 /bin/sh + stty 报错

### 根因（一行）

`ProotTerminalSessionPort.create()` 第 156 行：

```kotlin
val args = ProotCommand.sessionArgs(env, listOf("/bin/sh", "-i"))
```

**错误 A：硬编码 `/bin/sh`，绕过了容器内的登录 shell 探测。**

`loginArgs` 尾部本来会调 `loginShell(env)` 选 rootfs 里实际存在的 bash（优先）
或 sh。而 `sessionArgs` 的职责正是把尾部两个元素**替换**成调用方给的 program。

于是：容器里明明装了 bash，用户拿到的却被钉死在 `/bin/sh` 上 ——
没有登录 shell 的 PATH、别名、提示符，`ls`/`apt` 甚至可能因 PATH 不全而找不到。

**错误 B：`-i` 在非 tty 路径上刷 stty 报错。**

`sh -i` 强制交互模式，交互 shell 会 `tcgetattr(stdin)` 读终端属性。
一旦落到管道降级分支（PTY 起不来时的兜底），stdin 不是 tty，
于是输出里夹一行：

```
stty: 'standard input': Inappropriate ioctl for device
```

这正是用户看到的那句。

### 修法

新增 `ProotCommand.loginTail(env)` = `listOf(loginShell(env), "-l")`，
`create()` 改用 `sessionArgs(env, ProotCommand.loginTail(env))`。

  - `-l` 是登录 shell 语义，它自己判断要不要交互，不需要 `-i` 强指，
    因此不会在非 tty 上刷 stty 错误；
  - shell 从 rootfs 探测，容器里有什么用什么；
  - 探测规则只有 `loginShell` 一处，不会在调用处写第二份。

## 问题 2：proot 不持久化

### 排查过程与结论

先确认「AI 跑命令」实际走哪条路，结果发现有**两套并存的执行路径**：

| 路径 | 实现 | proot 生命周期 | 状态保留 |
|---|---|---|---|
| `linux_exec` | `LinuxToolPortImpl` → `ProotEnvPort.exec()` | **每条命令新起一个 proot** | ❌ cd/export 全丢 |
| 终端会话 7 工具 | `ProotTerminalSessionPort.execute()` | **常驻 PTY，复用 `live[sessionId]`** | ✅ 可保留 |

关键证据：

- `ProotEnvPort.exec()`（ProotEnvPort.kt:1320-1371）每次都 `ProcessBuilder(argv).start()`，
  读完输出就结束进程 —— 没有任何会话复用。
- `ProotTerminalSessionPort.execute()`（:404）写的是 `live[command.sessionId]` 里
  **已经存在**的 PTY 进程，命令跑完进程不退出。
- DI（AppModule.kt:297-301）把 `linux_exec` 绑在 `LinuxToolPortImpl`（一次性路径），
  把 7 个会话工具绑在 `ProotTerminalSessionPort`（常驻路径）。

**所以问题不在实现，而在「模型选了哪个工具」。**

`linux_exec` 的名字与描述更直白（「执行 Linux 命令」），模型优先选它，
于是每次工具调用都新起一个 proot，`cd`/`export` 全部丢失 ——
这就是用户观察到的「每次脚本都会起一个 proot 会话」。

### 尚未修的（需要决策）

有以下几种做法，各有代价，**我还没有动手**：

1. **让 `linux_exec` 内部改用常驻会话**
   —— 行为最一致，但 `linux_exec` 的语义是「无状态一次性执行」，
   改了会让它在并发/超时场景下与过去不同。
2. **把 `linux_exec` 的优先级调低 / 从工具列表移除**
   —— 治本（模型没得选就不会用错），但破坏兼容性。
3. **在 `linux_exec` 的 description 里明确写「不保留状态，需要 cd/export 请用
   execute_in_terminal_session」** —— 改动最小、风险最低，
   但依赖模型读描述。

我倾向 2 + 3 组合，但这动到了模型可见的工具清单，属于产品决策，
需要确认后再做。
