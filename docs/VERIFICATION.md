# 八项修复的验证状态

> 这份文档回答一个问题：**每一项修复，到底被验证到了什么程度？**
>
> 区分三种证据，强弱不同：
>
> 1. **运行时复现 + 修复后验证** —— 造出失败场景、看到报错、改完再跑一遍通过。最强。
> 2. **模拟器实测** —— 在真 Android 上跑通功能，但没有刻意复现故障。
> 3. **纯单测** —— 只证明代码逻辑符合预期，不证明真机上能用。
>
> 写这份文档的原因：过去几轮我多次把「单测全绿」误当成「问题已解决」，
> 也多次以为「x86 模拟器验不了」而放弃 —— 后来发现 ③④ 的部分环节根本不需要 ARM。
> 把边界写清楚，避免下一次再犯同样的判断错误。

## 总表

| # | 问题 | 证据级别 | 具体验证 |
|---|---|---|---|
| ① | 终端无法长按复制/全选 | 模拟器实测（含回归） | 长按出 `已选中文本/复制/全选/完成`；② 修完后重测仍正常 |
| ② | proot 启动即退 | **模拟器端到端进容器** | 见下：进程级 + loader execve 证据 |
| ③ | Kali rootfs 解压失败 | **运行时复现 + 修复验证** | 见下 |
| ④ | apt 源重复配置警告 | **运行时复现 + 修复验证** | 见下 |
| ⑤ | 会话读取中断 | **运行时验证（正常关闭分支）** | 见下：活会话关闭后零伪故障日志 |
| ⑥ | 聊天页进度条/回底按钮 | 模拟器截图 + 单测 | 截图确认轨道消失、按钮上移；9 条显示逻辑单测 |
| ⑦ | 当前对话删不掉 | 模拟器 + 数据库 + 护栏 | 删除后直读 sessions 表确认行真的消失 |
| ⑧ | 无 AI 生成标题 | mock 服务 + 数据库 + 单测 | 见下 |

**八项全部在 API 30 模拟器上验证过。** 未覆盖的是「arm64 真机 + 真实 Kali/Ubuntu
rootfs + PTY 分支」这一组合，详见文末「明确未验证的部分」。

## ③ 的运行时验证（2026-11-02，API 30 / toybox 0.8.3）

✅ **不需要 ARM** —— 解压走 `/system/bin/toybox tar`，x86 模拟器能真跑。

**复现 1：设备节点**

```
tar: can't create './dev/zero': Operation not permitted
tar: can't create './dev/null': Operation not permitted
EXIT=1
```

以 app 身份（`run-as`，真实 SELinux 上下文）解压含 `dev/zero` 的 tar 即复现。
加 `--exclude=./dev/* --exclude=*/dev/*` 后 → **EXIT=0**，`dev` 被排除、其余正常解出。

**复现 2：硬链接（并因此发现一个真 bug）**

```
tar: can't link './usr/bin/gunzip' -> './usr/bin/uncompress': Permission denied
EXIT=1
```

代码原本靠 `tar -tv` 里的 ` link to ` 识别硬链接。但 toybox 0.8.3 的逐字输出是：

```
-rw-rw-rw- root/root  0  ... ./hardlink -> ./realfile     <- 硬链接
lrwxrwxrwx root/root  0  ... ./symlink  -> realfile      <- 符号链接
```

**` link to ` 根本不出现**（那是 GNU tar 的措辞）。于是硬链接排除在这类设备上静默失效。

修法：两种格式都认，`-> ` 的两种情况用**行首类型字符**区分（`-` 硬链接 / `l` 符号链接）。

端到端结果：新解析器识别出 `./usr/bin/gunzip -> ./usr/bin/uncompress` → 排除后解压
**EXIT=0** → `uncompress` 内容正确、`gunzip` 被排除。

**仍未经真机验证**：Kali rootfs 的**真实** tarball（含 `kali-arm64/dev/*`）没在设备上解过。
上面的验证用的是自造的同构 tar。

## ④ 的运行时验证（2026-11-02）

✅ **不需要 ARM** —— 换源产出的是纯 shell 脚本。

逐字复刻 `switchMirrorScript` 的 Ubuntu 分支（仅去掉 `apt-get update`，模拟器无 apt），
在沙箱里造出「两个源文件同时存在」的故障现场后真跑：

```
sh -n 语法检查        -> EXIT=0
执行                  -> EXIT=0
对照组（不退役）      -> apt 可见源文件 2 个   <- 会报 configured multiple times
执行脚本后            -> apt 可见源文件 1 个   <- 警告消失
```

文件变化：`sources.list` → `.praxis-disabled`（改名不删）；`ubuntu.sources` 被写入新镜像源；
两个 `.praxis-bak` 备份都保留原始内容（**可逆性有实证**）。

