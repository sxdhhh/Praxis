# Praxis 交接文档（接手方必读）

> 写给「以后由电脑上的 DeepSeek harness 继续开发 Praxis」的人。
> 目标：读完这份文档，不需要问原作者任何问题，就能安全地改代码、跑 CI、推仓库。
>
> 最后更新：2026-11-01
> 文档自身位置：`docs/HANDOVER.md`

---

## 0. 三十秒版本

Praxis（中文「思行」）是一个 Android AI 助手客户端，包名 `dev.praxis` / debug 变体 `dev.praxis.debug`。
它的独特之处不是聊天，而是**内置一个真正能跑的 Linux 容器**（proot + Ubuntu 24.04 / Kali NetHunter），
模型可以直接在容器里执行命令、装软件、跑 sshd / vsftpd / frpc。

- 代码：`/root/Praxis`（本地），远端 `gh` = GitHub `sxdhhh/Praxis`，`localhub` = `sxd91.kdns.fr/sxd/Praxis`
- 分支：本地 `master` → 远端 `main`（**推送时必须映射 `master:main`**）
- 18 个 Gradle 模块，约 363 个 `.kt`，19,000+ 行
- 构建：JDK 21 / Gradle 9.7.0 / AGP 9.4.1 / compileSdk 37 / minSdk 28
- 记忆库：`/root/memories-full`（从 Operit ObjectBox 完整导出，2161 条 / 137 万字）

---

## 1. 铁律（违反会导致返工）

这一节是原作者反复强调、踩过坑才定下来的，**优先级高于任何「更优雅」的做法**。

### 1.1 不本地构建 APK

宿主机是 aarch64 的手机侧环境，本地跑 `./gradlew assembleDebug` 极慢且会 OOM
（`-Xmx2g -XX:MaxMetaspaceSize=768m` 是为 proot 省内存定的，装不下 18 个模块的元数据）。

**正确做法**：本地只做秒级静态校验 → 推 GitHub → 用 CI 构建。

```bash
python3 tools/check_kt_files.py <改动的 .kt 文件>     # 括号/KDoc/@Test 结构校验
python3 -m py_compile tools/*.py                      # 改过 Python 才需要
python3 -c "import yaml;yaml.safe_load(open('.github/workflows/build.yml'))"   # 改过 workflow
```

### 1.2 「构建禁令」是一条真实存在的临时状态

用户会说「启动构建禁令，这一轮只回好」。
此时：**不 push、不 assembleDebug、不跑任何 Gradle 构建**。
允许：本地 `git commit`、静态校验、真机上手动跑 proot/vsftpd 做实测。

禁令解除前不要自作主张推代码。禁令是临时的，每轮开头确认一次当前状态。

### 1.3 先取证，再改代码

遇到「某个功能起不来」时，**不要在代码里猜**。这个项目的历史上，
最省时的做法永远是：先在真机上把命令手动跑一遍，拿到真实报错，再回头改代码。

典型例子见第 5 节「终端起不来」：猜了半小时不如真机跑一次 proot。

### 1.4 改代码要一次改全

一次提交里同步改：定义、调用点、配置、文档、测试。
不要留「下次再补」，这个项目的 `WiringGuardTest` 就是专门用来钉这类漏改的。

### 1.5 不加用户设备专属的硬编码

不允许出现「屏幕宽 1280」「density 3.25」这类针对某台设备的参数，
注释里也不许留。所有实现必须通用自适应。

---

## 2. 仓库与推送

### 2.1 远端

| 名字 | 地址 | 说明 |
|---|---|---|
| `gh` | `https://github.com/sxdhhh/Praxis.git` | 主远端，CI 在这里跑 |
| `localhub` | `https://sxd91.kdns.fr/sxd/Praxis.git` | 自建 Git 服务，备份用 |

注意：**用户名是 `sxdhhh`（三个 h），不是 `sxd91`**。这是历史遗留，不要「纠正」它。

### 2.2 推送命令

分支映射 `master:main` 是必须的，远端默认分支叫 `main`。

```bash
# GitHub
cd /root/Praxis
GH_PUSH_TOKEN='<token>' GIT_ASKPASS=/root/.gh-askpass.sh GIT_TERMINAL_PROMPT=0 \
  PROOT_TMP_DIR=/tmp TMPDIR=/tmp timeout 300 git push gh master:main

# LocalHub
GIT_ASKPASS=/root/.lh-askpass.sh GIT_TERMINAL_PROMPT=0 \
  LH_TOKEN=$(cat /root/.localhub-token) PROOT_TMP_DIR=/tmp TMPDIR=/tmp \
  timeout 300 git push localhub master:main
```

凭证文件（**不入库、不写进文档正文**）：

