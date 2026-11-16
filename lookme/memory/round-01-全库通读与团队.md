# round-01 全库通读 + 团队并发 + 建立 lookme

日期：2026-11-16

## 这一轮做了什么

1. **建立 lookme 记忆库**：`GOAL.md` / `STATE.md` / `README-工作循环.md` / `memory/` / `delivery/` / `index/`。
2. **全库清点**：1251 个文件 / 207.0 MB。其中纯文本 15.3 MB ≈ 460 万 token（`docs/memories` 占 7.8 MB）。
   生成 `index/INDEX-files.md`（961 个文本文件，声明级摘要，168 KB）。
3. **判定「不可能一次读完 15.3 MB 进上下文」**，改用两条腿：
   - 机器索引覆盖 100% 文件（每个文件都被打开并抽取声明）；
   - 智能体分模块**逐文件精读**，产出 6 份报告（合计 ~360 KB）。
4. **派出 6 路并发智能体**（只读 + 只写自己那一份 md，写域互不重叠）：
   WS-R1 gateway / WS-R2 imagegen / WS-R3 ai+data+plugin / WS-R4 app / WS-R5 feature+ui+core其余 / WS-R6 CI 取证。
5. **修掉第一条真 bug**（见 round-02 的代码提交）：下载器「失败/取消」竞态。

## 关键认知（这一轮真正学到的东西）

- **这个仓库的问题不在领域层，在接线层。** core 六个模块的纯逻辑测试密度极高（396 + 1600 余例），
  但大量「有类型、有单测、有界面、零生产调用」的死接线。这是本轮所有报告的共同结论。
- **第一次红灯是 flaky，不是回归。** `43f666c` 绿 → `67f2e8a` 红（1 条）→ `1e00132` 绿（9/9 全过），
  而 `ModelDownloaderTest.kt` 与 `ModelDownloader.kt` 三个 commit **逐字节相同**。
  根因是代码里真实存在的顺序敏感竞态，被线程调度随机踩中。
  **教训：CI 红≠回归，先看是不是 flaky，再决定改代码还是改测试。**
- **`1e00132` 那个转储步骤收益≈0**：它抓 `message="..."`，而失败的那条断言
  `assertTrue(result is DownloadResult.Failed)` **没有 message** → 抓不到任何东西。
  所以以后再写断言，必须带 `"实际=" + result`。

## 团队协作机制（可复用）

- `subagent` 是**即发即走**（返回 `{kind:"activation", name:"agent-N"}`），可以真并发；
  `wait_agent({name})` 返回 `{timedOut}`；靠 `lookme/index/read/*.md` 文件落盘判断进度，不必轮询。
- 给智能体的 prompt 必须自带：项目根路径、必读文档、硬纪律（不许改仓库/不许 gradle/不许 git）、
  **交付文件的确切路径**、结论优先的格式、以及「未确认要标注」。
- 6 路并发读 15 分钟完成，等价于我串行读 6 小时。这是本轮最大的效率来源。

## 踩到的操作坑

- `run_code` 里不能用 `import` 语句，要 `await import('node:fs')`。
- Windows 下用 PowerShell `Get-Content` 读 UTF-8 中文文件会出乱码（`�?`），
  **一律用 `fs.readFileSync(path,'utf8')` 或 read 工具**。
- `findstr /s` 在无匹配时退出码非 0，会让 `execSync` 抛异常 → 要 try/catch 并读 `e.stdout`。
