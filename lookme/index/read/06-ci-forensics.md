# 06 · GitHub Actions 取证（:app ModelDownloaderTest 红灯）

取证时间：2026-11-16 21:28–21:40（Asia/Shanghai）
取证者：agent-7（通读+审计，只读角色；未改任何仓库文件、未跑 gradle、未连设备）
证据落地：`C:\creat-agent\work\ci-logs\`（不在仓库内）

---

## 0. 结论优先

**一句话：这次红灯不是代码回归，是一个 flaky 用例（同源码 3 次 CI 里 2 绿 1 红）；而它暴露出的真实缺陷是下载器「结果选择」的非确定性——并发下一个文件失败会被报成「用户取消」。**

| 问题 | 结论 |
|---|---|
| 1e00132 的 run 结果 | **全绿**。run `43875229354` → conclusion=success，verify（job `133326418429`）与 build（job `133326419130`）都 success；9 个 ModelDownloaderTest 全过；:app:lintDebug BUILD SUCCESSFUL in 4m32s |
| 是不是新加的转储步骤导致的红 | **不是**。1e00132 没有新增 step（step 名称/编号与 67f2e8a 完全一致），转储是**内联**进既有 step 的失败分支（set +e 之下），不改变 exit 语义 |
| build job 是否仍绿 | **是**。两轮（67f2e8a / 1e00132）都是 Build → Locate APK → Upload APK 全 success |
| 67f2e8a 那次 :app 失败在哪 | `ModelDownloaderTest.kt:137` 的第一条断言 `assertTrue(result is DownloadResult.Failed)`，java.lang.AssertionError（**无 message**），同轮其余 8 条用例全过 |
| 根因 | 已确认：同源码不同 run 结果不同 ⇒ flaky。**未确认**：那一次 result 到底是什么（日志无法区分，见 G3）。已确认存在的实现缺陷：download() 用 `firstOrNull { it != null }` 挑终止结果，顺序敏感，并发下 Cancelled 可掩蔽 Failed（复现用例见 §6 Patch C；该用例是**静态推导**，本 agent 不能运行，预期在旧实现上红） |
| 9 个 core:plugin 失败 | 与上轮完全一致（PluginRepositoryTest 6 + PluginLoadOrderTest 2 + ExtensionSnapshotTest 1），走 continue-on-error: true 的步骤，**不阻断** CI |

> 诚实性限定：本文件里凡是「预期 / 会 / 复现」这类断言，只要涉及**运行结果**，都是静态推导（本地只能读、不能跑 gradle，:app 在 Windows 上也无法配置）。已实际取证的只有：CI 的 run/job/日志原文、三处源码快照、workflow 全文。

---

## 1. 范围与文件清单（数量核对）

本任务是**取证**，不是模块通读，所以「范围」= CI 侧证据 + 故障路径涉及的最小源码集。

### 1.1 CI 侧证据（全部读过）

| 文件 | 内容 | 读法 |
|---|---|---|
| work/ci-logs/runs.json | /actions/runs?per_page=10 | 逐条读 |
| work/ci-logs/run-43875229354.json | 新 run 详情 | 逐条读 |
| work/ci-logs/jobs-43875229354.json | 新 run 的 2 个 job + 步骤状态 | 逐步骤读 |
| work/ci-logs/jobs-43703912198.json | 旧 run 的 2 个 job + 步骤状态 | 逐步骤读 |
| work/ci-logs/jobs-43703549635.json | c1016bc run 的 job 状态 | 逐条读 |
| work/ci-logs/jobs-42985598909.json | 43f666c run 的 job 状态 | 逐条读 |
| work/ci-logs/job-132780767400.zip（实为纯文本，856 KB / 10 822 行） | 67f2e8a 的 verify 日志 | 全量结构化扫描 + 失败区段逐行 |
| work/ci-logs/job-133326418429.log（868 KB / 11 176 行） | 1e00132 的 verify 日志 | 全量结构化扫描 + 关键区段逐行 |
| work/ci-logs/job-130405892133.log（896 KB / 11 415 行） | 43f666c 的 verify 日志（全绿对照） | 全量结构化扫描 + 相关区段逐行 |
| work/ci-logs/job-132779671159.log（110 KB / 1 224 行） | c1016bc 的 verify 日志（编译期就红了） | 全量读 |
| work/ci-logs/build-67f2e8a.yml / build-1e00132.yml | 两个 commit 的 workflow 全文（19 746 / 20 741 字节） | 全文行级 diff + 286–452 区段逐行 |
| work/ci-logs/*-{67f2e8a,1e00132}.kt、ModelDownloader-43f666c.kt | raw 拉的三处源码快照 | 用于核对「本地==CI」 |

### 1.2 源码（逐行读完，非抽样）

| 文件 | 行数 |
|---|---|
| app/src/test/java/dev/praxis/data/imagegen/ModelDownloaderTest.kt | 201 |
| app/src/main/java/dev/praxis/data/imagegen/ModelDownloader.kt | 318 |
| core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageDownloadPlan.kt | 197 |
| core/imagegen/src/main/kotlin/dev/praxis/core/imagegen/ImageModelCatalog.kt | 224 |
| app/src/main/java/dev/praxis/data/imagegen/ImageModelDownloadManager.kt | 150 |
| app/src/test/java/dev/praxis/data/imagegen/ImageModelDownloadManagerTest.kt | 118 |

**数量核对（诚实记账）**

- app/src/main/java/dev/praxis/data/imagegen/：目录内 14 个 .kt，我读了 **2** 个（ModelDownloader.kt、ImageModelDownloadManager.kt）——只读与本次故障路径相关的；
- core/imagegen/src/main/kotlin/.../imagegen/：24 个 .kt，读了 **3** 个（ImageDownloadPlan.kt、ImageModelCatalog.kt；ImageDownloadSettings.kt **仅按 concurrency 关键字检索，未逐行通读**）；
- app/src/test/java/dev/praxis/data/imagegen/：2 个 .kt，读了 **2** 个（全覆盖）；
- 未读：app 侧 EngineLocator/EngineProcess/ImageGenClient/GalleryWriter 等 12 个、core 侧 21 个——与本次红灯无关，留给其他 agent 的通读轮次，本文件不对它们下任何结论；
- 版本一致性已核对：ModelDownloaderTest.kt / ModelDownloader.kt / ImageDownloadPlan.kt / ImageModelCatalog.kt **在 43f666c / 67f2e8a / 1e00132 三个 commit 与本地工作树逐字节相同**。

---

## 2. 模块职责（一段话）

这一小块是「本地生图的模型下载落地层」：core:imagegen 里的 ImageDownloadPlanner 把一条 ImageModel 目录项算成纯数据的 ImageDownloadPlan（要下哪些文件、落到 models/<id>/、成功写什么标记、失败清什么残留），SpaceCheck 做 30% 余量的空间预检，ImageModelCatalog.isInstalled 判定「算不算装好」（多文件包看标记文件 + 每个文件非空，单 zip 包看目录非空）；app 里的 ModelDownloader 真正执行（阻塞式）：下到 .part → renameTo 去掉后缀 → 单 zip 解压剥一层同名目录 / 多文件写 ZIMAGE 这类标记 → 返回 Completed / InsufficientSpace / Failed / Cancelled 四档；ImageModelDownloadManager 再包成「单飞 + StateFlow 状态 + 标志位取消」，供前台服务与设置页消费。

---

## 3. 逐文件一表

| 路径 | 职责 | 对外关键 API/类型 | 疑点 |
|---|---|---|---|
| app/src/test/java/dev/praxis/data/imagegen/ModelDownloaderTest.kt | 下载器落地行为单测（9 条）：解压、剥目录、写标记、失败清理、zip-slip、进度、取消、体积未知 | 无（测试） | **:137 断言无 message**，红了只能靠猜；**没有一条 concurrency>1 用例** |
| app/src/main/java/dev/praxis/data/imagegen/ModelDownloader.kt | 真下载：.part/rename、并发门、abort 标志、zip 解压剥目录、残留清理、四档结果 | DownloadResult{Completed,InsufficientSpace,Failed,Cancelled}、download(plan, baseUrl, shouldStop, concurrency, onProgress)、DEFAULT_BASE_URL | **:154 firstOrNull 顺序敏感**（G1）；abort 中止与用户取消共用 Cancelled（G1）；:210 把两种「停」压平 |
| core/imagegen/.../ImageDownloadPlan.kt | 计划、体积解析、空间预检、清理清单（纯函数） | ModelSize.parse、DownloadTarget、ImageDownloadPlan(partPaths/zipTempFile/extractedZipFile/extractTempDir)、ImageDownloadPlanner.plan、SpaceCheck.decide(SAFETY_FACTOR=1.3)、DownloadCleanup.staleFiles/staleDirs | 无实质疑点；plan() 保持 packageFiles 原序，正是 G1 的放大器 |
| core/imagegen/.../ImageModelCatalog.kt | 模型目录数据、芯片档位映射、**安装态判据** | ImageModel(isMultiFile/localNameOf)、ImageModelCatalog.builtin/markerFileName/isInstalled、ChipsetTier.suffixFor/downloadSuffix | isInstalled 对单 zip 用「目录非空」⇒ 解压残留会误判，靠 DownloadCleanup 兜（G5） |
| app/src/main/java/dev/praxis/data/imagegen/ImageModelDownloadManager.kt | 编排：单飞、状态流转、标志位取消、下载源/并发/闸门注入 | start(model):Boolean、cancel()、acknowledge()、isBusy()、state:StateFlow<ImageDownloadState> | :120 Cancelled → ImageDownloadState.Cancelled（界面不弹错）⇒ G1 的被掩蔽失败在 UI 上表现为「什么都没发生」 |
| app/src/test/java/dev/praxis/data/imagegen/ImageModelDownloadManagerTest.kt | 编排层 5 条：单飞、Completed、Failed、Cancelled、acknowledge | 无（测试） | 5 条用例的 model **全是单 zip 单目标** ⇒ 多文件/并发失败路径零覆盖（G6） |
| .github/workflows/build.yml @1e00132 | CI：verify（库测试 / JVM 模块 / :feature:chat 逐类 / :app 逐类 / lint）+ build（APK） | — | 转储只在失败分支且抓不到无 message 的断言（G2）；:app 白名单硬编码（G7） |

---

## 4. 关键机制深挖

### 4.1 CI 拓扑与「为什么 9 个 core:plugin 失败不阻断」

- verify 的测试步骤：Unit tests (library modules)（-x :app、-x :feature:chat、-x :core:plugin:test，build.yml:244-261）→ Unit tests (JVM modules, observation)（--continue + 指名 :core:plugin:test，**continue-on-error: true**，:286-310）→ Summarize JVM test results（if: always()，:323）→ :feature:chat 逐类 17 次（:367-397）→ :app 逐类 10 次（:401-437）。
- 9 个 core:plugin 失败发生在唯一带 continue-on-error 的步骤里，所以汇总行打出 test suites: 163  tests: 1645  failed: 9（job-133326418429:4221）而 verify 仍绿。两轮数字完全一致（67f2e8a 也是 163/1645/9）⇒ 历史债零变化。

### 4.2 新注入的「JUnit XML 断言转储」（1e00132 唯一改动）

diff 只有两处**新增内联块**，无新增 step：

```yaml
# build.yml:420-431（:app 逐类循环的失败分支）
if [ $rc -ne 0 ]; then
  echo "::error::$CT 失败或超时（exit=$rc）"
  failed=$((failed + 1))
  for xml in app/build/test-results/testDebugUnitTest/TEST-*"$CT"*.xml; do
    [ -f "$xml" ] || continue
    echo "----- $xml -----"
    grep -o 'message="[^"]*"' "$xml" | head -5
    sed -n '/<failure/,/<\/failure>/p' "$xml" | head -40
  done
