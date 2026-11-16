# round-08 G4 落地 + 又一次静默替换失败的复盘

日期：2026-11-17

## 交付

**G4：sd15npu 档位锁死 512×512。**资产里没有 .patch，引擎的 --patch 在生产路径从不传入；
非 512 画布会被引擎**静默回落 512**——用户拿到尺寸不对的图且无任何报错。现在：
- `GenerateImageTool.availableSizes` 改成 `suspend () -> List<Pair<Int,Int>>`（读 DataStore 需要）；
- `AppModule` 按当前选中的本地模型给档位：`backendType == "sd15npu"` → `listOf(512 to 512)`，
  其余（CPU/远端）维持全局范围校验；
- 越界请求拿到 `invalid_size` + 可用档位说明（"当前模型没有 768x512 这一档；可用：512x512"）。

## 三次提交才绿的原因（两个新坑 + 一个旧坑）

1. `627a938` 红：档位 lambda 不是 suspend，读 DataStore `first()` 编译失败 → 工具参数改 suspend。
2. `0c8b815` 仍红：`modelsRoot` 不在 AppModule 该作用域。**我的修复替换静默失配**（见下）。
3. `a7b9581` 绿（run 43914414424）。

## 新坑（记进 memory，别再犯）

- **CRLF 文件上用 \n 拼接的字符串做 replace 会静默失配**——findstr/读文件时看不出，
  唯一防法是**替换后立刻读回验证**（含新串、无旧串）。AppModule 这两处都是这么翻车的。
- `head`/`tail` 在 Windows cmd 里不存在（前面已记过），管道里别用。