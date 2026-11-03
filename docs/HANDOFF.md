# Praxis 改造任务 · 交接说明

> 写于 2026-11-03 09:00
> 用途：设备恢复或换人接手时，按本文档可以直接继续，不必重新摸索。

---

## 一、任务当前处于什么状态

**代码全部写完，验证做了 7/24，发布未做。**

| 阶段 | 状态 |
|---|---|
| 代码实现（P1–P7 共 8 项） | 完成 |
| CI | 全绿（#138） |
| 全仓静态检查 | 374 文件零问题 |
| APK 构建 | v136 已下载 |
| 验收标准实证 | **7 / 24** |
| 发布 | 未做（通道未建立 + 无凭据） |

---

## 二、为什么停在这里

两个外部阻塞，都不是代码问题。

### 阻塞 1：真机不可用（19 小时+）

诊断结论（不是简单「离线」）：

```
ping 192.168.1.199   -> 100% 丢包（多为防火墙拦 ICMP，不足以判断）
TCP 连 5555          -> 成功（11-52ms），网络是通的
读 ADB banner        -> 无任何响应
adb connect          -> failed to connect
```

**手机网络通，但 adb 无线调试服务没在跑。** 需在手机上重新开「无线调试」，或插 USB。

### 阻塞 2：发布通道从未建立

`https://sxd.sxdshh.kdns.fr/apk/` -> **401 Authorization Required**

翻遍 `docs/memories/all-memories.md`（42262 行）确认：没有任何 Praxis 发布到该路径的记录。
Praxis 自己的一条记载写着「Praxis 未发布，无更新通道」。

这不是「照旧上传」，是通道尚未建立。

---

## 三、设备恢复后的执行顺序

### 第 1 步：跑现成的验证脚本

```bash
python tools/verify_device_terminal.py
```

覆盖：
- 步骤 0 设备状态
- 步骤 1 终端是否连容器登录 shell（stty 报错 / can't access tty / 提示符）
- 步骤 2 批量置顶排序（只读判据）

**注意**：此脚本本身还没在真机上跑过。

### 第 2 步：补 P1 的核心验收（脚本无法覆盖）

| 验收标准 | 怎么做 |
|---|---|
| 可跨行扩展 | 长按一行 -> 拖动手柄到别的行 -> 看选区是否连续 |
| 复制粘贴内容一致 | 选一段 -> 复制 -> 粘到输入框 -> 逐字比对 |
| ANSI 配色在选择态正确 | 跑 `ls --color` -> 进入选择态 -> 看颜色是否还在 |
| **帧耗时判据** | echo 填 1000 行 scrollback -> `dumpsys gfxinfo dev.praxis.debug` -> 看 50th/90th percentile 是否 > 16ms |

帧耗时那条是目标明确要求的，**从没测过**。若真的超 16ms，目标要求退回「仅选择态切单块」方案。

### 第 3 步：补 P4 的消息数比对

目标要求「发消息 -> 记录 token **与消息数** -> 杀 App 重进 -> 数字与消息都还在」。
只验了 token，**消息数没逐条比对**。

做法：杀进程前 dump sessions/messages 表 -> 重进 -> 再 dump -> 对比行数与内容。

### 第 4 步：P5b 动画

需录屏（`adb shell screenrecord`）或连拍，观察模型/功能菜单展开关闭是否有形变动画。

### 第 5 步：发布

两个选项，需用户确认：

**A′. GitHub Release 发 debug 包**

```powershell
Move-Item docs/release-workflow-draft.yml .github/workflows/release.yml
# 然后推送，再手动 dispatch（workflow_dispatch）
```

草稿已验证：版本号能被 grep 正确提取（1.0.0）、action 版本与 build.yml 一致、YAML 解析通过。

**B. 给 /apk/ 凭据** -> 写 `tools/publish_apk.*`

---

## 四、关键文件索引

```
docs/SETTLEMENT.md                结算报告（含 24 条验收逐条对照，必读）
docs/TERMINAL-PROOT.md            终端三问题完整排查
docs/EMULATOR-LIMITS.md           模拟器为何装不上本 APK
docs/release-workflow-draft.yml   A′ 发布草稿（放 docs/ 不生效）
tools/verify_device_terminal.py   真机验证脚本
```

APK：`C:\creat-agent\apk-in\v136\app-debug.apk`（88.1MB，md5 `097c86f5aa5f24612d385e0af89d0a79`）

---

## 五、装包注意事项（踩过的坑）

### 无线 ADB 传大文件会丢数据

三种方式都试过，全部失败：

```
adb install                -> failed（无错误信息）
adb push + pm install      -> 30534-byte write failed
adb install --no-streaming -> 65544-byte write failed
```

**必须分块推送再合并**：

```powershell
# 切成 3 段（30MB/30MB/29.5MB）后逐段推
adb push part0.bin /data/local/tmp/p0.bin
adb push part1.bin /data/local/tmp/p1.bin
adb push part2.bin /data/local/tmp/p2.bin
adb shell 'cat /data/local/tmp/p0.bin /data/local/tmp/p1.bin /data/local/tmp/p2.bin > /data/local/tmp/v136.apk'
adb shell pm install -r -d /data/local/tmp/v136.apk
```

### uiautomator 需要 --compressed

不加会一直报 `ERROR: could not get idle state`：

```
uiautomator dump --compressed /data/local/tmp/x.xml
```

### 直读数据库

```bash
adb shell 'cp /data/data/dev.praxis.debug/databases/praxis.db /data/local/tmp/ && chmod 666 /data/local/tmp/praxis.db'
adb pull /data/local/tmp/praxis.db
```

（设备上没有 sqlite3，要拉回 PC 查）

---

## 六、若决定继续改代码（不推荐）

**不推荐的原因**：未验证项已达 17 条，每加一项改动就多一份「CI 绿但没人跑过」的债。
这个项目历史上已经因为「护栏写了但 CI 不跑」翻过车。

如果确实要改，优先事项是：

1. **P6 剩余**：圆角 token 只迁了 10/67 处；页面排版、拖动动画接入未做
2. **linux_exec 是否降权/移除**（需产品决策，影响模型可见工具清单）

---

## 七、用户反馈里仍未解决的

| 用户原话 | 状态 |
|---|---|
| 「proot 应该持久化，AI 每次脚本都会起一个 proot 会话」 | 已查清根因（模型选了无状态的 linux_exec），已加描述警告；**是否降权需决策** |
| 「终端连接的应该是内置的 linux 系统而不是 /bin/sh」 | 已改代码，**真机未验证** |
| 「stty: inappropriate ioctl for device」 | 已改代码，**真机未验证** |
| 「重进软件可能会出现部分内容消失」 | 已修用量清零；「部分内容消失」**具体指什么从未确认** |

最后一条问过多轮没得到答复，是最需要澄清的。
