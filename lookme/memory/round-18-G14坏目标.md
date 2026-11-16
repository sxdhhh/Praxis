# round-18 G14 坏目标单独显示

日期：2026-11-17

## 交付（4ae42a1，CI run 43959357153 绿）

`imagegen_target` 解析失败时 ImageGenRoute 显示
「生图目标配置读不出来（可能被手动改坏）。请在下方重新选择里重选一次；下次下载完成时会自动接管。」
—— 不再伪装成「还没选。装好第一个模型后会自动选中它」（那会让用户以为是自己没配，
且掩盖了「下次下载会静默覆盖他的选择」这个行为）。

实现：PraxisApp 保留 decode 的 Result（不再 getOrNull 吞掉失败态），
`imageGenTargetBroken = isFailure` 一路传到路由；SettingsHost 两层签名各加一个参数。

## 验证

CI run 43959357153 build ✓ verify ✓。

## 本轮红灯 2 次（同根因：锚点）

- SettingsHost 的 `currentTarget` 参数只在路由调用层出现，两层 Host 用的是
  `imageGenCurrentTarget` 这个名字 —— 锚点又没先 grep。
- 双插入导致 L1 重复声明。最终靠「打印实际行 → 按行修」收尾。

**教训确认：这个 session 的所有红灯没有一个是逻辑错误，全是「没先看真实文本就动手」。**
`grep → 改 → diff → commit` 四步里的第三步（diff 自查）今后必须执行。