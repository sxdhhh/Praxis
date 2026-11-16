# round-12 G5/G13 决策落地 + 验收清单

日期：2026-11-17

## 交付（提交 47b5ec2，CI run 43931777691 绿）

1. **G5**：删 `FailoverStep.RefreshCredentials`。Praxis 没有续期执行者，「返回了没人做」是最糟状态
   （审计原话）。语义降级：凭证过期 → 直接换号 + 标 needsRelogin。哪天支持 OAuth，把这一档拆回两步。
2. **G13**：`QuotaLimited` 且该家还有别的模型 → `state.markSink` —— 文档 7.4「还有额度时另加垫底」
   这半句第一次有了执行者；单模型的家不标（沉不沉没区别）。RouteState.markSink 从死代码变活。
3. **真机验收清单**：`lookme/delivery/round-12-真机验收清单.md` —— A 线十步 + B 线五步，
   每步都标了它验证的是哪一个修复（A5=G1、A7=G6、A8=G5、A10=G3、B1=G2、B2=G11、B4=G4）。

## 验证

`:core:gateway` **427 例全绿**（凭证过期 2 例改写 + 垫底 2 例新增；RefreshCredentials 相关 3 例删除）。
CI run `43931777691` build ✓ verify ✓。

## 剩余（诚实清单）

- needsRelogin 现在有写入方但账号页还没展示（下一轮小改动）。
- imagegen G6（QNN 解压进度）/ G12（acknowledge）/ G19（模型库删除）。
- 自定义家 modelsJson 能力位。
- 真机验收本身 —— 等用户执行上面的清单。