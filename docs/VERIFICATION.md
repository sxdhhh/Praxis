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
| ① | 终端无法长按复制/全选 | 模拟器实测 + 单测 | 长按出选择态、操作条（复制/全选/完成）、跨行高亮、全选扩到整缓冲 |
| ② | proot 启动即退 | 仅单测 | 14 条：loader 铺放 9 条、登录 shell 选择 6 条 |
| ③ | Kali rootfs 解压失败 | **运行时复现 + 修复验证** | 见下 |
| ④ | apt 源重复配置警告 | **运行时复现 + 修复验证** | 见下 |
| ⑤ | 会话读取中断 | 仅单测 | 4 条：预期收尾 vs 真故障的判定 |
| ⑥ | 聊天页进度条/回底按钮 | 模拟器截图 + 单测 | 截图确认轨道消失、按钮上移；9 条显示逻辑单测 |
| ⑦ | 当前对话删不掉 | 模拟器 + 数据库 + 护栏 | 删除后直读 sessions 表确认行真的消失 |
| ⑧ | 无 AI 生成标题 | mock 服务 + 数据库 + 单测 | 见下 |

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

## 明确未验证的部分

| 项 | 缺什么 |
|---|---|
| ② | proot 在**真 ARM 设备**上能否真正起容器、不再退回宿主 shell |
| ③ | 真实 Kali NetHunter / Ubuntu rootfs 整包解压 |
| ④ | 真机上 `apt-get update` 是否真的不再报 configured multiple times（模拟器无 apt）|
| ⑤ | 真实 PTY 会话关闭时的表现 |
| ① | 「复制」写进剪贴板的内容（adb 层读不到剪贴板，只验到取文本逻辑）|

这些都需要一台 arm64 真机。**仅凭单测不能认为它们已经可用。**
