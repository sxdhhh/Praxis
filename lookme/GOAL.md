# 目标（长期，持续推进）

> 建立于 2026-11-16 13:26:25（Asia/Shanghai）。每次工作前先读本文件 + memory/ + 上一条 delivery/。

## 总目标

把 Praxis 变成**同时具备两条核心能力**的端侧 AI 客户端，且两条都真的能跑：

1. **Agent2API 网关移植**（`docs/PORTING-GATEWAY.md`）
   多提供商、多账号、多路由策略、故障转移、报告、导入导出、内建 24 家。
   验收线：内置家能拉模型清单 → 建账号 → 选模型 → 真发出请求 → 报告有数据。

2. **Local Dream 本地生图嵌入**（`docs/PORTING-IMAGEGEN.md`）
   真·端侧推理（CPU / NPU / DiT 三类引擎），不依赖云端。
   验收线：手机装上 APK → 下模型 → 启动内置引擎 → 出一张图。

## 过程目标（用户明确要求的工作方式）

- **每轮开工前**：读 `lookme/GOAL.md` → `lookme/memory/*` → 最近一条 `lookme/delivery/*`。
- **每轮收工后**：
  - 写 `lookme/memory/round-NN-<主题>.md`（做了什么 / 为什么 / 踩了什么坑）
  - 写 `lookme/delivery/round-NN-<主题>.md`（交付物清单 + 验证证据 + 遗留）
  - 更新 `lookme/STATE.md`（当前指针：轮次、CI 状态、下一步）
- **通读全库**：`lookme/index/INDEX-files.md` 必须覆盖仓库里 **100% 的文件**（1251 个），
  之后按模块逐轮深读，读过的在 `lookme/index/READ-LOG.md` 记账。

## 铁律（沿用）

1. 不摆「能点但存不进去」的假控件；每个设置项必须有消费方。
2. 不写 fallback 式设计（不静默降级；失败要说清原因）。
3. 不复制上游代码（Local Dream 是 CC BY-NC 4.0）。
4. 不动用户真机；token 只从环境取。
5. 本地攒 ~16 个提交再推一次，推完必须看 CI；红了先深挖原因再推。
6. 绝不提交 `docs/PORTING-GATEWAY.md`（用户改过）与 `docs/PORTING-IMAGEGEN.md`（未跟踪）。
