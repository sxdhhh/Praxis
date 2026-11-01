# 09-LocalHub与运维

共 138 条 / 44445 字符


## gitsrv 补全：GITHUB_* 环境变量注入 + tag 发版链路验证通过

# gitsrv 补全：GITHUB_* 环境变量注入 + tag 发版链路验证通过

日期：2026-10-11（承接「gitsrv 本地 GitHub 模拟器」）。

## ★ 新修的真 bug：step 环境缺 GITHUB_* 变量
现象：workflow 脚本里 `$GITHUB_SHA` / `$GITHUB_REF` / `$GITHUB_REF_NAME` **全是空串**。
实测证据：一个用 `case "$GITHUB_REF" in refs/tags/*` 判定的 step，因为 `GITHUB_REF` 为空，走到了 `*)` 分支并 `exit 1`，整个 job 失败。
真因：runner 的 `_run_run_step` / `_run_uses_step` 构造 env 时只合并了 config env + GITHUB_ENV + step env，**从未注入 GITHUB_* 这套**。
修法：新增 `JobRunner._github_env_for_step(job, ctx)`，注入：
GITHUB_SHA / GITHUB_REF / GITHUB_REF_NAME / GITHUB_REF_TYPE / GITHUB_REPOSITORY / GITHUB_REPOSITORY_OWNER / GITHUB_WORKSPACE / GITHUB_RUN_ID / GITHUB_RUN_NUMBER / GITHUB_JOB / GITHUB_EVENT_NAME / GITHUB_ACTOR / GITHUB_WORKFLOW / GITHUB_SERVER_URL / GITHUB_API_URL / GITHUB_TOKEN / GITHUB_ACTIONS / CI / RUNNER_OS / RUNNER_ARCH / RUNNER_NAME，并按需补 GITHUB_HEAD_REF / GITHUB_BASE_REF。
两处调用点都加了（run: 与 uses:）。

## ★ 新增：`--run` 支持 ref 参数（第 6 个位置参数）
用法：`runner.py --run <owner/repo> <sha> <workflow> [event] [ref]`
传 `refs/tags/android-vX` 会正确设 tag=、branch=""，从而让 `if: startsWith(github.ref, 'refs/tags/android-v')` 的 release job 真正执行；默认 `refs/heads/main`。

## tag 发版链路验证（独立 demo 仓库，避免白跑真实构建）
建 `/tmp/tagtest`（含临时 `tag-test.yml`：build 造假产物 → upload-artifact → release job 带 job 级 if + needs: build + download-artifact + gh-release → 另加一个 `never-on-branch` 反例 job）。

执行：
```
python3 bin/sync.py --from /tmp/tagtest --to demo/tagtest
python3 bin/runner.py --run demo/tagtest <sha> .github/workflows/tag-test.yml push refs/tags/android-v9.9.9
```
结果（全绿）：
```
run #1 结束：success
  job build            completed success
  job release          completed success   <- job 级 if 正确放行
  job never-on-branch  completed skipped  [job 级 if 不满足：startsWith(github.ref, 'refs/heads/')]
  artifact fake-apk (30 字节)
```

产物与下载验证：
- `releases/demo/tagtest/android-v9.9.9/app-debug.apk` + `release.json` + `RELEASES.json` 均正确
- `release.json` 里 `ref = refs/tags/android-v9.9.9` 证明 `${{ github.ref }}` 插值正确，`target_commitish` 是真实 sha
- `GET /<repo>/releases/download/<tag>/<file>` → HTTP 200 (30B)
- `GET /<repo>/artifacts/<id>` → HTTP 200 (164B zip)，zip 内是 `out/app-debug.apk`（保留工作区相对路径）
- 页面全 200：`/`、`/demo/tagtest`、`/demo/tagtest/actions`、`/demo/tagtest/releases`、`/demo/tagtest/actions/runs/1`
- 日志端点 `/<repo>/actions/runs/1/logs/build` 正常返回带时间戳的日志

## 备注
- `sync.py` 没有 `--force` 参数（只有 `--from/--to/--emit-event/--quiet`）；覆盖靠 refspec 里的 `+` 前缀，本来就是强制的。
- 测试脚本里 `shell: bash` 才支持 `${VAR:0:8}` 这类 bash 切片；默认 `sh` 下行不通。runner 的 shell 解析已支持 `shell: bash` / `bash` / `sh` / `python`。

---

## [项目] LocalHub 登录态丢失修复：sessions 被 dbsync 清空（2026-10-17）

# [项目] LocalHub 登录态丢失修复：sessions 被 dbsync 清空（2026-10-17）

## 现象
登录成功但刷新即掉线；db.json 里 `sessions` 从对象退化成数组 `[]`。

## 根因（证据链完整）
1. `src/dbsync.js` 的 `LIST_KEYS` 里错放了 `sessions`，每 30 秒一次的跨节点元数据同步（`shard-sync.js tick()`）把它当实体数组合并。
2. `sessions` 在 db.json 里是**以 token 为键的对象** `{ "<raw>.<hmac>": { userId, createdAt, ip, userAgent } }`。
3. `asList()` 把对象转成数组时**丢掉键**（token 本身）；`idOf()` 只认 `id`/`key`/`login`，session 记录只有 `userId` → 返回 null。
4. `mergeList()` 里 `if (id === null) continue;` 把所有 session 逐条跳过 → `r.list = []`。
5. `mergeDb()` 执行 `result[key] = r.list`；`shard-sync.applyMerged()` 写进内存；`store.save()` 落盘。
6. 结果：**任何登录态最多活 30 秒**；且磁盘上被写成 `[]`，此后 `arr[token] = {...}` 在数组上赋值不报错但 `Object.entries()` 枚举不到，会话写入静默丢失。

## 修复（4 个文件 + 1 个环境变量）
- `src/dbsync.js`：sessions 移出 `LIST_KEYS`，新增 `LOCAL_ONLY_KEYS = ['sessions']`（本机专有，不参与合并）；`mergeDb` 加「非空变空」防御闸（记录 `keptLocalBecauseWouldEmpty`）；导出 `LIST_KEYS`/`LOCAL_ONLY_KEYS`。**注意 `tokens` 仍留在 LIST_KEYS**（PAT 是数组、有 id，跨节点共享是有意为之）。
- `src/store.js`：新增 `repairSessions()`，在 `load()` 里把非对象形态重置为 `{}` 并告警；`snapshotHot()` 落盘前再兜一次形态。
- `src/shard-sync.js`：`strip()` 加 `repairLocalShape()`，把 `sessions: []` 修成 `{}`（否则 applyMerged 会把数组写回内存）。
- `src/auth.js`：新增 `sessionsMap()` 防御（写会话前保证容器是对象）；`SESSION_TTL_MS` 改为读 `LH_SESSION_DAYS`（默认 30 天）；`userFromToken()` 加**滑动续期**（用过半寿命就把 createdAt 推到当前，只在一半处续一次避免高频写盘）。

## 验证
- 单测 `/root/lh-sess/test_session.js` 15/15（含复现原始事故、防御闸、幂等）。
- 端到端 `/root/lh-sess/e2e_session.sh` 15/15（真实 store+auth+dbsync，5 轮同步后会话仍在、落盘、重启、损坏自愈）。
- 生产实测：登录 → 跨 75 秒（2 个 30s 同步周期）→ `/settings` 仍 200 且显示 logout；`sessions 键数 1`；重启后仍已登录。
- 公网 `https://sxd91.kdns.fr` 登录 302 → `/`，`/settings`、`/notifications` 均 200 已登录。
- `Set-Cookie: lh_session=...; Max-Age=2592000; Path=/; HttpOnly; SameSite=Lax`。

## 重要事实（勿再踩）
- **登录表单字段名是 `login`，不是 `username`**（routes.js `doLogin` 读 `form.login`）。用 curl 测登录必须 `-d "login=sxd"`，否则报「用户名或密码不正确」而误判为密码错。
- 账号 `sxd` / 密码 `sxd369870` 验证通过（`store.verifyPassword(u,'sxd369870') === true`），isAdmin=true。
- 会话**不跨节点共享是有意的**：两端 `config.secret` 相同（指纹 a31ee720cf164ea6），token 在另一台本可校验通过，但复制 sessions 等于把登录凭据铺到每台机器，任一节点被读内存即扩大暴露面。请求到哪台就在哪台校验。
- 修复部署位置：两台 `/opt/localhub/src/`；备份在 `/opt/localhub/backups/sessfix-<stamp>/`（含 db.json.bak）。改动只在 aws-1 做，再同步到 aws-2。

## 相关文件
- 本地工作副本：`/root/lh-sess/src/`（改的 4 个文件）、`/root/lh-sess/full/src/`（完整 src 用于跑测试）。
- 测试：`/root/lh-sess/test_session.js`、`e2e_session.sh`、`test_login_live.sh`、`deploy_sessfix.sh`。

---

## 目录：`ui/animation/` 9 文件 896 行，分交互动画 + 预测性返回转场两块。

目录：`ui/animation/` 9 文件 896 行，分交互动画 + 预测性返回转场两块。

# 一、交互动画（2 文件，均来自 KernelSU GPL-3.0）

## DampedDragAnimation.kt（5660 字节）
五套独立 spring（关键是非对称阻尼）：
```kotlin
valueAnimationSpec          = spring(1f,   1000f, visibilityThreshold)
velocityAnimationSpec       = spring(0.5f, 300f,  visibilityThreshold * 10f)
pressProgressAnimationSpec  = spring(1f,   1000f, 0.001f)
scaleXAnimationSpec         = spring(0.6f, 250f,  0.001f)
scaleYAnimationSpec         = spring(0.7f, 250f,  0.001f)  // X/Y 故意不同
```
`release()` 先等位移靠近目标再收缩放（避免缩放回弹盖住位移）：
```kotlin
awaitFrame()
if (value != targetValue) {
    val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
    snapshotFlow { valueAnimation.value }
        .filter { abs(it - targetValue) < threshold }.first()
}
```
`animateToValue` 用 `MutatorMutex` 防并发，内部 press→animate→release。

## InteractiveHighlight.kt（4600 字节）
AGSL RuntimeShader 按压光斑（`@SuppressLint("NewApi")`，API33+）：
```glsl
half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}
```
Kotlin 侧：全局扫白 `White@0.06*progress`（BlendMode.Plus）+ 光斑 `White@0.12*progress`，`radius = size.minDimension * 1.2f`，`positionAnimation.snapTo(change.position)` 跟手。

# 二、预测性返回转场（7 文件）
`AospNavTransition`(10527B) / `ScaleNavTransition`(5158B) / `NoPredictiveBackTransition`(4642B) / `ClassicNavTransition`(2509B) / `NavTransitionEasing`(1107B) / `InstallerNavTransition`(902B) / `NavTransitionGeometry`(636B)

**五档分发器**：None→NoPredictiveBack / MIUIX→NavTransitions.MiuixDefault / AOSP→AospNavTransition / Scale→scaleNavTransition(exitDirection) / Classic→ClassicNavTransition

**Classic 的 motion**：commit/programmatic 用 `Tween(200, CubicBezierEasing(0.2,0,0,1))`，cancel 用 `Spring(stiffness=1500f)`。

**NavTransitionGeometry（636 字节但关键）**：把缩放/位移吸附到整像素，否则非整数缩放边缘 1px 抖动。
```kotlin
snapScaleToPixelExtent(scale, extent) = (scale*extent).roundToInt() / extent
snapTranslationToPixelEdge(translation, scale, extent, pivotFraction=0.5f) =
    (translation + extent*pivotFraction*(1-scale)).roundToInt() - extent*pivotFraction*(1-scale)
```

**FastOutExtraSlowIn**：单 CubicBezier 表达不了「先极快后极慢」，用两段拼接，knot=(0.1667,0.4)。

---

## LocalHub（自建 Git 服务）上传要点 —— 2026-10-11 实测

# LocalHub（自建 Git 服务）上传要点 —— 2026-10-11 实测

## 服务器

  · ngrok 公网：https://nurture-street-democracy.ngrok-free.dev
  · **内网直连（推荐）**：http://192.168.1.80:3000
    你在改代码时会重启，服务随时 502/000 —— 属正常，等即可
  · 软件：LocalHub（GitHub 兼容），页面 `<title>Explore · LocalHub</title>`
  · 用户：sxd（邮箱 18723143414@163.com）
  · token：`ghp_3c73***REDACTED***0c65`（名 DEEPSEEK，scope repo）
    已存 /root/.localhub-token（权限 600，勿提交）