- `/root/.gh-askpass.sh` —— 读环境变量 `GH_PUSH_TOKEN`
- `/root/.lh-askpass.sh` —— 读环境变量 `LH_TOKEN`
- `/root/.localhub-token` —— LocalHub token 本体

### 2.3 about 一个环境细节

推送时**必须**带 `PROOT_TMP_DIR=/tmp TMPDIR=/tmp`。
不带的话 `send-pack` 会因为 proot 的临时目录权限卡住，表现为「零流量、分支成功但 tag 卡死」。

---

## 3. 真实可用的构建与安装链路

### 3.1 CI

`.github/workflows/build.yml` 是主 workflow：

- `workflow_dispatch` 手动触发，`push` 到 main 也触发
- job `build`：产出 APK，上传 artifact `praxis-apk`
- job `verify`：跑单元测试 + lint

触发方式：

```bash
curl -s -X POST -H "Authorization: Bearer $GH_PUSH_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  https://api.github.com/repos/sxdhhh/Praxis/actions/workflows/build.yml/dispatches \
  -d '{"ref":"main"}'
```

查状态：

```bash
curl -s -H "Authorization: Bearer $GH_PUSH_TOKEN" \
  'https://api.github.com/repos/sxdhhh/Praxis/actions/runs?per_page=3' \
  | python3 -c "import json,sys;[print(r['head_sha'][:7],r['status'],r['conclusion'],r['name']) for r in json.load(sys.stdin)['workflow_runs']]"
```

### 3.2 下载并装到真机（这一步有坑）

```bash
# 1. 取 artifact（需要 unzip）
curl -sL -H "Authorization: Bearer $GH_PUSH_TOKEN" \
  'https://api.github.com/repos/sxdhhh/Praxis/actions/artifacts' \
  | python3 -c "import json,sys;[print(a['archive_download_url']) for a in json.load(sys.stdin)['artifacts'] if a['name']=='praxis-apk']"

# 2. ⚠️ 不能直接 pm install 从 /sdcard
cp /sdcard/Download/praxis-ci.apk /data/local/tmp/praxis-ci.apk
chmod 644 /data/local/tmp/praxis-ci.apk
pm install -r -d /data/local/tmp/praxis-ci.apk
```

**为什么必须中转**：`pm install` 由 `system_server` 执行，它对 `fuse`（/sdcard）
没有读权限，直接装会失败并抛 `avc: denied { read }`。
而且这一步必须用 **root shell**（`super_admin:shell`），proot 内写不进 `/data/local/tmp`。

### 3.3 真机常量

| 项 | 值 |
|---|---|
| 设备分辨率 | 1280×2772，density 520 |
| 主 Activity | `dev.praxis.debug/dev.praxis.MainActivity` |
| rootfs 路径 | `/data/data/dev.praxis.debug/files/linux/shared` |
| rootfs 发行版 | Ubuntu 24.04.3 LTS（清华源） |
| native 库 | `libproot.so` / `libproot-loader.so` / `libpty.so`，都在 APK 的 `lib/arm64/` |
| 截图 | `screencap -p` 可用（实测 1.1MB+） |

⚠️ 主 Activity 是 `dev.praxis.MainActivity`，**不是** `.MainActivity`。
写成 `.MainActivity` 会解析成 `dev.praxis.debug.MainActivity` 然后启动失败。

---

## 4. 模块地图

```
app/                     Application、MainActivity、引导链接线、data/ 下的 Port 实现
core/
  model/                 纯数据类（Message、ModelSettings、ThemeSettings…）
  data/                  Repository / DataStore / 加密设置存储
  ai/                    Provider（OpenAI 兼容）、流式、工具调用
  tool/                  工具注册与执行
  memory/                记忆存储与检索
  terminal/              PTY 会话（JNI 到 libpty）
  linux/                 ⭐ 容器全套：ProotCommand / LinuxDistro / FtpServer / RemoteAccess
  workflow/              多步工作流
  plugin/                插件系统
  hyperceiler-common/    从 HyperCeiler 移植的公共库（引导链依赖）
ui/
  theme/                 Miuix 主题、莫奈取色
  design/                设计系统组件（含 ScrollThumbRail 等）
  widgets/               通用控件
  navigation/            导航
  icons/                 图标
  provision/             ⭐ 引导页（1:1 复刻 HyperCeiler 的 provision）
feature/
  chat/                  聊天页
  settings/              设置页
  terminal/              终端页
  shell/                 命令行
preview/                 Paparazzi 截图预览（只在 CI 的 x86_64 上能跑）
build-plugins/           自定义 Gradle 约定插件（praxis.* 前缀）
third_party/miuix/       内置的 miuix AAR
```

### 4.1 依赖方向

`feature → ui → core`，`app` 在最上面组装。
`core/*` 之间不允许互相依赖成环。
UI 层不许直接碰 `android.content.Context` 之外的东西——见 `WiringGuardTest`。