fi
```

同款另加一份给 :feature:chat（:385-391）。位置在 set +e（:403）之下、failed 计数之后，**不改变 exit 语义** ⇒ 不可能把 CI 变红。

**它本次没有机会执行**（新 run 无失败）；而且**即使执行也答不了「result 是什么」**：assertTrue(boolean) 无 message 时 XML 里 failure 的 message 属性为空，grep 'message="[^"]*"' 抓不到内容，sed 打出来的只是已经在 Gradle 日志里的栈。见 G2。

### 4.3 下载器主流程（数据流）

download()（ModelDownloader.kt:110-181）：

1. **建目录 + 清残留 + 预检**（:112-122）：先 cleanStale 再 SpaceCheck.decide(requiredBytes, usableSpace)——顺序是刻意的（否则上次的 .part 会让预检自相矛盾地报「不够」）；InsufficientSpace 在**一个字节都没写**时返回。
2. **并发下载**（:129-156）：Semaphore(concurrency) 当门，targets.map { async(Dispatchers.IO) { gate.acquire(); downloadOne(...) } }.awaitAll()。
3. **终止结果选择**（:154-160）：awaitAll().firstOrNull { it != null } —— **顺序敏感，G1 的核心**。
4. **收尾**（:163-173）：单 zip → extractZip + 删压缩包（删不掉不算失败，名字带出去）；多文件 → 写标记文件。
5. **异常兜底**（:174-180）：IOException / SecurityException（zip-slip）→ cleanStale → Failed。

downloadOne()（:188-235）：<localName>.part → 边读边写边 onProgress(ParallelProgress.aggregate(done, totalBytes))（进度是**所有文件的和**，各报各的会让界面来回跳）→ 成功后 renameTo 去掉 .part；读循环每块检查 shouldStop() || abort.get() → Cancelled；IOException → abort.set(true) + Failed（一个文件失败就中止其余，避免白烧流量）。

### 4.4 G1 的机理（为什么「失败会被报成取消」）

targets 顺序来自 packageFiles 原序（ImageDownloadPlan.kt:97-103）。设 targets = [unet.bin, clip.bin]、concurrency = 2（**生产默认值**，ImageDownloadSettings.kt:67 DEFAULT_CONCURRENCY = 2，AppModule.kt:508-509 注入）：

1. 两个 async 同时起跑；clip.bin 的 openStream 抛 IOException → abort = true → 返回 Failed；
2. unet.bin 的读循环下一块看到 abort.get() → 返回 **Cancelled**；
3. awaitAll() = [Cancelled, Failed]，firstOrNull { it != null } 取**下标 0** ⇒ 整体报 **Cancelled**；
4. ImageModelDownloadManager:120 把 Cancelled 映成 ImageDownloadState.Cancelled（不是 Failed）⇒ 界面不弹错误、不进失败统计，用户看到「下载消失了」，实际是 404/断网。

把 packageFiles 顺序反过来（[clip.bin, unet.bin]）就变成报 Failed。**同一个故障、同一份代码，结果取决于清单里谁写在前** —— 这就是非确定性来源，也是「单测断言无 message 时红了不知道为什么」的那类 bug。

### 4.5 安装态判据（为什么「残留」这件事很贵）

isInstalled（ImageModelCatalog.kt:206-222）：多文件包要求「标记文件存在 + 每个 packageFiles 本地文件非空」；单 zip 包只看「目录非空」。所以一旦失败没清干净：多文件包会缺标记（还好），**单 zip 包会被误判成已装好** —— 用户点进去，引擎一启动就崩。这就是 DownloadCleanup.staleFiles 特意把 extractedZipFile 也列进去的原因（ImageDownloadPlan.kt:187-192）。

### 4.6 空间预检的 30% 余量

SpaceCheck.decide（:160-168）：needed = requiredBytes * 1.3；requiredBytes 为 null/<=0 → Unknown（**不并进 Enough**，避免「下到一半才发现装不下」）。这解释了「进度回调…总量未知」与「体积未知时结果里明确标注」两条用例的存在。

---

## 5. 缺口/疑点清单

### G1（最高危，已确认存在）终止结果选择顺序敏感，失败会被 Cancelled 掩蔽

- 证据：ModelDownloader.kt:154 .awaitAll().firstOrNull { it != null }；ModelDownloader.kt:210 if (shouldStop() || abort.get()) return DownloadResult.Cancelled；ModelDownloader.kt:224 abort.set(true)；ImageModelDownloadManager.kt:120 Cancelled → ImageDownloadState.Cancelled；生产并发默认 2（ImageDownloadSettings.kt:67）。
- 影响：**多文件包（DiT）在默认并发下，一个文件失败可能被报成「用户取消」**：界面不弹错、不进失败统计、Failed.Reason.Transport 的 detail（「网络断了」/「HTTP 404」）被丢掉。用户唯一能做的是再点一次，且会重复失败。
- 建议动作：按 §6 Patch A 让「失败优先于取消」（仅在 shouldStop() 为真时让取消优先），并按 Patch C 补一条 concurrency=2 的回归用例。

### G2（中危，已确认）转储步骤对「无 message 的断言」抓不到任何东西

- 证据：67f2e8a 日志 job-132780767400:10165-10170 只有 java.lang.AssertionError 三行栈；对应源码 ModelDownloaderTest.kt:137 是 assertTrue(result is DownloadResult.Failed)（无 message）。此路径产出 message 为空的 failure 节点，grep -o 'message="[^"]*"' 匹配不到有效内容。
- 影响：**1e00132 这个提交的收益接近 0** —— 它只对 assertEquals(期望, 实际) 这类天生带 message 的断言有用；而这次红的恰恰是最需要信息的类型。下一次同类红灯仍然「靠猜」。
- 建议动作：见 §6 Patch B（把断言 message 补上，内插 $result）；转储脚本保留作兜底，但别指望它。

### G3（中危，未确认）67f2e8a 那次红灯的 result 到底是什么

- 已排除（有证据）：**Completed**（同轮「多文件包逐个下载后写标记文件」期望 Completed 且通过，成功路径正常）；**InsufficientSpace**（同轮同 requiredBytes="1MB" 的用例通过，磁盘充足）。
- 剩余唯一与「只有第 137 行失败」自洽的分支是 Cancelled，但那需要并发；而用例里 download(plan) { } 走的是 concurrency = 1（ModelDownloader.kt:108），Semaphore(1) 应严格串行 ⇒ **逻辑上不该发生**。
- 诚实结论：**根因未确认**。候选：(a) 偶发环境因素使某分支提前返回（日志无法区分）；(b) 读到的源码与当次编译产物之间存在未察觉的差异（compileDebugUnitTestKotlin UP-TO-DATE 只说明输入未变，不能证明产物内容）。
- 建议动作：先落 Patch B（让下次红了直接看到值）再观察；**不要**为这条 flaky 改断言期望值，也不要重跑刷绿了事。

### G4（低危，已确认）三次运行的历史对照证明是 flaky

| commit | 测试源码 | 实现源码 | ModelDownloaderTest |
|---|---|---|---|
| 43f666c（run 42985598909） | 同 | 同 | 9/9 通过（job-130405892133:10155-10174） |
| 67f2e8a（run 43703912198） | 同 | 同 | **1 失败**（job-132780767400:10165） |
| 1e00132（run 43875229354） | 同 | 同 | 9/9 通过（job-133326418429:9929-9948） |

（c1016bc 那次 verify 在 :feature:gateway:compileDebugKotlin 就红了，:app 根本没跑到，不能作对照。）
影响：把这条当「代码回归」会白花时间；当 flaky 处理又会让 G1 继续潜伏。
建议动作：按 G1 修实现 + 按 G2 补信息，不重跑刷绿。

### G5（低危，隐患）单 zip 包的 isInstalled 只看「目录非空」

- 证据：ImageModelCatalog.kt:219-221。
- 影响：任何在模型目录里留下文件的中断（未来重构动了 DownloadCleanup、或用户手动放东西）都会被判成「已装好」。
- 建议动作：保留现状（与上游同口径），但给 DownloadCleanup 补一条「不许删掉 extractedZipFile」的守护测试。

### G6（中危，已确认）编排层零覆盖多文件/并发路径

- 证据：ImageModelDownloadManagerTest.kt:29-35 的 model() 是单 zip 单目标；5 条用例全走单目标。
- 影响：G1 这条路径**在产品默认配置（并发 2）下必然出现，却没有任何测试会红**。
- 建议动作：见 Patch C。

### G7（低危，已记录）:app 逐类白名单硬编码

- 证据：build.yml:405-411（注释自陈 2026-11-08 踩过「新类没登记 ⇒ CI 绿但一条没跑」）。
- 影响：新增测试类不会自动进 CI；本期新增用例若放在新文件里会被静默跳过。Patch B/C 都放进**已登记的** ModelDownloaderTest，正是为了绕开这个坑。
- 建议动作：中期改成「比对 src/test 里的类名清单，缺一个就 fail」。

### G8（低危）grep 在 set -e 下会成为新的红源

- 证据：转储块里的 grep -o … | head -5；当前在 set +e（:403）之后，安全。
- 影响：将来若有人把循环改成 set -e，grep 匹配不到就 exit 1，转储步骤会反过来把 CI 弄红。
- 建议动作：转储块里给 grep 补 || true。

---

## 6. 补丁（可直接应用；**未落盘**，本 agent 不改仓库文件）

### Patch A — app/src/main/java/dev/praxis/data/imagegen/ModelDownloader.kt（修 G1）

```diff
--- a/app/src/main/java/dev/praxis/data/imagegen/ModelDownloader.kt
+++ b/app/src/main/java/dev/praxis/data/imagegen/ModelDownloader.kt
@@ -152,7 +152,19 @@ class ModelDownloader(
                             } finally {
                                 gate.release()
                             }
                         }