## ★ API 是 /api/... 不是 /api/v1/...

  GET  /api/user                     鉴权探测
  GET  /api/repos                    仓库列表
  POST /api/repos {"name":"x","private":true}   建仓 → 201（已存在 422）
  PATCH /api/repos/:o/:r             改描述 ← **实测有效**（PUT/POST 都 404）
  POST /api/repos/:o/:r/releases     {tag_name,name,body} → 201（已存在 422）
  POST /api/repos/:o/:r/releases/:id/assets?name=f   传原始二进制
  /docs 有完整端点表

## ★ 认证方式：GIT_ASKPASS（不要把 token 写进 remote）

  写进 remote URL 会落进 .git/config，有误发风险。改用：

    export LH_TOKEN=...
    export GIT_ASKPASS=/root/.lh-askpass.sh   # 问 username 给 sxd，问 password 给 token
    export GIT_TERMINAL_PROMPT=0

## ★ 三个必踩的坑（都已解决）

### ① 浅克隆被拒收

  `! [remote rejected] master -> master (shallow update not allowed)`
  7 个上游镜像是 `clone --depth=1` 来的（.git/shallow 存在）。
  → 先 `git fetch --unshallow` 补全历史（GitHub 可访问，能补）。

### ② 大包 ngrok 死锁

  700M 的仓库推 12 分钟零流量零 IO，send-pack 挂起。
  → 换内网 192.168.1.80:3000（响应 45ms vs 秒级）。

### ③ --tags 一把梭会卡死

  255 个 tag 一次性推 → send-pack 零流量挂起。
  → 改成 `refs/tags/X:refs/tags/X` **逐个推**（单个约 40-95s）。

## ★ detached HEAD 推不动

  /root/cloudflared-src 不在任何分支上，`refs/heads/main` 会报
  `src refspec does not match any`。
  → 改推 `<sha>:refs/heads/main`，给它造分支。

