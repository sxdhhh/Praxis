# round-10 G6 会话粘性接线（空转 → 真效果）

日期：2026-11-17

## 交付

粘性整条机制此前是「真开关假效果」：策略默认开、路由页有开关、RouterSession 有全套接口，
但没有**人存**「上次这个对话用了哪个账号」。本轮补上缺失的那一环：

- 新增 `core:gateway/StickyAccountStore`：内存表（键→账号 id），TTL 30 分钟、容量 256（超限淘汰最旧）、
  时钟可注入。**单例**注册进 Koin，三个 GatewayProvider 构造点共用 —— 每次新建空表等于没粘性。
- `GatewayProvider`：会话建好后按 `ConversationKey.of`（显式头优先，Praxis 的 ChatRequest
  没有 headers，实际走首条内容哈希）取上次成功的账号 → `session.setStickyAccount`；
  成功后 `session.stickyAfterSuccess` 写回表。
- `RouterSession.stickyAfterSuccess` 从「只写不读」变成有消费方；`ConversationKey` 的 7 个单测
  不再是死代码的测试。

## 取舍（写在 KDoc 里，不是嘴上）

- TTL 30 分钟：覆盖一次连续对话，不把账号钉死半天。
- 内存不持久化：进程重启回顺序/轮询首位是可接受行为，持久化反而制造清理负担。
- 两个人恰好同一句开场白会共享键：`ConversationKey` KDoc 早就写明，不是 bug。

## 验证

`:core:gateway:test` **421 例全绿**（新增 StickyAccountStoreTest 5 例：往返/TTL/淘汰/空键/覆盖）。
`:app` 侧接线等 CI。

## 过程坑

- PraxisApp 三处构造点的 `groups = { ... },` 有两种缩进（probe 12 空格、prod 28 空格），
  12 空格的锚点是 28 空格行的**子串** —— 计数骗人。按行号精确插入才可靠。