---

## 5. 容器层（core/linux）—— 本项目最容易踩坑的地方

这一节是整个交接文档里**信息密度最高**的部分。所有内容都是真机实测出来的。

### 5.1 proot 环境变量：一个都不能少

在容器里跑任何程序的正确环境：

```sh
HOME=/root
TERM=xterm-256color
LANG=C.UTF-8
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin   # ← 必须是容器 PATH
PROOT_TMP_DIR=<cacheDir>
PROOT_NO_SECCOMP=1
PROOT_LOADER=<libproot-loader.so 的绝对路径>                        # ← 最容易漏
```

**必须清空继承的 Android 环境**，尤其 `LD_LIBRARY_PATH` / `LD_PRELOAD` / Android 的 `PATH`。

两个历史 bug，都是这两条引起的：

| 缺失项 | 报错 | 为什么难查 |
|---|---|---|
| `PROOT_LOADER` | `proot error: execve("/bin/sh"): No such file or directory` | 这句话的意思是「loader 找不到」，不是「/bin/sh 不存在」，极易误导 |
| 容器 `PATH` | `/bin/sh: 1: ls: not found` | 因为继承的是 Android PATH，proot 原样带进容器 |

启动命令模板：

```sh
$LIB/libproot.so --link2symlink --kill-on-exit -0 -v 0 \
  -b /dev -b /proc -b /sys -w /root -r $R <程序>
```

### 5.2 vsftpd（FTP）

**这个项目的 FTP 是在容器里装 vsftpd**，不是 App 内嵌 Java FTP 服务。
原因写在 `FtpServer.kt` 类 KDoc 里（SELinux/uid 映射 + 生命周期统一）。

三个必须处理、否则跑不起来的问题：

#### (1) 必须关闭 chroot —— 2026-10-31 实测反转

旧配置 `chroot_local_user=YES` + `allow_writeable_chroot=YES` 是**普通 Linux 的正解**，
在 proot 下是灾难。现象极具迷惑性：

```
230 Login successful.                     ← 登录成功
227 Entering Passive Mode (127,0,0,1,8,80)
data conn: OK                             ← 数据连接也建起来了
150 Here comes the directory listing.     ← 服务端愿意发数据
226 Transfer done (but failed to open directory).   ← 但列不出东西
```

`SIZE` / `RETR` 一律 `550`。

**根因**：proot 靠 ptrace 拦截路径系统调用来伪造根目录，host↔guest 映射是固定的；
`chroot()` 会改进程自己的根视图，映射立刻失效，之后所有 `openat` 都算错路径。

**改法**：`chroot_local_user=NO` + `local_root=/home/ubuntu`。
代价是客户端能 `CDUP` 跳出家目录——在「用户自己的容器、proot 本就无真实安全边界」
的前提下，这个取舍是对的。

#### (2) 不能设 `pasv_address=0.0.0.0`

它会把这串**原样发给客户端**：

```
227 Entering Passive Mode (0,0,0,0,8,85)
```

客户端拿 0.0.0.0 去连数据端口必然失败。**整项不写**，让 vsftpd 用控制连接的本地地址应答。

#### (3) 必须自己建 `secure_chroot_dir`

```
500 OOPS: vsftpd: not found: directory given in
          'secure_chroot_dir':/var/run/vsftpd/empty
```

这个目录在普通发行版由包脚本 + init 创建，proot 没有 init。
安装脚本必须 `mkdir -p /var/run/vsftpd/empty`（顺带建 `/var/log`，否则 xferlog 静默丢失）。

#### (4) PAM 必须极简化

proot 里 `pam_loginuid` / `pam_systemd` 会失败，导致**所有登录被拒**。
所以 `pam_service_name` 指向自建的 `vsftpd.praxis`（只有 `pam_unix`）。

#### 最终实测记录

```
pwd: /home/ubuntu
--- LIST ---
-rw-r--r-- 1 ubuntu ubuntu 22 Oct 31 hello.txt
--- RETR ---  download: b'hello from praxis ftp\n'
--- STOR ---  size: 20
--- MKD/RMD --- 250 Remove directory operation successful.
ALL OK
```

### 5.3 sshd

与 FTP 同样的 PAM 问题、同样的端口约束（不能绑 < 1024，proot 不给 `CAP_NET_BIND_SERVICE`）。

### 5.4 端口分配约定

| 端口 | 用途 |
|---|---|
| 2127 | FTP 控制端口 |
| 2128–2136 | FTP 被动模式数据段 |
| 2222 | SSH（避开 FTP） |
| 8791 | 被 MT APK MCP 占用，别用 |
| 8792 | pk-node 默认端口 |
| 8793 | preview_server.py |

### 5.5 发行版支持现状（**接手后大概率要扩展**）

目前只支持两个，都是 Debian 系：