## 学到的排查手法

  判断 push 是真在传还是死了：
    cat /sys/class/net/*/statistics/tx_bytes  # 间隔采样，Δ=0 就是死锁
    cat /proc/<pid>/io                        # read_bytes/write_bytes 不动 = 死锁

## 脚本

  /root/upload-all.sh      第一轮（ngrok）
  /root/upload-lan.sh      内网版
  /root/upload-lan2.sh     内网版 + README 自动补简介
  /root/set-descriptions.py  批量写简介（PATCH）
  /root/watchdog.sh        ★ 守望脚本：等站点起来 → 推仓库 → 逐个推 tag → 补 release
  /root/watchdog.log       日志

## 简介已写

  26 个仓库已补 description（PKTCH 写入）。
  其中 8 个因未建仓当时 404，守望脚本建仓后会从 README 自动补。

---

## ikuuu 全流程打通：注册/登录/签到/订阅（真实账号验证）2026-10-31

# ikuuu 全流程打通：注册/登录/签到/订阅（真实账号验证）2026-10-31

## 实测结果（真实账号 3851310227@qq.com / Ik3851aA9x / name iku38513102）
- 注册：ret=1 「注册成功！正在进入用户中心」✅
- 签到：ok=true 「你获得了 2512 MB流量」✅（第二次返回 already=true「您似乎已经签到过了」）
- 订阅：成功提取 Clash 链接 ✅
- 但免费版账号订阅地址返回 HTTP 404（站点策略，非技术问题）

## 关键协议细节（新发现）

### 1. 登录必须带 phase=password
POST /auth/login 是分阶段流程。
- 只发 email+passwd → {"phase":"password","result":"invalid_phase"}
- 带 phase=password + 极验 → {"phase":"authenticated","result":"authenticated","msg":"登录成功"}
成功判定是 result=="authenticated" 而非 ret==1（run.py 已加 _login_ok()）。
且需先 GET /auth/login 建立会话。

### 2. 订阅链接是两层 base64
- 整页套壳：var originBody = "<base64 整个HTML>"
- 页内每种客户端：base64Decode('aHR0...')  （subscription.py 新增 _decode_inline_b64）
- 真正地址在 oneclickImport('clash', 'https://X.no-mad-sub.one/link/TOKEN?clash=1')
- **子域名和 token 每次刷 /user 面板都会轮换**（实测 ijqzj→2ls36→d70ty→kn63v），必须现取现用
- pickClash 要排除同源 /user/subscribe_log（那只是记录页）；优先 clash=1 > clash=3

### 3. 站点机制：免费版不给订阅节点
- 面板 7 个「免费-日本N」节点全是 node-is-offline
- 点击弹窗「不提供单独的节点链接。请按照基础教程配置客户端，并导入订阅。」
- 订阅 URL 返回 404，但 /user/subscribe_log 记录到请求（登记为 Clash + 我的IP 106.85.214.164），
  说明请求到达后端被判「无可用订阅资源」
- 商店只有付费套餐：专业版 ¥12/月起 300GB/月 5客户端
- 结论：要真实可用节点必须账号有付费套餐；代码里 probe 字段会如实报告 reachable/reason

### 4. 其他页面事实
- 页面 shell 变量名固定 originBody
- /user/node、/user/profile、/user/shop 都 200 且同样是 base64 套壳
- /api/v1/client/subscribe、/user/subscribe、/sub 均 404（不存在的路径）

## 代码变更（commit cc8a969，已推 LocalHub）
- run.py: do_login 加 phase=password + 先 GET 登录页；新增 _login_ok()；req() 支持 extra_headers；do_subscription 加 probe 探测；新增 auto 子命令
- subscription.py: 新增 _decode_inline_b64 解第二层；_detect_format 支持 clash=N；pickClash 重写（排除 subscribe_log，clash=1 优先）；push() 清理尾随分号
- README 补充上述全部实测记录

## 常用命令
cd /root/ikuuu
发码: python3 run.py send --email X@qq.com --max-try 30
注册: python3 run.py register --email X --code 123456 --name NAME --password 'PASS'
一键: python3 run.py auto --email X [--code 123456]
签到+订阅: python3 run.py checkin --email X --password 'PASS' --max-try 50
注意：密码含 ! 时 shell 会展开，用单引号包住

---

## [项目] WorkBuddy 部署到阿里云-cn 8.163.55.49（2026-10-17）

# [项目] WorkBuddy 部署到阿里云-cn 8.163.55.49（2026-10-17）

## 部署结果
- 地址：`http://8.163.55.49:8788`
  - API Base：`http://8.163.55.49:8788/v1`
  - Dashboard：`http://8.163.55.49:8788/`
  - 健康：`http://8.163.55.49:8788/health`
- API Key（沿用原有两个）：`sk-vme50toeatkfc`（cn，名 test）、`sk-sxd`（intl，名 国际版）
- 方式：**systemd 服务**（非 docker，不影响现有容器），单元 `/etc/systemd/system/workbuddy.service`
- 代码目录：`/opt/workbuddy`；日志：`/var/log/workbuddy.log`
- 启动命令：`/usr/bin/python3 /opt/workbuddy/wb_proxy.py --host 0.0.0.0 --lan --port 8788`

## 阿里云机器事实
- Debian 13 (trixie) x86_64，2 vCPU / **1883MB 内存** / **无 swap** / 40G 盘（用 5.8G）
- Python 3.13.5 预装，无需 pip（项目零依赖）
- 已跑：haproxy（占 80/443）、frps（81）、realm、easytier、docker（easytier-web / openlist 5244 / dpanel 8807 / wg-easy 51821 / memcached 11211 / hbbs / hbbr / looking-glass / 22020）
- **iptables INPUT 策略 ACCEPT，本机无 ufw/firewalld**；8788 公网直连实测 200，**阿里云安全组已放行，不用改**

## 验证证据
- `/health` → `{"ok":true,"realm":"cn","accounts":4,"accounts_ready":4,"api_key_required":true}`
- `/v1/models` 无 key → 401；带 key → 200，**16 个模型**
- 真实 chat：POST `/v1/chat/completions` model=deepseek-v4.1-flash → **200，1.1s，回复「阿里云」**
- 进程：`python3 wb_proxy.py`，RSS ~72MB，监听 `0.0.0.0:8788`
- `systemctl enable` 已开，开机自启；`Restart=always`

## 踩过的坑（重要）
- **Android 的 tar 不可靠**：`tar --exclude=... -C /sdcard/workbuddy .` 只打出 26 个文件、源码全丢（`wb_proxy.py` 不在包内）。正解：先 `cp` 到干净暂存目录（`/root/wb-aliyun/stage`）再 `tar -czf . `。
- 打包前必须确认：`包内 py 总数 = 22`、`wb_proxy.py = 1`，否则一定漏。
- `--lan` 会强制开启 API Key；日志里会打印一个新生成的 key，但**不会**真的写盘（实际 settings.json 仍是原来那 2 个），别被日志误导。
- 本次直接拷贝了 `accounts/*.json`（含 accessToken）到服务器，属于私有部署的有意行为；`/sdcard` 原件未动。
- dashboard 面板密码仍是默认 `admin`（启动日志有告警），建议改。

## 与 LocalHub 托管版的区别
- 阿里云：直接 `http://IP:8788`，systemd 守护，可自启、可 `systemctl restart`
- LocalHub 版：`https://sxd91.kdns.fr/server/workbuddy`，跑在 aws-2，入口 aws-1 转发；数据在 `/opt/localhub/data/hosted-data/workbuddy`

## 脚本位置
`/root/wb-aliyun/{pack2.sh,step1.sh,step2.sh,step3.sh}`，暂存目录 `/root/wb-aliyun/stage`

---

## 本轮两项修改已完成（2026-09-27）

# 本轮两项修改已完成（2026-09-27）

## 1) 高级参数：costTime + 画笔算法（新增）
- `costTime`：原来就有，但标签名称不清楚、且被藏在折叠区底部。现改名为「costTime（毫秒，留空 = 自动）」
  并提到折叠区第一位；**留空=自动**（题数 × 5ms 下限）；服务端加了非负数校验（非法→400）。
- **画笔算法（新增）**：单选「弧线（推荐）/ 七段码」，对齐原版 `PkStrokeMode`。
  新建 `src/strokes.js`，移植两种算法：
  - `ARC`：21/24 点密集弧线，**像素坐标** x≈150-240、y≈450-500（对齐真机 ground truth，服务端接受）；
  - `SEVEN_SEGMENT`：22 个字形（0-9 + - × x X * / = < > . : ? 空格），**归一化 0..1 × CANVAS_SIZE=1000**；
  - 布局公式与原版 `buildStrokes` 一致：`cellW=width/n`, `sx=cellW*0.72`, `sy=cellH*0.78`；
  - **回退规则**：ARC 模式下答案不是 `>`/`<` → 自动回落七段码（与原版一致）。
  - 自检实测：ARC> 21点 / ARC< 24点 / 七段码> 21点 / ARC 打 '78' 回落=SEVEN_SEGMENT ✓

## 2) 实时日志不显示 —— 根因已定位并修好
### 根因（不是 SSE 写坏了，是「事件早于订阅」）
`jobs.startJob()` 调用 `runLoop()` 后，async 函数会**同步执行到第一个 await**，
于是「任务开始」「第 1/N 轮开始」「等待 Xs」三条事件在 **HTTP 响应写出之前**就 publish 了；
前端必须等响应回来才能 `new EventSource()` → 全部漏掉。
加上默认间隔 12~20s，日志面板就空白十几秒，看起来像坏了；任务快时（如全 400）则永远看不到。

### 修法（三层保险）
1. `jobs.js` 加**事件回放缓冲**（每任务最近 300 条），`subscribe()` 时先补发历史再实时推；
2. SSE 一连上先发 **snapshot**（任务状态 + 已落库轮次）+ 已结束时直接发 `finished`；
3. 前端 **3 秒轮询兜底**（`/api/jobs/:id`），补齐任何漏掉的轮次，用 `seenRounds` 去重。

### 附带改进
- 长等待（轮间隔 / 频控退避）每 5 秒发 `tick` 事件，前端**原地更新最后一行**显示「还剩 Xs」
  （不刷屏、也不留空白）。`left===0` 不再回报。
- 事件粒度细化：新增 `match-ok`（pkIdStr + 题数）、`encode` / `encode-ok`（明文→密文字节数）。
- 为此把 `leo.pkSubmit` 拆成 `pkSubmitRaw(jar, cipher)` + 便捷版 `pkSubmit(jar, bodyObj)`，
  避免引擎里重复加密（原实现会加两次）。

### SSE 实测（真机真服务）
```
data: {"type":"snapshot",...,"config":{...,"strokeMode":"SEVEN_SEGMENT"}}
data: {"type":"status","message":"任务开始"}
data: {"type":"round","round":1,"message":"第 1/2 轮开始"}
data: {"type":"gap","message":"等待 5.0s 以规避频控"}
data: {"type":"match","message":"出题 pointId=1951"}
```
故意等 2.5s 再订阅，仍能拿到完整开头 → 回放生效 ✓

## 3) 其它
- `bin/selftest.js` 扩到 **14 项**，新增第 6 节笔画算法断言；全绿。
- `node --check` 全文件通过。
- 服务已重开：**http://127.0.0.1:8787**（8787 已释放，start.sh 自动选中），admin/admin，14 项自检全过。
- 测试期账号处于风控冷却（出题 400「已封禁，暂时无法使用」），属服务端行为，与代码无关。

---

## 2026-10-08 「冲榜模式」+ 1.3s/轮为何回不去（本地 a0e61fe）

# 2026-10-08 「冲榜模式」+ 1.3s/轮为何回不去（本地 a0e61fe）

## 用户问题：「之前 1.3s 一题的那种速度已经做不到了吗？」

### 答案：做不到了 —— 但当时是真的

仓库 `fe6f0e0`（2026-10-01）原文：

> 出题冷却实测 **约 1 秒/账号**（2026-10-01 复测：连续出题 4 次全 200，
> 旧的「62 秒硬限流」已作废）

⇒ **1.3~1.5s/轮 在 10-01 确实成立**。服务端后来把冷却收紧了。

### 实测复核（tools/recheck-cooldown.js，零间隔连发、全程不提交）

```
第 1 次  ✅ 200
第 2~6 次 ❌ 429（连发、1.2s 间隔都一样）
```

⇒ 单账号已无法回到 1.3s/轮。**UI 里那句「一轮约 1.5 秒」是过时文案，已更正。**

## 关键发现：出题冷却是「每账号独立」的

tools/probe-cooldown-scope.js（6 账号**同刻并发**出题）：

```
id=2 ✅200 ｜ id=5 ✅200 ｜ id=7 ✅200
id=6 ❌400(已封禁) ｜ id=12 ❌400(已封禁) ｜ id=15 ❌429(刚跑过测试)
```

⇒ 多账号同刻各自独立放行 ⇒ **并行 N 个账号 ≈ N 倍吞吐**。
（历史上那句「约 1 秒/账号」也印证了这个「每账号」口径。）

## 提交间隔下限（精确定标）

tools/calibrate-submit-min.js（10 题梯度，顶层 costTime 取该档值）：

| 间隔 | 单题 | 结果 |
|---|---|---|
| 300ms | 30ms/题 | ❌ 400 |
| 1037ms | 104ms/题 | ❌ 400 |
| 1754ms | 175ms/题 | ❌ 400 |
| 1902ms | 190ms/题 | ❌ 400 |
| 2473ms | 247ms/题 | ✅ 200 |
| 2602ms | 260ms/题 | ✅ 200 |

⇒ 单题下限 **200~260ms**。注意：我第一次设 200+200 落在失败区，已上调到 250+300。

## 三档模式（最终）

| 模式 | perQMs（每题声明） | basePerQMs（顶层等待） | padMs | 定位 |
|---|---|---|---|---|
| **rush**（默认） | 500 | **250** | 300 | 冲榜：间隔最小 |
| rank | 500 | 200 | 500 | 上榜 |
| speed | 200 | 200 | 300 | 刷分 |

- `perQMs` 保留 500ms 维持**上榜资格**（服务端不校验每题之和）
- `basePerQMs` 决定**等待**（服务端只校验顶层 ≤ 实际间隔）
- 冲榜模式 **429 轮询 400ms**（`exerciseSpeed.rushRetryMs`）：贴窗口抢，不提前等

## 本轮实测（三档全对）

```
rush : 等待 2.8s → ✅ 判对 10/10   本局 3.4s
rank : 等待 2.5s → ✅ 判对 10/10
speed: 等待 2.3s → ✅ 判对 10/10
```

## 提速的正路（结论）

单账号已到物理下限，要提吞吐只有：
1. **多账号并行**（冷却是每账号独立的；N 账号 ≈ N 倍）—— 量级手段
2. **每局题数拉满 200**（同一个冷却窗口收益翻倍）

## 待发现的新线索（本轮顺带看到，未深追）

`probe-cooldown-scope` 里 id=2 与 id=7 返回的 `idString` 是 **"0"**，
而 id=5 返回真实 examId。`idString=0` 疑似「被静默拒绝的出题」——
若成立，则「出题 200」不等于「拿到有效卷」。**值得后续验证。**

---

## 2026-10-08 练习拆成「刷分/上榜」两模式 + 三项提速（本地 9bee228）

# 2026-10-08 练习拆成「刷分/上榜」两模式 + 三项提速（本地 9bee228）

## 1. 关键认知纠正：真正的瓶颈是**出题冷却**，不是提交等待

tools/measure-match-cooldown.js（每秒轮询出题，4 轮实测）：

| 轮 | 出题等待 | 试次 | 提交 |
|---|---|---|---|
| 1 | 0.3s | 1 | 200（判对 10/10）|
| 2 | **57.6s** | 54 | 200（判对 10/10）|
| 3 | **58.4s** | 55 | 200（判对 10/10）|
| 4 | **58.2s** | 55 | 200（判对 10/10）|

⇒ **出题冷却约 58 秒 = 服务端硬约束**。提交只要约 2s，一局里 **56s 空闲**。
⇒ 「缩短提交等待」对吞吐几乎无用。之前几局 61~64s 里约 50s 都是这个冷却。

## 2. 两个模式（默认 rank = 主打上榜）

`config.exerciseSubmitPace.modes`：

| 模式 | 定位 | 单题间隔 | 余量 |
|---|---|---|---|
| `speed` | 刷分、速度优先 | 200ms | 300ms |
| `rank`（**默认**） | 主打上榜 | 500ms | 500ms |

- modes 由「数字」升级为对象 `{perQMs, padMs, label}`，`resolveSubmitPace()` 两种写法都兼容
- 覆盖：`submitPaceMode` / `PK_EX_SUBMIT_GAP_MODE` / `submitGapPerQMs`（毫秒优先）

## 3. 三项提速

1. **默认题数 100 → 200**（`exerciseSpeed.defaultLimit`）
   — 一个 58s 窗口一次，题数翻倍 = 经验翻倍。**最有效杠杆**。
2. **重试轮询 10s → 3s**（`exerciseSpeed.matchRetryMs`）
   — 旧值与 `MATCH_COOLDOWN_MS=1500` 不协调，每轮白等 4~5s。
3. **多账号并行** — `MAX_CONCURRENT=0` 不限制，每账号享独立冷却窗口，≈N 倍吞吐。
   用 UI 批量开任务即可（无需改码）。

## 4. 验证（真实跑，tools/verify-two-modes.js）

```
rank  （默认）: 等待 10.5s → 判对 20/20  +40 经验   本局 11.1s
speed        : 等待  4.3s → 判对 20/20  +40 经验   本局 54.2s
              （其中 49.8s 等出题冷却，印证瓶颈）

默认模式       : rank（主打上榜）
模式表         : speed=200ms  rank=500ms
重试轮询间隔   : 3000ms（旧 10000）
默认每局题数   : 200
```

## 5. 其他

- `resolveSubmitPace` / `MATCH_RETRY_INTERVAL_MS` 已导出（便于测试）
- UI 模式下拉改为「上榜(默认)/刷分」，并写明真实瓶颈

## 6. 仍待解决

1. **数字字形（七段码）** — 比大小题（ARC）已判对 20/20；数字答案口径待真机样本
2. **PK 提交 400** — 已排除结构/时间/形态；疑需先 WS 匹配拿 battleId

---

## 2026-10-31 把 /root/ikuuu 重构为纯 API 实现并推送 LocalHub（commit 8c44f97，仓库 https://sxd91.kdns.fr/sxd/ikuuu-auto）。

2026-10-31 把 /root/ikuuu 重构为纯 API 实现并推送 LocalHub（commit 8c44f97，仓库 https://sxd91.kdns.fr/sxd/ikuuu-auto）。

【核心变更：协议层零第三方依赖】
- 新增 purecrypto.py（175 行）：手写 AES-128-CBC（PKCS#7）和 RSA PKCS#1 v1.5。RSA 公钥加密就是 pow(m,e,n)；AES 加密几百字节性能无所谓。
- 新增 geetest_api.py（430 行）：极验 v4 全流程，只用 urllib + purecrypto，系统 python3 直接跑，不用 pip。
- 新增 solver_server.py（304 行）：识别层拆为独立 HTTP 服务（POST /solve 返回像素坐标），不支持的类型返 501。
- 新增 selftest_crypto.py：与 pycryptodome 对拍（AES 9 组长度字节级一致；RSA 用私钥解回验证，因为 PKCS#1 填充随机比密文无意义）。
- 删除 geetest_solver.py / word_solver.py / mailbox.py / providers.md（已合并或不再需要）。

【架构：协议与识别分离】
geetest_api.py 通过 --solver={none,manual,http} 选择识别方式；--solver-url 指向任何实现了同一接口的打码服务（含外部 YesCaptcha 等）即可直接换。

【修的关键 bug（之前一直没发现）】
1. 坐标必须万分比 round(x/300*10000)，之前传像素全部 fail。
2. 手写 AES 有两个错：GF(2^8) 的 _mul 实现有误、state 初始化用 pt[i::4] 拿的是行而非列（正确为 block[c*4:c*4+4]）。修正后与 pycryptodome 字节级一致。

【实测证据】
- selftest_crypto: RESULT ALL OK
- geetest_api --solver http: chars ['酥','油','茶'] → result=success, fail_count=0, 拿到 pass_token
- run.py send: 第 14 次 → 「验证码发送成功，请查收邮件。」
- 全新 clone 到 /tmp 用系统 python3（无任何 pip 包）跑 geetest_api.py → result:fail fail_count:1（证明签名正确）

【重要事实：risk_type 参数无效】实测传 risk_type=ai/word/nine 都无效，同一个 captchaId 连绕 4 次分别下发 word/icon/nine，说明类型由站点配置决定，无法强制指定 ai 隐形类型。所以识别环节绕不开，只能靠重试撞 word。

【题型支持】word ✅ / icon ⚠️精度不足 / nine ❌未实现。run.py 策略：换新 challenge（类型重随），501 就跳过，撞到 word 才提交。

---

## 2026-10-21 本轮踩到的坑（都是自己写控件导致的）。

2026-10-21 本轮踩到的坑（都是自己写控件导致的）。

## 1. 把 miuix FloatingNavigationBar 的 color 设成 Transparent = 底栏消失
`FloatingNavigationBar` 内部只有一处背景：`Row(... .squircleBackground(color).then(modifier))`。
若 `color = Color.Transparent` 且 `drawBackdrop` 因任何原因没画出东西（shader 不支持/采样源空/折射参数把内容推出形状外），就**没有任何兜底**，整条底栏消失，只剩暗底上看不见的图标。
正确做法（InstallerX）：不要把玻璃当背景，而是当**叠在半透明底上的一层效果**——`onDrawSurface = { drawRect(containerColor) }` 让玻璃层自己画底色。

## 2. 设置页分组标准写法（重复确认）
`LazyColumn → item{Spacer(12.dp)} → item{SmallTitle("分组")} → item{Card(Modifier.padding(horizontal=12.dp).padding(bottom=12.dp)){ 控件直排 }}`
行间**不加 HorizontalDivider**（miuix BasicComponent 自带行距与按压反馈，再画会出现双线）。
条件项用 `AnimatedVisibility(fadeIn()+expandVertically())`，不是直接 if 显隐。

## 3. 项目里不存在的控件名（别再凭空写）
- `MiuixSelectionDialog` —— 不存在，用 `MiuixDropdown(items, selectedIndex, onSelectedIndexChange)`。
- `MiuixCardSection` / `MiuixListRow` —— 不存在。
- `WindowSpinnerPreference` 是 InstallerX 的，等价物是 `MiuixDropdown`。

## 4. 安装到真机的正确路径
`pm install /sdcard/x.apk` 会报 `avc denied read ... fuse` / `Can't open file`。
必须先 `cp /sdcard/x.apk /data/local/tmp/p.apk && chmod 644`，再 `pm install -r /data/local/tmp/p.apk`。

## 5. 多模态截图只能看个大概
OCR 会把底栏文字重复识别（同一个「对话」出现两次：一个是顶栏标题、一个是 tab）。
确认 UI 结构要看 `uiautomator dump` 的 bounds 坐标，而不是 OCR 文本。

---

## 取证：`docs/doc-src/architecture/tool_stream_reconcile_plan.md`。

取证：`docs/doc-src/architecture/tool_stream_reconcile_plan.md`。

# 问题
OpenAI 兼容链路里，tool 流式中途断网重试会产生「半截旧 tool + 新 tool」污染：半截旧 tool 已进入当前显示，也进了重试上下文，重试后模型生成新的随机 tool_xxxx，两者共存。

# 方案：侧事件通道 + 快照/回滚
```kotlin
interface RevisableTextStream : Stream<String> {
    val eventChannel: Stream<TextStreamEvent>
}
data class TextStreamEvent(val eventType: TextStreamEventType, val id: String)
enum class TextStreamEventType { SAVEPOINT, ROLLBACK }
```
**只做两个事件，不传文本、不传 offset、不传字符数**。理由：字符级回退太脆弱（分块一变就错）；终止符会污染正文和历史；复杂事件会把方案做重。

# 提交边界提升
从 tool 级提升到「本轮 assistant attempt」级：
- 请求开始 → `SAVEPOINT(id)`
- 正常完成 → 不需要额外事件（本轮正常完成就不需提交事件）
- 断网/中止/重试 → `ROLLBACK(id)`：旧 attempt 已显示文本整体作废，重试时重发原始 message + history，不再依赖「continue from interruption」续写提示。

# 最大的坑：侧通道必须穿过所有 share()
链路：provider → EnhancedAIService → AIMessageManager → MessageProcessingDelegate → ChatMessage.contentStream → UI。
文本流会被 `share()` 至少两次。如果只 share 主流不 share 侧通道，事件能力在中途就断了。

# 每个消费端必须自己存快照
- EnhancedAIService：`streamBuffer.toString()`（保证重试上下文干净）
- MessageProcessingDelegate：修正本地 `contentBuilder`（否则 UI 回退了但落库还是脏的）
- UI：清流式渲染器已积累的脏状态，用快照重建（否则「逻辑上回退了，画面上没回退」）

# 两个约束
- savepoint 的 `id` **不能**复用 `tool_xxxx`（一个是已发布协议，一个是运行时搬回控制，职责不同）
- 侧事件流必须可 replay（否则后订阅者只收到 ROLLBACK 收不到 SAVEPOINT，找不到快照）

---

## 2026-10-20 完成。apiKey 从 DataStore 明文改为 Android Keystore + AES/GCM。新增 5 例，全量 172 例全绿，assembleDebug / lint（0 error 0 warnin…

2026-10-20 完成。apiKey 从 DataStore 明文改为 Android Keystore + AES/GCM。新增 5 例，全量 172 例全绿，assembleDebug / lint（0 error 0 warning）均过。

## 方案与取舍

- **不用 EncryptedSharedPreferences**：依赖 Tink，多 ROM（改过 Security Provider 的国产系统）有初始化抛 GeneralSecurityException 导致设置页打不开的案例。一个安全组件让 App 起不来是不可接受的失效模式。
- **手写 Keystore + AES/GCM/NoPadding**：密钥由系统 Keystore 保管、从不离开；data/data 被 root dump 也解不出明文；零第三方依赖。
- **失败一律降级**：encrypt 失败→存明文（保可用）；decrypt 失败→返回空串=未配置（提示重填，不崩）。
- **IV 每次随机**（`cipher.iv`），与密文一起存：`base64(iv):base64(ciphertext)`。
- **旧数据零成本升级**：`looksEncrypted()` 判别后，`migrateApiKeyIfNeeded()` 在 MainActivity 启动时幂等执行；不写迁移脚本。

## 两个真实问题（都是测试/编译发现的）

**1. `android.util.Base64` 在纯 JVM 单测里是 stub**：调用直接抛 “not mocked”，导致 `looksEncrypted` 永远 false，判据无法被测试锁住。改用 `java.util.Base64`（Android API 26+ 可用，本项目 minSdk 28）。这正是“先测再信”的价值——不测会把一个恒为 false 的判据当正确实现。

**2. 备份会带密文跨设备**：因此先查了两个 backup 规则文件，确认 `allowBackup` 与 cloud-backup / device-transfer 均已全量排除，不存在“恢复后解不开”的问题。

## 只测判别函数的原因

encrypt/decrypt 需要真实 Android Keystore，纯 JVM 跑不起来（Robolectric 也不提供）。而它们的失败已被“返回 null + 调用方降级”包住；真正会伤用户的是**旧数据判据判错**（明文被当密文送去解密 → key 凭空消失），所以 5 例全打在那里。

---

## 2026-10-19 完成。`ui:navigation` 从空骨架变为 3 个文件（200 行）+ 10 例测试，`:app` 全部改走 miuix-nav 真栈。

2026-10-19 完成。`ui:navigation` 从空骨架变为 3 个文件（200 行）+ 10 例测试，`:app` 全部改走 miuix-nav 真栈。

关键 API 事实（读 /root/repos/miuix 源码校正，勿凭印象写）：
1. `NavTransitions` 在 `top.yukonga.miuix.kmp.nav.transition`，**不在** `.core`。写错会直接 Unresolved reference。
2. `NavBackStack = SnapshotStateList<NavKey>`（typealias）；非 composable 构造用 `navBackStackOf(vararg NavKey)`。
3. `rememberNavBackStack<Route>(seed)` 泛型**必须显式写父类型**，写 `rememberNavBackStack(seed)` 会推导成具体子类，存栈时 SerializationException。
4. 路由必须 `@Serializable`；`NavDisplay` 的 `entry<T>{}` 按**精确 runtime class** 匹配，接口注册不覆盖子类。
5. 重复 contentKey 会在 reconcile 时抛 IllegalArgumentException → push 必须幂等。
6. 空栈会让 NavDisplay 抛异常 → pop 必须保底一层。

离线验证：miuix-nav 0.9.4 的全部传递依赖（navigationevent 1.1.2、lifecycle 2.11.0、kotlinx-serialization 1.11.0、collections-immutable 0.5.1、squircle）都在 gradle 缓存里，`--offline` 能编。

---

## 2026-10-20 定案。地址 https://sxd91.kdns.fr/server/sd-buddy

2026-10-20 定案。地址 https://sxd91.kdns.fr/server/sd-buddy

架构变更：从 **aws-2 的 setsid 裸进程** 改为 **aws-1 的 systemd 服务**。

理由：① 域名 sxd91.kdns.fr 入口在 aws-1，locals 直走 loopback 最快且无跨机依赖；② aws-1 的 `/server` 列表只读本机 hosting.json，跑在本机才可见；③ systemd 提供 enabled + Restart=always，重启/崩溃自动拉起。

关键文件（都在 aws-1）：
- `/etc/systemd/system/sd-buddy.service`（enabled，Restart=always，RestartSec=3）
- `/root/sdbuddy/sd-buddy.env`（600，含 SD_MASTER_KEY / ADMIN / NODE_ENV / SD_DATA_DIR / PORT=18001）
- `/opt/localhub/data/hosted/sd-buddy/src`（git clone 自裸仓库，LocalHub 会从 aws-2 分片取对象）
- `/opt/localhub/data/hosted/sd-buddy-data/state.json`（运行时数据）

⚠ 重启后必须同步 pid：`python3 /tmp/reg.py $(systemctl show sd-buddy -p MainPID --value)`，否则 hosting.json 里的旧 pid 会让 isAlive 判死 → 503。

健康检查：`curl http://127.0.0.1:18001/api/health` → version 2

---

## 2026-10-20 完成。仅改数值，不动架构。

2026-10-20 完成。仅改数值，不动架构。

链路：代码在本机 `/root/autosteps/`（sd_steps.py / sd-steps.env / deploy1-2.sh / verify2-3.sh），部署在 aws-1 3.148.103.19 同路径，systemd 驱动（sd-steps.service + sd-steps.timer，OnCalendar=*-*-* 07:00:00 Asia/Shanghai，Persistent=true），非 crontab。账号 18723143414@163.com。

改步数 43865 → 21553，两处全改（只改 env 不够）：
1. `/root/autosteps/sd-steps.env` 的 SD_STEPS（600 权限）
2. `sd_steps.py:44` 的默认值 `os.environ.get("SD_STEPS", "43865")` → `"21553"`（防 env 丢失时回落旧值）

回滚点：`/root/autosteps/backup-20261020-172115/sd-steps.env`（旧值）+ `sd_steps.py.bak-before-21553`。

实测：`sd_steps.py --force` 返回「成功：修改步数（21553），累计 64212 次」，退出码 0，站点 getCount 也返回 64212。重要坑：当天已刷过时直接跑会被日内冪等跳过（打 .last-success 日期到 /root/autosteps/.last-success），需加 --force 才能立即生效；timer 下次触发 2026-10-21 07:00 CST。

---

## 2026-10-20 完成。地址：https://sxd91.kdns.fr/server/sd-buddy

2026-10-20 完成。地址：https://sxd91.kdns.fr/server/sd-buddy

多用户架构（16 个文件，零依赖 node）：
- src/crypto.js：用户口令 scrypt(N=16384,r=8,p=1)+16 字节 salt（单向）；刷步账号口令 AES-256-GCM+随机 12 字节 nonce（可逆）；会话 token 32 字节随机、库里只存 SHA-256。
- src/auth.js：httpOnly Cookie、同 IP+账号 10 分钟 8 次失败锁定、账号不存在与密码错误返同一句话（不可枚举）。
- src/store.js：数据结构为 users[uid].{accounts,tasks,logs}，**没有全局 accounts/tasks** —— 越权在结构上不可能。原子写（tmp+fsync+rename）。
- 管理员 sxd 由首次启动 ensureAdmin 建号，口令仅从环境变量读，scrypt 哈希后落库，明文不进代码/响应/日志。

部署方式（重要）：不再用 LocalHub deploy 接口（它传不了 env），改为 `/root/sdbuddy/sd-buddy.env`（600）+ `setsid node server.js`。数据目录 `/opt/localhub/data/hosted/sd-buddy-data`。源码快照在 `/opt/localhub/data/hosted/sd-buddy/src`（git pull 后需重启+同步 hosting.json 的 pid）。

实测：管理员登录 200、真实刷步 21553 步成功、新用户看不到管理员账号且用其 id 刷步被拒。

---

## 2026-10-20 完成。WSL 构建（root@192.168.1.80，4核7G，JDK21/SDK37/Gradle9.7.0）→ BUILD SUCCESSFUL 1m40s / 44 tasks，APK 72,614,262 B…

2026-10-20 完成。WSL 构建（root@192.168.1.80，4核7G，JDK21/SDK37/Gradle9.7.0）→ BUILD SUCCESSFUL 1m40s / 44 tasks，APK 72,614,262 B。

【三个真坑，下次直接避开】
1. wrapper 哈希目录不同 → 重下发行版失败（网络断）。直接用 /root/.gradle/wrapper/dists/gradle-9.7.0-bin/611k3nwo545ltqdxvmsmz75lk/gradle-9.7.0/bin/gradle，不要用 ./gradlew。
2. WSL 的 DNS 把 repo.maven.apache.org 解析到 fake-ip，导致 Connect timeout。写 /etc/hosts 指向同段可用地址即通（其他域名如 repo1.maven.org / dl.google.com 的 fake-ip 本身能用）。缓存是空的（那 369M 只是发行版），必须联网构建。
3. 配置缓存阶段调 git 取版本号，没 .git 时 git 返回 128 → BUILD FAILED（但 APK 已产出）。在源码目录 git init + 首次提交即可。

跨机传输：tar czf /tmp/apx-sync.tgz --exclude=.git --exclude=build ...（48M，scp 4秒）。

完整下载校验必须在跨机做（本机 curl 被中间层截断到 3.8MB）；在 aws-1 上 curl 公网域名得到 72614262 B，sha256 86e4e49d… 与本地一致。

---

## 2026-10-20 排障。现象：公网 https://sxd91.kdns.fr/server/sd-buddy 返 503「服务不可用」（LocalHub 的托管不可用页），但 aws-2 本机 health 正常。

2026-10-20 排障。现象：公网 https://sxd91.kdns.fr/server/sd-buddy 返 503「服务不可用」（LocalHub 的托管不可用页），但 aws-2 本机 health 正常。

根因：上一轮为了「让 /server 列表可见」，把 aws-2 的应用记录手工登记到了 **aws-1 的 hosting.json**。而 `resolveTarget()` 的逻辑是：
  1. 先 `findApp(name)` 查**本机** state
  2. 本机找到 → `isAlive(app)` 用 `process.kill(pid,0)` + 比对 `/proc/<pid>/cwd` 判活
  3. 那个 pid 在 aws-1 上根本不存在 → 返回 `not-running` → **503**
  4. 只有本机**没记录**时，才会走 `shardEnabledForHosting()` 的 peer 查找

所以「手工登记」反而破坏了 peer 自动发现。修法：从 aws-1 hosting.json 删除该条，让 peer 发现生效。

更深一层问题：服务在 aws-2 是 `setsid node server.js` 直接起的（因为 LocalHub deploy 接口传不了 env），**没有守护、不会开机自启**。服务一挂就再也起不来 —— 这才是「显示关了」的结构性原因。

最终方案：把服务改到 **aws-1** 跑（域名入口就是 aws-1，列表可见与代理直连两得），用 systemd 守护（enabled + Restart=always）。

---

## 2026-10-20 实测踩到的三个真坑（都会直接导致部署失败）：

2026-10-20 实测踩到的三个真坑（都会直接导致部署失败）：

1. **git init --bare 的 HEAD 默认指 master**，而推送的是 main → clone 后工作区为空 → `Cannot find module .../src/server.js`。修：`git --git-dir=REPO symbolic-ref HEAD refs/heads/main`。新建裸仓库必做这一步。

2. **LocalHub 分片按 repo.id 的 sha256 前 8 位模 2 算归属**（shard.js:153 shardOf）。sd-buddy 的 r_164c75945a65798a → aws-2。在 aws-1 部署会被拒（WRONG_NODE），必须直接 POST 到持有者节点。到 aws-2 上建裸仓库后即可 push。

3. **deploy 接口可能返 504（页面渲染超时 15s）**，但部署实际已成功。别直接重试，先查 `GET /api/hosting` 看 status 与 logFile。

另：网关对长时间无数据的 SSE 连接不友好，已用心跳注释行（: ping）保活 + `X-Accel-Buffering: no` 关代理缓冲。

---

## 2026-10-20 完成并上线：https://sxd91.kdns.fr/server/sd-buddy

2026-10-20 完成并上线：https://sxd91.kdns.fr/server/sd-buddy

三个根因与修法（全部实测）：

1. **CSS 404（页面无样式）根因**：用户访问 `/server/sd-buddy`（无尾斜杠）时，浏览器把 `assets/app.css` 解析成 `/server/assets/app.css`。修法：服务端渲染 index.html 时注入 `<base href="<x-forwarded-prefix>/">`，不改前端一行代码。

2. **/server 部署页看不到应用**：`hostingUi.hostingListPage` 只读**本节点** `hosting.json`；sd-buddy 在 aws-2，而域名入口是 aws-1。修法：把 aws-2 的完整 app 记录手工登记到 aws-1 的 hosting.json，重启 localhub。

