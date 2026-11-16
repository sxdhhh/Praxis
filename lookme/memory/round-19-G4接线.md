# round-19 G4 并发/RPM 过滤接线（收尾）

日期：2026-11-17

## 交付（7db45ea + bd0da2e + a5e51af，CI run 43965654172 绿）

1. **RouteState 进行中计数**：beginAttempt / endAttempt / runningCount（成对增减、防负、归零清表）。
2. **GatewayProvider**：发流前 begin、成功路径 end、catch 首行 end（取消也归还）；
   views 快照带 runningConcurrent 进 planner —— 三条「写了但空转」的跳过原因里
   「并发已满」的数据源第一次接通。
3. **每账号 RPM**：GatewayUsageEntities 加按账号窗口内请求数查询
   （**含 isTest**：保护性计数，不给「测试连接白嫖绕过」留后门）；
   GatewayUsageStore 暴露 requestCountsByAccountSince；
   RouterSession.setRpm 推入 planner（next() 里并进 accounts 快照）。
4. **装配**：PraxisApp 三个构造点传 usageRpm。

## 如实的边界

账号的 maxConcurrent / requestLimitPerMinute 字段**有列无编辑入口** —— 上限恒为 0 =
不过滤。也就是说：机制端到端接通了，但**要看到「并发已满」跳过原因，还需要账号页的
上限编辑 UI**（下一轮或用户提出再做）。这不是假控件：机制没有 UI 也能被 planner 消费。

## 验证

core:gateway **433 例全绿**（RouteState 计数 2 例）。CI run 43965654172 全绿。

## 过程坑（本轮红灯 3 次全部是「锚点/结构」错）

1. collect 外多加 try 层没闭合（braces +1）→ 恢复干净基线后**一次原子替换**重做。
2. GatewayUsageEntities 的插入截断了 TokensRow 数据类。
3. 嵌套 lambda 里误用 `return@`。

`grep → 改 → diff 自查 → commit` 的 diff 自查这条，执行了但不够细 —— 以后 diff 自查
要**逐块过**而不是只看 stat。