## ⑧ 的运行时验证（2026-11-02）

用本地假模型服务（mock，OpenAI 兼容）+ 直读数据库：

```
mock 收到两次请求：
  1) 标题生成  system「你负责生成简短的对话标题…」
               user「用户第一条消息：help me debug this error」
  2) 正文对话  system「你是 Praxis…」

数据库 sessions.title: 「新对话」 -> 「排查报错的方法」   <- AI 标题真的落库
```

修复过程中还发现并修掉两个问题：占位标题会吞掉 AI 标题（覆盖条件太严）、
以及「读-判断-写」之间的竞争窗口（改成原子条件 UPDATE）。

## ② 已修复：App 端到端进容器（2026-11-02 最终验证）

用新 APK + x86_64 proot 真实二进制（**无任何包装脚本**）在 API 30 模拟器上验证：

### 证据一：终端不再回退宿主 shell

点「新建会话」后，终端里**没有** `[当前不是 Linux 容器]` 那段提示 ——
说明 `port.open()` 返回了有效会话，`create()` 的 400ms 存活检查通过。

### 证据二：proot 进程真的在跑

```
12334 12300 u0_a130 libproot.so  libproot.so --link2symlink --kill-on-exit -0 -v 0 \
   -b /dev -b /proc -b /sys ... -w /root \
   -r /data/user/0/dev.praxis.debug/files/linux/shared /bin/sh -i
12338 12300 u0_a130 sh
```

两个 `libproot.so` 进程常驻，各带一个 `sh` 子进程 —— 容器内 shell。

### 证据三：loader 确实被 execve

```
readlink /proc/12338/exe
  -> /data/app/~~...==/dev.praxis.debug-...==/lib/x86_64/libproot-loader.so
```

容器内 `sh` 进程的 `exe` 指向 **nativeLibraryDir 里的 loader** —— 这直接证明
proot 是 `execve` 那个 loader 的，也就解释了为什么它必须待在可执行目录。

### 证据四：没有伪故障日志

`logcat -s ProotTermSession` 在会话建立后**零输出** —— 既没有「读取中断」，
也没有「已结束」。会话是健康的。

### 关于 `can't access tty`

模拟器上终端仍会打印 `can't access tty; job control turned off`。这是**预期**的：
`libpty.so` 只有 arm64 版本，x86 模拟器加载不了，于是走管道降级分支，
`/bin/sh -i` 拿不到 tty。**真机上有 libpty，走 PTY 分支，不会出现这句。**

### 三层根因回顾

| 层 | 缺陷 | 验证方式 |
|---|---|---|
| 一 | loader 缺执行位（644） | chmod 644/755 来回切 4 次，4 次一致 |
| 二 | 动态依赖找不到（名字 + 目录双错） | 修复前后：`CANNOT LINK` -> `REAL_BINARY_OK` |
| 三 | loader 在 filesDir，不可 execve | PROOT_LOADER 指向两处对比；最终 App 进容器 |

### 仍未覆盖

真实 Kali/Ubuntu rootfs、arm64 真机、PTY 分支的实际表现。上面的验证用的是
x86_64 proot + Alpine minirootfs，走的是管道降级分支。
## ② 的早期排查记录（保留，作为过程档案）

### 之前判断错了：x86 上不是验不了

我一直以为「proot 是 arm64 的，所以模拟器验不了」。实际缺的只是**二进制**，
而二进制可以换。做法：

```
1. 下 Termux 的 proot 5.1.107.102_x86_64.deb，取出 proot 与 loader
2. 下它的依赖 libtalloc.so.2、libandroid-shmem.so（同名 x86_64 包）
3. 把四者塞进 App 的 nativeLibraryDir/lib/x86_64/（该目录可写）
4. 下 alpine-minirootfs-x86_64.tar.gz（3.5MB）解到 filesDir/linux/shared
```

之后 **proot 在模拟器上真的跑起来了**：

```
PROOT_OK
uid=0(root)
NAME="Alpine Linux"
ls /  ->  bin dev etc home lib media mnt opt proc root run sbin srv sys tmp usr var
```

### 由此找到 ② 的真根因：loader 缺执行位

用 App 暂存在 filesDir 的 loader 跑，报：

```
proot error: execve("/bin/sh"): Permission denied
```

切换该文件的执行位，其余完全不变：

```
chmod 644 -> execve("/bin/sh"): Permission denied   FAIL
chmod 755 -> RUN_OK                                 PASS
chmod 644 -> FAIL
chmod 755 -> PASS
```

4 次一致，因果确定。`ProotLoaderStaging` 原来只调 `setReadable`，产物 644。