3. **deploy 接口无法传 env**：`util.readForm` 把 `env[KEY]=v` 当成字面键名，不会嵌套成对象（`envNames` 始终为 `[]`）。改用独立 env 文件 + setsid 启动，不再走 deploy 传参。

---

## https://sxd.sxdshh.kdns.fr/deepseek/ 是 DeepSeek Harness（DSH）的 Web 客户端，HTTP Basic 鉴权（realm "DSH"），后端 nginx + Cloudflare，前…

https://sxd.sxdshh.kdns.fr/deepseek/ 是 DeepSeek Harness（DSH）的 Web 客户端，HTTP Basic 鉴权（realm "DSH"），后端 nginx + Cloudflare，前端为 @deepseek-ai/dsh-client-modules 打包的 SPA；关键模块是插件 @deepseek-ai/dsh-api-gateway/client.js（内含 endpointOf 实现），主 bundle 里几乎没有 API 路径，接口定义都在各插件 client.js 中。远程协议走 wss://sxd.sxdshh.kdns.fr/deepseek/api/remote.mux，帧为精确键集校验：{type:'open', streamId:<UUID>, endpoint, payload:{args}}；streamId 必须是 UUID 字符串（randomUUID 可用，不能用自增数字）；endpoint 由 endpointOf(descriptor) 拼成 `namespace/method`，写成 session.list 这类点号形式会被网关判为 invalid Remote endpoint。

