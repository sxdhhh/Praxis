# round-10 交付

| 项 | 内容 | 验证 |
|---|---|---|
| 经网关入口 | `agent2api` 内置家（Api 直连 + ApiKey，地址自填） | run 43917778780 绿 |
| G6 会话粘性 | StickyAccountStore 单例 + GatewayProvider 接线（setStickyAccount / 写回） | run 43920493428 绿；core 421 例 |
| G4（上轮尾） | sd15npu 锁 512×512 | run 43914414424 绿 |

## 提交链（本段已全部推送并 CI 绿）

``
627a938 → 0c8b815 → a7b9581   G4（两次红灯后绿）
f027731                       经网关入口
ecc3fc2                       G6 粘性接线
``

## 遗留（按优先级）
1. 能力位落 `gateway_model` 表（/models 解析已有，缺写入方）。
2. G5 续期 / G13 垫底：接线或摘除（决策项）。
3. 真机验收：A 线（含新加的 agent2api 家走一遍）+ B 线。
4. QNN 解压进度（G6-imagegen）/ 模型库删除入口（G19）。