| 发行版 | rootfs 来源 | 体积 |
|---|---|---|
| Ubuntu 24.04 LTS | ubuntu-base 官方 tar.gz（清华/阿里镜像） | 压缩 30MB，解开 ~120MB |
| Kali NetHunter minimal | kali.download nethunter-images | 压缩 131MB，解开 ~500MB |

**为什么选 NetHunter 而不是官方 Kali**：官方 `cdimage.kali.org` 的 arm64 是安装器
（installer），需要分区+grub，proot 根本跑不通。NetHunter rootfs 本身就是可直接 `tar -x` 的根文件系统。

**扩展新发行版时要注意**：`DistroFamily` 目前只有 `Debian("apt")` 一个成员，
`FtpServerScript.installScript` 和 `RemoteAccess` 里的 `apt-get` 是**硬编码**的。
要加 Alpine（apk）/ Arch（pacman）必须先让这两处走 `DistroFamily` 分支，否则装上就跑不起来。

---

## 6. 记忆库

### 6.1 位置

`/root/memories-full/`

| 文件 | 内容 |
|---|---|
| `all-memories.json` | 全量结构化（2161 条，137 万字符） |
| `all-memories.md` | 全量可读版（2.4MB） |
| `PRAXIS-only.md` | 只含 Praxis/operit-next 相关（641 条，47 万字） |
| `by-topic/*.md` | 按主题分组的 10 个文件 |

### 6.2 来源与解析方式（重要，别重复踩坑）

原始库：`/data/data/com.ai.assistance.operit/files/objectbox/data.mdb`（ObjectBox 二进制）

**没有可用的 ObjectBox 反序列化工具**，历史做法是从二进制里提取连续可打印 UTF-8 串：

- 正则：连续 `>= 20` 字符的可打印 ASCII 或合法多字节 UTF-8
- 过滤：UUID、`reply_finalized_auto` 等元数据噪声、过短纯 ASCII 串
- 去重：SHA-256

这样能拿到全部正文，但会混入少量元数据碎片。这是**已知的、可接受的**精度损失。

同日期的旧导出 `/root/memories-all`（2026-10-08，1099 条）已过时，别用。

---

## 7. 已知未完成事项

接手时这些是「已知欠债」，不是「bug」：

| 项 | 状态 |
|---|---|
| 多发行版支持 | 只有 Ubuntu + Kali，两者都是 apt 系 |
| `ChatSettingsBar` 两个 Popup | 仍硬切，未做动画 |
| 环境页容器与终端 PTY | 两套独立会话表，未统一 |
| release 包签名 | 未签名，只有 debug 可用 |
| 路线 A（wasmJs 浏览器预览） | 未启动，目前只有 Paparazzi 走 CI |
| FTP 的 chroot 隔离 | 已关闭（见 5.2），无替代隔离方案 |

---

## 8. 常见失败对照表

| 现象 | 真实原因 |
|---|---|
| `pm install` 报 `avc: denied { read }` | 从 `/sdcard` 直接装，要中转 `/data/local/tmp` |
| `am start` 说 Activity 不存在 | 入口是 `dev.praxis.MainActivity`，不是 `.MainActivity` |
| 终端报 `proot 进程启动后立即退出` | 多半是 `PROOT_LOADER` 没传（见 5.1） |
| 容器里 `ls: not found` | PATH 被 Android 的污染了（见 5.1） |
| FTP 能登录但列不出文件 | chroot 开着（见 5.2） |
| FTP 返回 `227 (0,0,0,0,...)` | `pasv_address=0.0.0.0`（见 5.2） |
| FTP 每个连接都 `500 OOPS` | `secure_chroot_dir` 不存在（见 5.2） |
| `:preview:compileDebugKotlin` OOM Metaspace | 本地内存不够，必须走 CI |
| CI 报 `:terminal` "No variants exist" | 忘了 `submodules: true`（历史问题） |
| push 卡住零流量 | 没带 `PROOT_TMP_DIR=/tmp TMPDIR=/tmp` |

---

## 9. 开始工作的建议顺序

1. 读 `README.md` 了解功能全貌
2. 读这份文档的第 1 节铁律和第 5 节容器层
3. 用 `query_memory` 或直接搜 `/root/memories-full/PRAXIS-only.md` 找具体历史决策
4. 改代码前先跑一次静态校验，确认基线是干净的
5. 小步提交，每次提交都写清楚「为什么」而不是「改了什么」

---

## 10. 一句话总结

这个项目的价值不在代码量，而在**一堆真机实测出来的反直觉结论**——
chroot 在 proot 下不能用、`pasv_address=0.0.0.0` 会原样发出、`PROOT_LOADER` 缺了报的是别的东西。

这些结论都在第 5 节和第 8 节。**动容器层之前先把它们读完**，能省掉几天。