---

## 在 sxd-host（192.168.1.80）为 LocalHub 做仓库镜像时，/opt/localhub/scripts/sxd-host-tar-mirror.sh 表现为「只搬一个仓库就结束」——total 恒等于 1。根因确认：…

在 sxd-host（192.168.1.80）为 LocalHub 做仓库镜像时，/opt/localhub/scripts/sxd-host-tar-mirror.sh 表现为「只搬一个仓库就结束」——total 恒等于 1。根因确认：while read 循环里内层 `ssh host "cd ... && tar -cf -"` 会读走循环的 stdin，第一轮 read 之后直接 EOF，剩下的仓库全被跳过。修复为 `ssh -n $SSH_OPTS ...`，并在脚本里写明「-n 必须加，否则 ssh 吃掉 while read 的剩余输入」的注释。随后 scp 到 /opt/localhub/scripts/、chmod +x、`sudo systemctl restart localhub-tar-mirror`，查 /opt/localhub/data/logs/mirror-tar.log 已确认连续搬运多个仓库。附带经验：`du -sh /opt/localhub/data/repos` 在该大目录上会卡住，统计仓库数改用 `find ... -maxdepth 2 -name "*.git" -type d | wc -l`。

---

## 用户澄清意图：「让 DeepSeek harness 继续帮你干活」，即把自建 DSH 当作可调用的 AI 后端，而非只访问它的 Web 页面。已打通 DSH 远程协议：WebSocket 端点 wss://sxd.sxdshh.kdns.…

用户澄清意图：「让 DeepSeek harness 继续帮你干活」，即把自建 DSH 当作可调用的 AI 后端，而非只访问它的 Web 页面。已打通 DSH 远程协议：WebSocket 端点 wss://sxd.sxdshh.kdns.fr/deepseek/api/remote.mux，需 HTTP Basic 鉴权并带 Origin 头；请求帧为精确键集校验 {type:'open', streamId:<UUID 字符串>, endpoint:'<namespace>/<method>', payload:{args:{}}}。streamId 用 randomUUID 生成的 UUID 字符串即为正解；此前 endpoint 写 session.list 会返回 gateway/internal: invalid Remote endpoint，查 @deepseek-ai/dsh-api-gateway/client.js 的 endpointOf(descriptor) 后确认 wire 名为 namespace/method（如 session/prompt、session/create），改正后继续实测。

---

## 2026-10-21 记录。R32 取证与实施中的关键事实。

2026-10-21 记录。R32 取证与实施中的关键事实。

## 1. miuix-nav 0.9.4 转场 API 全部是公开的（已逐项核实源码）
位置 `/root/repos/miuix/miuix-nav/src/commonMain/kotlin/top/yukonga/miuix/kmp/nav/transition/`。
可直接用：`NavTransitionScope`（relativeDepth / role / change / gesture / settle / layoutSize / density）、`NavGesture`（progress / swipeEdge / touchY / initialTouchY）、`NavSettle`（phase / releaseVelocity / elapsedMillis）、`NavRole`、`NavSettlePhase`、`NavSwipeEdge`、`NavMotion`、`NavSettleSpec.Tween/Spring`、`navGraphicsTransition(opaqueDepth, dismisH

---

## 阿里云 8.163.55.49 的 `/opt/workbuddy` 共 52M：31 个 py 模块 + `dashboard.html`，其中 usage 45M、`__pycache__` 2.3M。本机等价产物：`/root/wb-…

阿里云 8.163.55.49 的 `/opt/workbuddy` 共 52M：31 个 py 模块 + `dashboard.html`，其中 usage 45M、`__pycache__` 2.3M。本机等价产物：`/root/wb-build`（全补丁构建版，31 模块）、`/root/wb-src-latest.tar.gz`（993K / 50 文件）、`/root/wb-push`（推 LocalHub 的副本）、`/root/pk-release/`（patch_brand_hard.py、patch_fp.py、patch_sched*.py、patch_finish.py、fix_emit.py 等补丁脚本全集）；回滚备份 `/root/wb-brand-bak-20261027-230041`、`/root/wb-fp-bak-*`、`/root/wb-checkin-bak-20261030-214117`、`/root/wb-deploy-bak-20261031-081500`、`/root/wb-finish-bak-20261031-163618`。

---

## 用户下达「帮我装一下」后，在 sxd-computer 上完成了 CI 构建环境落盘：

用户下达「帮我装一下」后，在 sxd-computer 上完成了 CI 构建环境落盘：
- JDK：openjdk-17-jdk-headless 与 openjdk-21-jdk-headless 都装好，默认 java 指向 21。
- Android SDK：安装在 /opt/android-sdk，commandline-tools 12.0 位于 /opt/android-sdk/cmdline-tools/latest，属主已改为 sxd:sxd。
- 组件：platform-tools、build-tools 34.0.0 / 35.0.0 / 36.0.0、platforms android-33/34/35/36 全部安装成功。
- 网络与磁盘前提：dl.google.com 与 services.gradle.org 均返回 200，根分区有 86G 可用。

该机同时承担 LocalHub 同步镜像与 CI 构建分流，构建走本地而非手机端。

---

## 2026-10-19 确认两处 LocalHub 托管坑：

2026-10-19 确认两处 LocalHub 托管坑：
1) 访问 `/server/sd-buddy`（无尾斜杠）时，HTML 里相对路径 `assets/app.css` 被浏览器解析成 `/server/assets/app.css` → 404，页面变成「只有静态页、没有 CSS」。带尾斜杠 `/server/sd-buddy/` 才正常，需要 rewrite/重定向修正。
2) 在 aws-1 手工登记 sd-buddy 记录（其 pid 在 aws-1 上不存在）后，公网持续 503「服务不可用」。根因：`resolveTarget` 先在本机 `findApp`，命中就直接用 pid 判活，不会回落到 peer 查询 → 返回 not-running。删掉 aws-1 的记录（/opt/localhub/data/hosted/hosting.json）并重启 localhub 后恢复；真实实例在 aws-2。

---

## 用户要求把 /root/ikuuu-node（Node ESM，express 5 + ws，监听 127.0.0.1:8793，支持 PORT/HOST/TOKEN 环境变量）部署到 https://sxd.sxdshh.kdns.fr/…

用户要求把 /root/ikuuu-node（Node ESM，express 5 + ws，监听 127.0.0.1:8793，支持 PORT/HOST/TOKEN 环境变量）部署到 https://sxd.sxdshh.kdns.fr/ikuu/，页面上签到、取订阅、复制三类按钮都要可用。该域名整站套 HTTP Basic 认证（realm=DSH，用户 sxd，口令不入库），前面还有 Cloudflare CDN，同域已有 /deepseek/ 反代一个 DeepSeek Web 客户端。部署要点：应用前端 fetch 用绝对路径 /api/*，子路径部署需加 BASE_PATH 环境变量并在前端拼接；nginx 需转发 Cookie / X-Auth-Token / X-Forwarded-For / Upgrade / Connection 头，proxy_read_timeout 300s。

---

## sd-buddy 进程在 aws-2 正常运行、本机 /api/health 返回 ok，但公网 https://sxd91.kdns.fr/server/sd-buddy 返回 503「服务不可用」（LocalHub 的托管不可用页）。根…

sd-buddy 进程在 aws-2 正常运行、本机 /api/health 返回 ok，但公网 https://sxd91.kdns.fr/server/sd-buddy 返回 503「服务不可用」（LocalHub 的托管不可用页）。根因：LocalHub 的 resolveTarget 先在本机 findApp，一旦命中就只用记录里的 pid 判断存活，本机查不到记录才去 peer 节点找。此前在 aws-1 手工登记了 sd-buddy 记录，那个 pid 在 aws-1 上不存在 → 判定 not-running → 503。处理：删掉 aws-1 上 data/hosted/hosting.json 里的这条记录并 systemctl restart localhub，之后公网恢复（改走 peer 查找 aws-2）。结论：托管记录只能由真正运行进程的节点登记，不要跨节点手工补。