-                    }.awaitAll().firstOrNull { it != null }
+                    }.awaitAll().filterNotNull()
+                        // 终止结果必须确定，不能「谁排前面报谁」。
+                        //
+                        // 一个文件失败会把 abort 置真（downloadOne:224），其余文件随即
+                        // 返回 Cancelled（:210）—— 于是 firstOrNull { it != null } 报的是
+                        // packageFiles 里靠前的那个，而不是真正的失败。并发（生产默认 2，
+                        // 见 ImageDownloadSettings.DEFAULT_CONCURRENCY）下这会把
+                        //「网络断了」报成「用户取消」，界面不弹错误。
+                        .let { all ->
+                            if (shouldStop()) {
+                                // 用户真的按了取消：意图优先。
+                                all.firstOrNull { it is DownloadResult.Cancelled } ?: all.firstOrNull()
+                            } else {
+                                // 没按取消 ⇒ Cancelled 只可能是兄弟失败带出来的中止。
+                                all.firstOrNull { it is DownloadResult.Failed } ?: all.firstOrNull()
+                            }
+                        }
                 }
             }
             if (terminal != null) {
@@ -208,6 +220,8 @@ class ModelDownloader(
                         // 两个都要看：用户取消（shouldStop）与别的文件已经失败（abort）。
+                        // 两者都先返回 Cancelled，优先级由 download() 统一裁决（见上）。
+                        // 不这样分开，失败就会被取消掩蔽。
                         if (shouldStop() || abort.get()) return DownloadResult.Cancelled
```

（第二个 hunk 只加注释、不改语义；真正的修复在第一个 hunk，若求最小改动可只落第一 hunk。）

### Patch B — app/src/test/java/dev/praxis/data/imagegen/ModelDownloaderTest.kt:137-139（修 G2）

```diff
--- a/app/src/test/java/dev/praxis/data/imagegen/ModelDownloaderTest.kt
+++ b/app/src/test/java/dev/praxis/data/imagegen/ModelDownloaderTest.kt
@@ -135,9 +135,9 @@ class ModelDownloaderTest {
         val result = downloader.download(plan) { }
 
-        assertTrue(result is DownloadResult.Failed)
-        assertTrue("残留必须清掉，否则下次预检误判", plan.partPaths.none { it.exists() })
-        assertFalse(ImageModelCatalog.isInstalled(root, multiModel()))
+        assertTrue("期望 Failed，实际 $result", result is DownloadResult.Failed)
+        assertTrue("残留必须清掉，否则下次预检误判（实际 $result）", plan.partPaths.none { it.exists() })
+        assertFalse("装了一半不该算装好（实际 $result）", ImageModelCatalog.isInstalled(root, multiModel()))
     }
```

### Patch C — 同文件末尾新增并发回归用例（修 G1 的可验证性 + G6）

预期（**静态推导，未运行**）：在未打 Patch A 的实现上此用例红，`result` 报 `Cancelled`；打上 Patch A 后绿。

```diff
--- a/app/src/test/java/dev/praxis/data/imagegen/ModelDownloaderTest.kt
+++ b/app/src/test/java/dev/praxis/data/imagegen/ModelDownloaderTest.kt
@@ -196,4 +196,36 @@ class ModelDownloaderTest {
         assertTrue(result is DownloadResult.Completed)
         assertTrue("必须标出「空间无法预估」", (result as DownloadResult.Completed).spaceWasUnknown)
     }
+
+    /**
+     * 并发下一个文件失败，不能被报成「用户取消」。
+     *
+     * 守的是 packageFiles 顺序带来的非确定性：失败的文件把 abort 置真后，另一个
+     * 文件会返回 Cancelled；若它排在清单前面，旧实现（取第一个非 null）就会把
+     * 整体结果报成 Cancelled —— 界面上等于「什么都没发生」。
+     */
+    @Test
+    fun 并发下一个文件失败不能被报成取消() {
+        val root = tmp.newFolder("models")
+        val plan = ImageDownloadPlanner.plan(root, multiModel())
+        // unet.bin 慢一点，保证 clip.bin 抛错时它还在读 —— 生产默认并发 2 的时序。
+        val slow = object : java.io.InputStream() {
+            private var remaining = 64 * 1024
+            private val chunk = ByteArray(16 * 1024)
+            override fun read(): Int = throw UnsupportedOperationException("只走批量读")
+            override fun read(b: ByteArray, off: Int, len: Int): Int {
+                if (remaining <= 0) return -1
+                Thread.sleep(20)
+                val n = minOf(len, chunk.size, remaining)
+                remaining -= n
+                return n
+            }
+        }
+        val downloader = ModelDownloader() { url ->
+            if (url.endsWith("clip.bin")) throw IOException("网络断了") else slow
+        }
+
+        val result = downloader.download(plan, concurrency = 2) { }
+
+        assertTrue("失败不能被取消掩蔽（实际 $result）", result is DownloadResult.Failed)
+        assertTrue("残留必须清掉", plan.partPaths.none { it.exists() })
+        assertFalse(ImageModelCatalog.isInstalled(root, multiModel()))
+    }
 }
```

---

## 7. 验证方法（不需要本地配置 Android SDK）

1. **交叉验证 flaky（已完成，无需再跑）**：同一份源码在 43f666c/1e00132 绿、67f2e8a 红，见 §5 G4 的日志行号。
2. **验证 Patch A/C（推 CI 后看）**：ModelDownloaderTest 会在 :app 逐类步骤里自动跑到（该类已在白名单，build.yml:411）。预期：新用例在**当前实现上红**（message 为「失败不能被取消掩蔽（实际 Cancelled）」），打上 Patch A 后**绿**。
   - 想先确认它确实红：只推测试（Patch B+C），日志里应出现 ----- app/build/test-results/.../TEST-...ModelDownloaderTest.xml ----- 与 message="失败不能被取消掩蔽（实际 Cancelled）" —— 这同时验证了 1e00132 的转储在**有 message** 时确实好用。
3. **验证「不会更红」**：事后 :app 逐类步骤的 10 次 BUILD SUCCESSFUL 不变；汇总行仍应是 163 suites / 1645 tests / 9 failed（core:plugin 历史债，未动）；加 1 条用例后应为 1646 tests / 9 failed。
4. **确认 build job**：build job 与 :app 单测无依赖；Patch A/B/C 都不改 API 签名（只改 download() 内部的结果选择），ImageModelDownloadManager 的 when 分支不需要改。

---

## 8. 给 lead 的下一步（按性价比排序）

1. **先落 Patch B**（3 行断言 message）—— 零风险，让下一次同类红灯可诊断；这是「转储步骤」补不上的那一块。
2. **再落 Patch A + C** —— G1 是**生产 bug**（默认并发 2 下多文件包失败被报成取消），不只是测试问题。
3. **不要**为 67f2e8a 那次红灯改期望值或重跑刷绿（那是 G3 未确认的 flaky）。
4. G5/G7/G8 记进技术债，等 core:plugin 那 9 条处理完一起收拾。
