# lookme —— 工作循环与记忆约定

用户要求的工作方式，**每轮必须遵守**：

```
读记忆 → 工作 → 交付
  ↑                  ↓
  └──────────────────┘
```

## 目录职责

| 路径 | 内容 | 谁写 |
|---|---|---|
| `GOAL.md` | 长期目标 + 铁律 | lead |
| `STATE.md` | 当前指针（轮次 / CI 状态 / 下一步），**每轮覆盖** | lead |
| `memory/round-NN-<主题>.md` | 这一轮学到什么、为什么这么决策、踩了什么坑 | 每轮的 lead |
| `delivery/round-NN-<主题>.md` | 这轮交出去什么、怎么验证的、遗留什么 | 每轮的 lead |
| `index/INDEX-files.md` | 全库自动索引（961 个文本文件的声明级摘要） | 脚本生成 |
| `index/read/*.md` | 逐模块通读成果（智能体产出） | 通读智能体 |
| `index/READ-LOG.md` | 通读台账：哪些范围已读完、由谁读、结论指向哪 | lead |

## 轮次纪律

1. **开工三步**：读 `GOAL.md` → 读 `STATE.md` → 读最近一条 `delivery/` 与本主题相关的 `memory/`。
2. **一轮一件事**：不并行改两块代码；并发只用于「读/审」，集成永远串行（lead 做）。
3. **收工必交付**：`memory/` + `delivery/` + 更新 `STATE.md`，缺一不算交付。
4. **如实**：没做的写「没做」，没验证的写「未验证」。禁止把「编译通过」说成「功能可用」。
5. `lookme/` **进仓库**（它是项目资产），但里面不许出现 token、手机序列号、用户隐私。

## 智能体分工（并发读/审，写文件互不重叠）

| 智能体 | 范围 | 产出 |
|---|---|---|
| WS-R1 | `core:gateway` + `feature:gateway` | `index/read/01-gateway.md` |
| WS-R2 | `core:imagegen` + app 生图实现 + 引擎资产 | `index/read/02-imagegen.md` |
| WS-R3 | `core:ai` / `core:model` / `core:data` / `core:plugin` | `index/read/03-ai-data-model-plugin.md` |
| WS-R4 | `app` 模块全量 | `index/read/04-app.md` |
| WS-R5 | `feature:*` / `ui:*` / core 其余 | `index/read/05-feature-ui-core-misc.md` |
| WS-R6 | CI 取证 | `index/read/06-ci-forensics.md` |