---

## 根因一：setTimeout 跨钩子失效

# 根因一：setTimeout 跨钩子失效
真机铁证（2026-10-18 UTC 11:55）
```
11:55:05.732  payload state=completed
11:55:05.739  quiet: scheduled in 8000ms
（到 11:55:29 共 24 秒，decision 计数恒为 79，零输出）
```
排期成功但回调永不执行。官方 d.ts `quickjs-runtime.d.ts` 证实运行时是 QuickJS：setTimeout 只在当前 JS 上下文存活期间有效，钩子回调返回后上下文被回收，定时器随之失效。官方示例均为 `await new Promise(resolve => setTimeout(...))` 式同步等待。

# 解法一：钩子内同步等待
`HookReturn = Json

---

## 用户要求在 AWS 服务器上部署循环，每天 07:00 去 https://sd.52cf.cn/（「出去溜达」步数助手）为账号 18723143414@163.com 提交步数 43865，允许直接走 HTTP 发包而不是操作网页。已确认…

用户要求在 AWS 服务器上部署循环，每天 07:00 去 https://sd.52cf.cn/（「出去溜达」步数助手）为账号 18723143414@163.com 提交步数 43865，允许直接走 HTTP 发包而不是操作网页。已确认 aws-1（3.148.103.19）可访问该站点，装有 python3、curl，并且已有 crontab。链路已摸清：首页没有可用 form，前端 JS 直接调用接口，提交走 POST https://sd.52cf.cn/api.php（application/x-www-form-urlencoded，参数 user / pwd / steps，返回 JSON），另有计数接口 GET api.php?action=getCount；已用 curl 按该协议构造真实提交做验证。

---

## 刷步数实际部署形态已核实：代码与配置在 aws-1（3.148.103.19）的 `/root/autosteps`，步数目标写在 `/root/autosteps/sd-steps.env` 的 `SD_STEPS`，调度不是 cront…

刷步数实际部署形态已核实：代码与配置在 aws-1（3.148.103.19）的 `/root/autosteps`，步数目标写在 `/root/autosteps/sd-steps.env` 的 `SD_STEPS`，调度不是 crontab 而是 systemd timer（`sd-steps.timer`，每天北京时间 07:00 触发，`systemctl is-active` 为 active，`systemctl list-timers` 可查下次执行）。站点为 sd.52cf.cn「出去溜达」步数助手，提交 `POST https://sd.52cf.cn/api.php`（user/pwd/steps）。此前“用 crontab 每天 07:00 跑”的记录口径应以本条为准。

---

## DSH Web 客户端的远程通道为 wss://sxd.sxdshh.kdns.fr/deepseek/api/remote.mux，需带 Basic 认证头与 Origin。帧格式 {type:"open", streamId, endp…

DSH Web 客户端的远程通道为 wss://sxd.sxdshh.kdns.fr/deepseek/api/remote.mux，需带 Basic 认证头与 Origin。帧格式 {type:"open", streamId, endpoint, payload}，其中 streamId 必须是 UUID 字符串（不是自增数字，否则帧键集校验失败），endpoint 命名规则为 namespace/method（源码 endpointOf(descriptor) 拼装），例如 session/prompt、session/create，写成 session.list 会返回 gateway/internal: invalid Remote endpoint。

---

## workbuddy 代理主程序为 /opt/workbuddy/wb_proxy.py，部署在阿里云 8.163.55.49（root 走 sshpass 远程执行）。审计数据集中在 /opt/workbuddy/usage/：usage.…

workbuddy 代理主程序为 /opt/workbuddy/wb_proxy.py，部署在阿里云 8.163.55.49（root 走 sshpass 远程执行）。审计数据集中在 /opt/workbuddy/usage/：usage.jsonl 记录每次调用（at/status/model/key/elapsed_ms），prompt-capture.jsonl 记录出站请求（stage=outbound-final 的 messages），fp-hits.jsonl 记录特征句清除命中。改造流程：本地 /root/pk-release/ 写脚本 → scp 到远端 /tmp → ssh 执行。

---

## 用户要求「给 workbuddy 反代的代码拉下来」。已确认源码位于阿里云 8.163.55.49 的 /opt/workbuddy：总体积 52M，其中 usage 45M（使用统计，非代码）、__pycache__ 2.3M（编译缓存）…

用户要求「给 workbuddy 反代的代码拉下来」。已确认源码位于阿里云 8.163.55.49 的 /opt/workbuddy：总体积 52M，其中 usage 45M（使用统计，非代码）、__pycache__ 2.3M（编译缓存），实际代码为 31 个 Python 模块 + dashboard.html（1MB）+ accounts/catalog/pricing 配置目录。确定打包口径：排除 usage、__pycache__、*.pyc、*.bak-*；服务器端生成 /tmp/wb-src.tar.gz 后 scp 到本地 /root/workbuddy-src 并解包。

---

## sxd-host-tar-mirror.sh 搬 LocalHub 仓库镜像时表现为 total=1 就退出、看起来「只搬了一个仓库」。真实原因是循环体内层 `ssh` 会读走 `while read` 的 stdin，第一轮结束后循环输入…

sxd-host-tar-mirror.sh 搬 LocalHub 仓库镜像时表现为 total=1 就退出、看起来「只搬了一个仓库」。真实原因是循环体内层 `ssh` 会读走 `while read` 的 stdin，第一轮结束后循环输入耗尽。修复方式：内层 ssh 加 `-n` 关闭 stdin。补丁落在 /root/localhub-src/deploy/sxd-host-tar-mirror.sh，并已 scp 到 /opt/localhub/scripts/ 后重启 localhub-tar-mirror 验证，修复后能连续搬多个仓库。

---

## 访问入口：https://sxd91.kdns.fr （内网 192.168.1.80:3000 当前不通，返 000）。