### 完整故障链

```
loader 644 -> proot 报 execve("/bin/sh"): Permission denied 并秒退
           -> create() 的 400ms 存活检查判定「启动后立即退出」
           -> 终端回退宿主 shell
           -> 宿主 mksh 无 tty 打出 can't access tty; job control turned off
```

整条链上没有一句提到 loader —— 这就是它一直没被找到的原因。

### 仍未解释的部分（诚实记录）

修完执行位后，在模拟器上**手工以 app 身份**执行 App 的**逐字 argv + 逐字 env**
（从包装脚本里 dump 出来的）可以成功，但 **App 自己发起会话仍报同一个错**。

已排除：loader 权限（755）、内容（md5 与原件一致）、argv（逐字复刻）、
env（仅 9 个变量，逐字复刻）、cwd（=/）、用户（run-as u0_a130）、
SELinux 标签（app_data_file 与 apk_data_file 两种都试过，均成功）。

剩下的怀疑点在「App 用 ProcessBuilder 派生进程时的上下文差异」，
但这台模拟器上无法进一步观测。**这是环境限制，不是已确认的代码缺陷。**
## ⑤ 已做运行时验证（2026-11-02，借 ② 修好后的容器会话）

② 修好之前，模拟器上根本没有活的容器会话可关，所以 ⑤ 只能靠单测。
② 打通后终于可以做真实验证。

### 做法与结果

```
关前 proot 进程数: 2
点标签页的 × 关掉一个会话
关后 proot 进程数: 1          <- 会话被正确回收，没有泄漏
日志: 管道会话 t1 已结束，退出码=0
「读取中断」伪故障日志: 0 条   <- PASS
```

关闭一条**活着的容器会话**后，既没有残留进程，也没有任何伪故障日志。

### 这条验证的边界（诚实说明）

退出码是 **0** 而不是异常 —— 说明 `close()` 里那句 `writer.write("exit\n")`
让 shell 自己干净退出了，泵线程拿到的是 **EOF**，不是
`read interrupted by close() on another thread`。

所以本次验证覆盖的是「正常关闭路径」：**关会话不产生噪声**。
而 ⑤ 那个 `closing.contains(id)` 判据专门处理的是「读被打断抛异常」的分支，
那条分支在管道模式下很难稳定复现（PTY 模式下才会）。

也就是说：`isExpectedShutdown` 的两条分支里，本次跑的是 `failed=false` 那条。
`failed=true` 那条仍只有单测覆盖，需要 arm64 真机（PTY 可用）才能真跑。

### 顺带确认的一件事

关闭会话后 proot 进程数从 2 变 1 —— 说明 `close()` → `pty.destroy()` /
`destroyForcibly()` 这条回收链在管道降级分支上也是有效的，没有孤儿进程。
## ⑤ 早期查证记录（保留）

原本想用「宿主终端」路径验 ⑤（关标签页 → 检查有没有伪故障日志）。实测发现不行：

- 终端页在非容器模式下走的是 `AndroidHostShellPort`（一次性命令执行，没有持久 PTY 会话、
  也没有 `closing` 机制）；
- 容器会话走 `ProotTerminalSessionPort`，而它需要 `libpty.so` —— 该库只有 arm64 版本，
  在 x86 模拟器上 `PTY 不可用，拒绝启动`，直接退回管道分支且没有 proot 可跑。

所以 ⑤ 的真实行为**只能在 arm64 真机上验**。目前的证据只有 4 条单测。

### 顺带修掉一个泄漏

审查 ⑤ 的代码时发现 `closing` 集合会永久增长：泵线程负责 `closing.remove(id)`，
但「会话自己结束（用户打 exit）→ 之后才关标签页」这条路径下，`close()` 仍会 `closing.add(id)`，
而此时泵线程已结束，没人再清。每开关一次标签页漏一个 id。

修法：只在 `item.process.isAlive` 时登记 —— 进程已死说明泵线程必然收尾过，
此刻没有「正在读的线程」需要被认定为预期关闭。
## 明确未验证的部分

| 项 | 缺什么 |
|---|---|
| ② | proot 在**真 ARM 设备**上能否真正起容器、不再退回宿主 shell |
| ③ | 真实 Kali NetHunter / Ubuntu rootfs 整包解压 |
| ④ | 真机上 `apt-get update` 是否真的不再报 configured multiple times（模拟器无 apt）|
| ⑤ | 真实 PTY 会话关闭时的表现 |
| ① | 「复制」写进剪贴板的内容（adb 层读不到剪贴板，只验到取文本逻辑）|

这些都需要一台 arm64 真机。**仅凭单测不能认为它们已经可用。**