访问入口：https://sxd91.kdns.fr （内网 192.168.1.80:3000 当前不通，返 000）。
- API 根是 /api，不带 v1（/api/v1/* 全 404）。已确认可用：GET /api/user、GET /api/repos、GET /api/repos/{owner}/{name}、GET /api/repos/{owner}/{name}/contents/、POST /api/repos（建仓库，返 201）、DELETE /api/repos/{owner}/{name}（返 204

---

## sd-buddy 进程在 aws-2 正常运行、本机 /api/health 返回 ok，但公网 https://sxd91.kdns.fr/server/sd-buddy 返回 503「服务不可用」（LocalHub 的托管不可用页）。根…

sd-buddy 进程在 aws-2 正常运行、本机 /api/health 返回 ok，但公网 https://sxd91.kdns.fr/server/sd-buddy 返回 503「服务不可用」（LocalHub 的托管不可用页）。根因：LocalHub 的 resolveTarget 先在本机 findApp，一旦命中就只用记录里的 pid 判断存活，本机查不到记录才去 peer 节点找。此前在 aws-1 手工登记了 sd-buddy 记录，那个 pid 在 aws-1 上不存在 → 判定 not-run@

---

## 代理关闭后复测：本地 getent 无解析，权威 DNS（223.5.5.5）对 ssh.sxdshh.kdns.fr 返回 Status 3 NXDOMAIN；www/git/hub/localhub/server/wb 及随机子域同样 …

代理关闭后复测：本地 getent 无解析，权威 DNS（223.5.5.5）对 ssh.sxdshh.kdns.fr 返回 Status 3 NXDOMAIN；www/git/hub/localhub/server/wb 及随机子域同样 NXDOMAIN，无通配符；父域 NS 正常指向 ns1~ns4.katabump.fr。此前 198.18.1.126 可连 22 是代理 fake-ip 假象。三台服务器 IP（3.148.103.19、18.118.4.253、8.163.55.49）已实测 SSH 可直连。

---

## LocalHub 服务端源码不在本机，位于远程服务器 `/opt/localhub`（如 18.118.4.253）；本机 `/root/localhub-shard-fix` 只有分片修复/上传脚本副本（shard.js、patch_sh…

LocalHub 服务端源码不在本机，位于远程服务器 `/opt/localhub`（如 18.118.4.253）；本机 `/root/localhub-shard-fix` 只有分片修复/上传脚本副本（shard.js、patch_shard.py、deploy_shard_fix.sh），不含 releases 路由实现。从本机对 18.118.4.253 用 `ssh -o BatchMode=yes` 登录被拒，SSHPASS 未设置，即无法免交互读远程源码，只能通过 API 实测探明行为。

---

## LocalHub 上的私有仓库 `sxd/workbuddy` 提交为 `11e46c8`（56 文件 / 31 py）；裸库位于 aws-1 3.148.103.19 的 `/opt/localhub/data/repos/sxd/wor…

LocalHub 上的私有仓库 `sxd/workbuddy` 提交为 `11e46c8`（56 文件 / 31 py）；裸库位于 aws-1 3.148.103.19 的 `/opt/localhub/data/repos/sxd/workbuddy.git`。aws-2 18.118.4.253 的托管目录为 `/opt/localhub/data/hosted/workbuddy/src`，仓库数据在 `/opt/localhub/data/hosted/workbuddy`。

---

## workbuddy 反向代理服务（进程 wb_proxy）部署在阿里云 8.163.55.49 的 /opt/workbuddy，systemd 单元名为 workbuddy，监听 8788；Nginx 在 80/443 做前置反代，站点配…

workbuddy 反向代理服务（进程 wb_proxy）部署在阿里云 8.163.55.49 的 /opt/workbuddy，systemd 单元名为 workbuddy，监听 8788；Nginx 在 80/443 做前置反代，站点配置 /etc/nginx/sites-available/workbuddy.conf，ACME webroot 为 /var/www/acme，证书由 /root/.acme.sh/acme.sh 签发。健康检查 /health 返回 200。

---

## workbuddy.xiaoyuyc.com 的 NS 为 dns31/dns32.hichina.com（阿里云 DNS）。A 记录原指向 8.163.55.49，2026-10-31 实测已改指 `110.42.47.142`（不再是阿…

workbuddy.xiaoyuyc.com 的 NS 为 dns31/dns32.hichina.com（阿里云 DNS）。A 记录原指向 8.163.55.49，2026-10-31 实测已改指 `110.42.47.142`（不再是阿里云机器）；该机 `/health` 返回 `accounts: 24`（intl 6 + cn 18），而 aws-2 上只有 8 个账号。早前该域名 80 端口未被阿里云 beian-block 拦截（301 nginx）。

---

## 2026-10-31 探测：8.163.55.49 ICMP 100% 丢包；22/80/443/8788 TCP 显示 OPEN 但 8788 `/health` HTTP 000（5–30s 无响应）；SSH 直连被 Connectio…

2026-10-31 探测：8.163.55.49 ICMP 100% 丢包；22/80/443/8788 TCP 显示 OPEN 但 8788 `/health` HTTP 000（5–30s 无响应）；SSH 直连被 Connection closed、经 aws-1 跳板 timed out。判定为阿里云侧 SSH/服务异常而非本机 IP 被封。当时可用的代码来源收敛为本机 `/root/wb-build` 与 DNS 新指向的 110.42.47.142。

---

## 两台 AWS 节点的 /opt/localhub 代码包含：src/shard.js（分片隔离，含 pushRefusalText 等）、src/dbsync.js（db.json 同步）、src/routes.js（gitAccess 写…

两台 AWS 节点的 /opt/localhub 代码包含：src/shard.js（分片隔离，含 pushRefusalText 等）、src/dbsync.js（db.json 同步）、src/routes.js（gitAccess 写分支 336-400）、sync/localhub-mirror-pull.sh（镜像拉取）；/opt/localhub/data/db.json 存在 tokens、sessions、actionTokens 等顶层字段。

---

## 用户要求「把这个包上传 localhub 的 release」，助手额外执行了 git tag 推送；用户明确质问「让你发 release 你发 tag 干啥」。随后实测：用不存在的 tag 名 POST 创建 release 仍返回 20…

用户要求「把这个包上传 localhub 的 release」，助手额外执行了 git tag 推送；用户明确质问「让你发 release 你发 tag 干啥」。随后实测：用不存在的 tag 名 POST 创建 release 仍返回 201，证明 LocalHub 的 release 创建不依赖 git tag，多余 tag 与临时 probe release 已清理。结论：发 release 只走 release API 并上传附件，不要顺手推 tag。

---

## 用户明确日志区域「丝滑滚动」的具体交互定义：滚动到达底部时介入自动滚动；用户上滑后关闭自动滚动；用户下滑回到底部后再次介入自动滚动；滚动过程需有平滑动画。要求同时覆盖练习页（ExercisePumpScreen）与刷 PK 页（PkGrin…

用户明确日志区域「丝滑滚动」的具体交互定义：滚动到达底部时介入自动滚动；用户上滑后关闭自动滚动；用户下滑回到底部后再次介入自动滚动；滚动过程需有平滑动画。要求同时覆盖练习页（ExercisePumpScreen）与刷 PK 页（PkGrindScreen）。排查确认：ExercisePumpScreen 当前使用 verticalScroll(rememberScrollState())，无自动滚动；两个页面均未使用 LazyColumn。

---

## 核对 aws-1(3.148.103.19) 后确认：刷步数代码在 /root/autosteps，配置（含 SD_STEPS、SD_PASSWORD）在 /root/autosteps/sd-steps.env，触发方式是 systemd…

核对 aws-1(3.148.103.19) 后确认：刷步数代码在 /root/autosteps，配置（含 SD_STEPS、SD_PASSWORD）在 /root/autosteps/sd-steps.env，触发方式是 systemd timer（sd-steps.timer 状态 active，每天北京时间 07:00，已执行过当天任务），并非此前记录的 crontab。本次已按用户要求把步数改为 21553，并备份原配置。

---

## 用户提出把仓库托管到其 LocalHub 账号并设为私有仓库。探测确认 aws-1（3.148.103.19）与 aws-2（18.118.4.253）上 `/opt/localhub/server.js` 进程均在运行，但 3000 与 …

用户提出把仓库托管到其 LocalHub 账号并设为私有仓库。探测确认 aws-1（3.148.103.19）与 aws-2（18.118.4.253）上 `/opt/localhub/server.js` 进程均在运行，但 3000 与 8080 端口均无监听；节点上存在 `/root/.localhub-token` 与 `/root/.lh-askpass.sh`。LocalHub 实际监听端口及仓库创建/私有化端点尚未确认。

---

## 2026-10-31 探测：8.163.55.49 ICMP 100% 丢包；22/80/443/8788 TCP 显示 OPEN 但 8788 `/health` HTTP 000（5–30s 无响应）；SSH 直连被 Connectio…

2026-10-31 探测：8.163.55.49 ICMP 100% 丢包；22/80/443/8788 TCP 显示 OPEN 但 8788 `/health` HTTP 000（5–30s 无响应）；SSH 直连被 Connection closed、经 aws-1 跳板 timed out。判定为阿里云侧 SSH/服务异常而非本机 IP 被封。当时可用的代码来源收敛为本机 `/root/wb-build` 与 DNS 新指

---

## 用户要求适配 Android 13 以下到 Android 9：检测到 13 以下自动降级底栏效果；并要求把所有 GitHub 地址改成 LocalHub 地址。当前 app `minSdk=33`，受 `miuix-blur` 的 man…

用户要求适配 Android 13 以下到 Android 9：检测到 13 以下自动降级底栏效果；并要求把所有 GitHub 地址改成 LocalHub 地址。当前 app `minSdk=33`，受 `miuix-blur` 的 manifest `minSdkVersion=33` 约束，要降到 minSdk 28 需确认 `tools:overrideLibrary` 是否可行。

---

## 排查发现主控所在的开发机没有 nginx 二进制、没有 /etc/nginx、也没有 nginx 进程，而 sxd.sxdshh.kdns.fr 的反代确实存在，说明 nginx 位于另一台宿主机（即 DSH 子代理运行的那台）。因此子路径…

排查发现主控所在的开发机没有 nginx 二进制、没有 /etc/nginx、也没有 nginx 进程，而 sxd.sxdshh.kdns.fr 的反代确实存在，说明 nginx 位于另一台宿主机（即 DSH 子代理运行的那台）。因此子路径部署的分工是：本机负责改应用代码与启动服务，宿主机（通过 DSH 子代理）负责加 nginx location 并 reload。

---

## 2026-10-17 排查 LocalHub fork/推送权限时确认：config.js 中已有 openPush 开关且实际生效值为 true，表示任何已登录用户都可向公开仓库推送；协作者（collaborator）功能也已在 src …

2026-10-17 排查 LocalHub fork/推送权限时确认：config.js 中已有 openPush 开关且实际生效值为 true，表示任何已登录用户都可向公开仓库推送；协作者（collaborator）功能也已在 src 中实现（grep collaborator 命中）。因此「给别人 push 权限」无需新增开关，只需确认公开仓库口径。

---

## 2026-10-30 用户要求修改 workbuddy 的签到调度时间：不再固定在同一个时刻，需加入随机时间选择，并且每个账号的签到时间都必须各不相同。本轮已在阿里云 8.163.55.49（/opt/workbuddy）定位到签到调度实现…

2026-10-30 用户要求修改 workbuddy 的签到调度时间：不再固定在同一个时刻，需加入随机时间选择，并且每个账号的签到时间都必须各不相同。本轮已在阿里云 8.163.55.49（/opt/workbuddy）定位到签到调度实现位于 wb_scheduler.py 与 wb_settings.py，正在阅读调度实现，尚未落改动。

---

## 2026-10-13 用户报告 LocalHub 公网域名 https://sxd91.kdns.fr/ 无法访问，原 IP 3.148.103.19:8080。实测 DNS 解析正常，HTTP 80 返回 200，但 443 端口全关；原…

2026-10-13 用户报告 LocalHub 公网域名 https://sxd91.kdns.fr/ 无法访问，原 IP 3.148.103.19:8080。实测 DNS 解析正常，HTTP 80 返回 200，但 443 端口全关；原 IP 8080 端口返回 200。当前已确认 443 未开放，需进一步检查服务端监听。

---

## 用户明确日志区域「丝滑滚动」的具体交互定义：滚动到达底部时介入自动滚动；用户上滑后关闭自动滚动；用户下滑回到底部后再次介入自动滚动；滚动过程需有平滑动画。要求同时覆盖练习页（ExercisePumpScreen）与刷 PK 页（PkGrin…

用户明确日志区域「丝滑滚动」的具体交互定义：滚动到达底部时介入自动滚动；用户上滑后关闭自动滚动；用户下滑回到底部后再次介入自动滚动；滚动过程需有平滑动画。要求同时覆盖练习页（ExercisePumpScreen）与刷 PK 页（PkGrindScreen）。排查确认：ExercisePumpScreen 当前使用 vert

---

## 核对 aws-1(3.148.103.19) 后确认：刷步数代码在 /root/autosteps，配置（含 SD_STEPS、SD_PASSWORD）在 /root/autosteps/sd-steps.env，触发方式是 systemd…

核对 aws-1(3.148.103.19) 后确认：刷步数代码在 /root/autosteps，配置（含 SD_STEPS、SD_PASSWORD）在 /root/autosteps/sd-steps.env，触发方式是 systemd timer（sd-steps.timer 状态 active，每天北京

---

## 把「服务在 /root/ikuuu-node」的部署任务交给 DSH 子代理后，子代理探测发现其所在机器上没有该目录，二者文件系统不共享。结论：不能让子代理直接接手只存在于主控机器上的目录/进程，只能让它承担宿主机侧的操作（如改 nginx…

把「服务在 /root/ikuuu-node」的部署任务交给 DSH 子代理后，子代理探测发现其所在机器上没有该目录，二者文件系统不共享。结论：不能让子代理直接接手只存在于主控机器上的目录/进程，只能让它承担宿主机侧的操作（如改 nginx、reload），跨机事实必须先说明清楚，否则子代理会误判环境。

---

## workbuddy.xiaoyuyc.com 的 NS 为 dns31/dns32.hichina.com（阿里云 DNS）。A 记录原指向 8.163.55.49，2026-10-31 实测已改指 `110.42.47.142`（不再是阿…

workbuddy.xiaoyuyc.com 的 NS 为 dns31/dns32.hichina.com（阿里云 DNS）。A 记录原指向 8.163.55.49，2026-10-31 实测已改指 `110.42.47.142`（不再是阿里云机器）；该机 `/health`

---

## 2026-09-30 进入对局后用户反馈画笔写完不识别。日志显示 bridge-miss 分布中 MathExercise/recognize 手写识别桥未实现；同时 match/v2 已解密成功、题目正常出来。需要实现或模拟 recogn…

2026-09-30 进入对局后用户反馈画笔写完不识别。日志显示 bridge-miss 分布中 MathExercise/recognize 手写识别桥未实现；同时 match/v2 已解密成功、题目正常出来。需要实现或模拟 recognize 桥才能让画笔识别流程走通。

---

## d.sxdshh.kdns.fr 的反代确实存在，说明 nginx 位于另一台宿主机（即 DSH 子代理运行的那台）。因此子路径部署的分工是：本机负责改应用代码与启动服务，宿主机（通过 DSH 子代理）负责加 nginx location …

d.sxdshh.kdns.fr 的反代确实存在，说明 nginx 位于另一台宿主机（即 DSH 子代理运行的那台）。因此子路径部署的分工是：本机负责改应用代码与启动服务，宿主机（通过 DSH 子代理）负责加 nginx location 并 reload。

---

## workbuddy.xiaoyuyc.com 的 NS 为 dns31/dns32.hichina.com（阿里云 DNS）。A 记录原指向 8.163.55.49，2026-10-31 实测已

workbuddy.xiaoyuyc.com 的 NS 为 dns31/dns32.hichina.com（阿里云 DNS）。A 记录原指向 8.163.55.49，2026-10-31 实测已

---

## [项目] CI 实测 7分44秒→3分50秒（未达 3分04秒，如实记录）· CI lint 抓到 API 35 真 bug（removeLast/removeFirst）

[项目] CI 实测 7分44秒→3分50秒（未达 3分04秒，如实记录）· CI lint 抓到 API 35 真 bug（removeLast/removeFirst）

---

## [教训] AndroidManifest 注释里的连续减号（--）→ manifest 解析失败 → CI 双红；本地 compileDebugKotlin 查不出来

[教训] AndroidManifest 注释里的连续减号（--）→ manifest 解析失败 → CI 双红；本地 compileDebugKotlin 查不出来

---

## 用户在第九轮明确要求「开启构建禁令直到我解除」，即在其主动解除前不执行任何构建动作；配合既有的「不本地构建 APK、走 CI」口径，改动只做代码与静态核对。

用户在第九轮明确要求「开启构建禁令直到我解除」，即在其主动解除前不执行任何构建动作；配合既有的「不本地构建 APK、走 CI」口径，改动只做代码与静态核对。

---

## 本次步数目标修改落在 aws-1 的 /root/autosteps/sd-steps.env，由 sd-steps.timer 每日 07:00 触发提交

本次步数目标修改落在 aws-1 的 /root/autosteps/sd-steps.env，由 sd-steps.timer 每日 07:00 触发提交

---

## [突破] gitsrv：本地 GitHub 模拟器建成（git 服务 + Actions + Release 全本地闭环，替代 github.com）

[突破] gitsrv：本地 GitHub 模拟器建成（git 服务 + Actions + Release 全本地闭环，替代 github.com）

---

## 2026-10-31 用户再次强调「构建禁令别忘了」，要求维持既有口径：不在本地构建 APK，构建走 CI；本地仅做代码读取、编译验证类轻量操作。

2026-10-31 用户再次强调「构建禁令别忘了」，要求维持既有口径：不在本地构建 APK，构建走 CI；本地仅做代码读取、编译验证类轻量操作。

---

## [约束] LocalHub 三个必须记住的 API 行为：/api/repos 前缀 / release 不依赖 tag / 504 通常已落盘

[约束] LocalHub 三个必须记住的 API 行为：/api/repos 前缀 / release 不依赖 tag / 504 通常已落盘

---

## [决策] App 兜底远程开关：Cloudflare Pages 静态 JSON 页控制 App 启动行为（要求隐蔽 + CI 中性伪装）

[决策] App 兜底远程开关：Cloudflare Pages 静态 JSON 页控制 App 启动行为（要求隐蔽 + CI 中性伪装）

---

## [项目] gitsrv 补全：GITHUB_* 环境注入 + tag 发版链路验证通过（artifact/release 下载均 200）

[项目] gitsrv 补全：GITHUB_* 环境注入 + tag 发版链路验证通过（artifact/release 下载均 200）

---

## [项目] AWS VPS 部署 LocalHub 拓扑 + sxd91.kdns.fr 域名 443 故障诊断（2026-10-14）

[项目] AWS VPS 部署 LocalHub 拓扑 + sxd91.kdns.fr 域名 443 故障诊断（2026-10-14）

---

## [项目] gitsrv 收尾验收：真机端到端全绿 + 队列 flock 单消费者 + 中断语义 + 文档十章（799bc20）

[项目] gitsrv 收尾验收：真机端到端全绿 + 队列 flock 单消费者 + 中断语义 + 文档十章（799bc20）

---

## [项目] LocalHub 推送通道故障：内网超时 + 公网 push 不落盘（push 报成功但 pushed_at 未变）

[项目] LocalHub 推送通道故障：内网超时 + 公网 push 不落盘（push 报成功但 pushed_at 未变）

---

## [项目] LocalHub 分片修复：API/网页读取绕过 shard + 空壳目录误判（两台 AWS，2026-10-16）

[项目] LocalHub 分片修复：API/网页读取绕过 shard + 空壳目录误判（两台 AWS，2026-10-16）

---

## 本机 /root/wb-push 副本即推送到 LocalHub 私有仓库 sxd/workbuddy（提交 11e46c8）

本机 /root/wb-push 副本即推送到 LocalHub 私有仓库 sxd/workbuddy（提交 11e46c8）

---

## 签到调度代码 wb_scheduler.py / wb_settings.py 位于该部署目录 /opt/workbuddy

签到调度代码 wb_scheduler.py / wb_settings.py 位于该部署目录 /opt/workbuddy

---

## 事实：miuix CircularProgressIndicator 的 size 参数会覆盖 Modifier.size

事实：miuix CircularProgressIndicator 的 size 参数会覆盖 Modifier.size

---

## [项目] LocalHub 仓库批量迁移：ngrok 502 后切内网直连，tag 改逐个推并补简介/release

[项目] LocalHub 仓库批量迁移：ngrok 502 后切内网直连，tag 改逐个推并补简介/release

---

## [关键] LocalHub 多机分片集群：push 报成功但远端不更新的真因 + 正确推法（2026-10-16）

[关键] LocalHub 多机分片集群：push 报成功但远端不更新的真因 + 正确推法（2026-10-16）

---

## sd-buddy 通过 LocalHub hosting 对外提供访问，本轮遇到的两个托管故障都发生在这条链路上。

sd-buddy 通过 LocalHub hosting 对外提供访问，本轮遇到的两个托管故障都发生在这条链路上。

---

## 事件：sxd-computer 装齐 Android CI 构建环境（JDK17/21 + SDK 33-36）

事件：sxd-computer 装齐 Android CI 构建环境（JDK17/21 + SDK 33-36）

---

## 刷步数部署链路：aws-1 /root/autosteps + systemd timer（非 crontab）

刷步数部署链路：aws-1 /root/autosteps + systemd timer（非 crontab）

---

## [项目] LocalHub 登录态丢失修复：sessions 被 dbsync 清空（2026-10-17）

[项目] LocalHub 登录态丢失修复：sessions 被 dbsync 清空（2026-10-17）

---

## [项目] LocalHub 分片写入修复：非属主 push 透明转发（禁止写入缓存，2026-10-16）

[项目] LocalHub 分片写入修复：非属主 push 透明转发（禁止写入缓存，2026-10-16）

---

## aws-1 上的错误托管记录使 sd-buddy 的公网地址在 aws-2 正常运行的情况下返回 503。

aws-1 上的错误托管记录使 sd-buddy 的公网地址在 aws-2 正常运行的情况下返回 503。

---

## [踩坑] sd-buddy 三个根因：CSS 404 / 部署页不可见 / deploy 无法传 env

[踩坑] sd-buddy 三个根因：CSS 404 / 部署页不可见 / deploy 无法传 env

---

## 事件：工作区清理（/root 释放 16GB，gitsrv/runs/ws 占 13.4GB 是主因）

事件：工作区清理（/root 释放 16GB，gitsrv/runs/ws 占 13.4GB 是主因）

---

## [踩坑] LocalHub 托管部署三个坑：裸仓库 HEAD / 分片归属 / deploy 504

[踩坑] LocalHub 托管部署三个坑：裸仓库 HEAD / 分片归属 / deploy 504

---

## [LocalHub] 事件：分片隔离后 git push 仍失败，用户提供双 AWS 凭证开始排查

[LocalHub] 事件：分片隔离后 git push 仍失败，用户提供双 AWS 凭证开始排查

---

## [LocalHub] 诊断 AWS 部署域名不可访问：DNS 正常、HTTP 200、443 全关

[LocalHub] 诊断 AWS 部署域名不可访问：DNS 正常、HTTP 200、443 全关

---

## 事件：阿里云 8.163.55.49 的 workbuddy 主机网络失联（2026-10-31）

事件：阿里云 8.163.55.49 的 workbuddy 主机网络失联（2026-10-31）

---

## 签名资产升级到 3.141.1 触发回归护栏过期，涉及 exercise/PK 两套 so 变体

签名资产升级到 3.141.1 触发回归护栏过期，涉及 exercise/PK 两套 so 变体

---

## [项目] WorkBuddy 部署到阿里云-cn 8.163.55.49（2026-10-17）

[项目] WorkBuddy 部署到阿里云-cn 8.163.55.49（2026-10-17）

---

## 事件：sxd-host 部署 ClawDesk 网页远控并卡在 DNS zone pending

事件：sxd-host 部署 ClawDesk 网页远控并卡在 DNS zone pending

---

## 失联发生在该部署结构所在的 8.163.55.49 主机上，服务、端口、nginx 均属同一实例

失联发生在该部署结构所在的 8.163.55.49 主机上，服务、端口、nginx 均属同一实例

---

## [项目] PK 秒结算 + 循环 PK + 多玩法并发 + 接口页UI对齐（CI #82 绿）

[项目] PK 秒结算 + 循环 PK + 多玩法并发 + 接口页UI对齐（CI #82 绿）

---

## 部署：ikuuu-node 多账号服务挂载到 sxd.sxdshh.kdns.fr/ikuu/

部署：ikuuu-node 多账号服务挂载到 sxd.sxdshh.kdns.fr/ikuu/

---

## LocalHub 事件：镜像脚本 ssh 吞 stdin 导致只搬一个仓库，加 -n 后恢复

LocalHub 事件：镜像脚本 ssh 吞 stdin 导致只搬一个仓库，加 -n 后恢复

---

## 项目事件：sd.52cf.cn 扒站还原为 sd-buddy 并接入 LocalHub 托管

项目事件：sd.52cf.cn 扒站还原为 sd-buddy 并接入 LocalHub 托管

---

## 签到调度属于 workbuddy 反向代理服务的一部分，与 wb_proxy.py 同一部署

签到调度属于 workbuddy 反向代理服务的一部分，与 wb_proxy.py 同一部署

---

## [LocalHub] 事件：确认发 release 无需预建 tag，已清理多余 tag

[LocalHub] 事件：确认发 release 无需预建 tag，已清理多余 tag

---

## [踩坑] LocalHub 托管：跨节点手工登记 hosting 记录导致公网 503

[踩坑] LocalHub 托管：跨节点手工登记 hosting 记录导致公网 503

---

## [项目] LocalHub Node 服务托管 + 服务器分流（2026-10-17）

[项目] LocalHub Node 服务托管 + 服务器分流（2026-10-17）

---

## [LocalHub] 事实：openPush=true 允许任意登录用户向公开仓库推送

[LocalHub] 事实：openPush=true 允许任意登录用户向公开仓库推送

---

## [项目] sd-buddy 迁到 aws-1 + systemd 守护（重启自动恢复）

[项目] sd-buddy 迁到 aws-1 + systemd 守护（重启自动恢复）

---

## 提交 31a2dd1 后按该命令与凭证推送到 localhub master→main

提交 31a2dd1 后按该命令与凭证推送到 localhub master→main

---

## [发布] 本地仓库上传 LocalHub（自建 Git 服务）完整要点 + 守望脚本

[发布] 本地仓库上传 LocalHub（自建 Git 服务）完整要点 + 守望脚本

---

## [项目] WorkBuddy 部署到 LocalHub 托管（2026-10-17）

[项目] WorkBuddy 部署到 LocalHub 托管（2026-10-17）

---

## 本轮 git push 失败排查的核心对象就是 LocalHub 分片隔离与同步组件

本轮 git push 失败排查的核心对象就是 LocalHub 分片隔离与同步组件

---

## 踩坑：LocalHub hosting 无尾斜杠 404 与 pid 判活 503

踩坑：LocalHub hosting 无尾斜杠 404 与 pid 判活 503

---

## [LocalHub] 事件：探测 AWS 节点 LocalHub 端口与仓库托管

[LocalHub] 事件：探测 AWS 节点 LocalHub 端口与仓库托管

---

## 实体：sxd-host（LocalHub 局域网节点 192.168.1.80）

实体：sxd-host（LocalHub 局域网节点 192.168.1.80）

---

## [踩坑] LocalHub 发 release 回 422：分片归属与资产双写

[踩坑] LocalHub 发 release 回 422：分片归属与资产双写

---

## 部署：ikuuu-auto 推送到 LocalHub（2026-10-31）

部署：ikuuu-auto 推送到 LocalHub（2026-10-31）

---

## 事实：workbuddy 反代服务部署结构（阿里云 8.163.55.49）

事实：workbuddy 反代服务部署结构（阿里云 8.163.55.49）

---

## [LocalHub] 事件：发 release 时误推 tag 被用户纠正

[LocalHub] 事件：发 release 时误推 tag 被用户纠正

---

## 项目：sd-buddy（多用户刷步托管站）已由 LocalHub 托管上线

项目：sd-buddy（多用户刷步托管站）已由 LocalHub 托管上线

---

## 该 nginx 位置事实决定了 ikuuu-node 子路径部署的分工方式

该 nginx 位置事实决定了 ikuuu-node 子路径部署的分工方式

---

## LocalHub 托管运行时：无守护进程，托管应用退出即 503/404

LocalHub 托管运行时：无守护进程，托管应用退出即 503/404

---

## [项目/vps] 事件：sxdshh.kdns.fr 诊断确认无子域记录

[项目/vps] 事件：sxdshh.kdns.fr 诊断确认无子域记录

---

## 打包拉取的源码即该部署结构中 /opt/workbuddy 目录下的代码

打包拉取的源码即该部署结构中 /opt/workbuddy 目录下的代码

---

## 本次部署尝试中因跨机导致子代理无法接手 /root/ikuuu-node

本次部署尝试中因跨机导致子代理无法接手 /root/ikuuu-node

---

## 事实：LocalHub 私有仓库 sxd/workbuddy 与托管目录

事实：LocalHub 私有仓库 sxd/workbuddy 与托管目录

---

## LocalHub 事实：release API 形态与 tag 无依赖

LocalHub 事实：release API 形态与 tag 无依赖

---

## 该域名解析到同一台阿里云主机并经其 Nginx 反代到 8788 服务

该域名解析到同一台阿里云主机并经其 Nginx 反代到 8788 服务

---

## LocalHub 事实：服务端源码在远程 /opt/localhub

LocalHub 事实：服务端源码在远程 /opt/localhub

---

## 环境：sxd.sxdshh.kdns.fr 的 nginx 不在本机

环境：sxd.sxdshh.kdns.fr 的 nginx 不在本机

---

## gitsrv 本地 CI 端到端验证与修复（2026-10-11）

gitsrv 本地 CI 端到端验证与修复（2026-10-11）

---

## openPush 配置属于该 LocalHub 部署实例的权限体系

openPush 配置属于该 LocalHub 部署实例的权限体系

---

## [任务] 事件：在 AWS 部署每天 7:00 刷步数定时任务

[任务] 事件：在 AWS 部署每天 7:00 刷步数定时任务

---

## [项目] 事件：刷步数部署链路核对并调整目标步数至 21553

[项目] 事件：刷步数部署链路核对并调整目标步数至 21553

---

## 事实：ClawDesk/Guacamole 部署的四条硬约束

事实：ClawDesk/Guacamole 部署的四条硬约束

---

## 事件：workbuddy 反代源码从阿里云打包拉回本地

事件：workbuddy 反代源码从阿里云打包拉回本地

---

## 项目事实：workbuddy 网关部署与审计日志布局

项目事实：workbuddy 网关部署与审计日志布局

---

## 实体：LocalHub 分片隔离/同步组件

实体：LocalHub 分片隔离/同步组件

---

## LocalHub 附件上传与跨节点下载坑

LocalHub 附件上传与跨节点下载坑

---
