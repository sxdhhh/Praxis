# 网关移植 · 当前状态与偏离记录

本文记录 Agent2API 网关移植到 Praxis 的实际进度：做成了什么、怎么验证的、哪些明确没做、哪些地方有意偏离了文档。

规格来源是 PORTING-GATEWAY.md。凡与本文冲突之处，以那份文档为准；本文只解释「实现与它差在哪、为什么」。

## 一、一句话现状

P0 全部完成；P1 的纯逻辑部分（路由引擎 + 失败切换 + 账号池读写）完成；P2 的数据层与账号页完成。

仍未开始的是：真正发请求的 GatewayProvider、添加账号的三级流程、H5 容器、模型管理页与路由页 UI、P3 及之后的一切。

## 二、测试与验证方式（务必看清这一节的边界）

| 范围 | 用例数 | 能否本地执行 |
|---|---|---|
| core:gateway（纯 JVM） | 160 | 当前机器起不了 Gradle（内存不足），未执行 |
| core:data 的网关映射与落库 | 20 | 同上，未执行 |
| :app / :feature（Room 实体、DI、Compose） | 0 | 本地完全编不了，只能交 CI |

所以：这 180 例只是写出来了，还没有在任何地方跑过。本次改动尚未推送，CI 一次都没跑。
凡本文写「有测试」的地方，指的是存在该用例，不表示它已经通过。

已经做过的静态检查只有一种：括号与结构配平（自写脚本）。它只能证明结构没断，证明不了类型正确。

## 三、已完成

### P0（入口与格式）

- feature:gateway 模块 + PraxisRoute.SettingsGateway + 设置首页入口 + 概览页
- ModelProfile.kind（single / gateway）+ 首次建档预置一条「网关」档案（不设为默认激活）
- 模型页按 kind 分叉：网关档案下不渲染服务商、接口地址、API Key，换成网关入口卡片
- 账号文件、modelRules、config.json 三条线都双向可逆（导入 + 导出 + 往返测试）

### P1（路由与账号池，纯逻辑部分）

| 文件 | 覆盖文档 | 用例 |
|---|---|---|
| CooldownKey.kt | 6.5 独立类型要求 | — |
| FailurePolicy.kt | 7.4 失败到动作表 | 10 |
| RouteState.kt | 6.5 四张运行期表 | 13 |
| CandidateChain.kt | 7.1 候选链 + 5.4 大小写与遮蔽 | 16 |
| RoutePlanner.kt | 7.2 过滤 + 7.3 排序 | 18 |
| FailoverLoop.kt | 7.4 + 12 换号上限 | 13 |
| AutoContinuePolicy.kt | 7.5 自动续写 | 10 |
| GatewayCredential.kt | 6.1 凭证两种形态 | 7 |
| GatewayStatusText.kt | 9.0 三态灯与倒计时 | 10 |
| GatewayAccountStatus.kt | 9.0 状态推导 | 10 |
| GatewayAccountRow.kt | 界面行模型 | 4 |

账号池存储层在 core:data：GatewayAccountStore 落库、凭证加密边界、行模型组装，共 11 例；
模型条目表（gateway_model，v12 到 v13）与账号表、提供商表（v11 到 v12）各有映射测试。

### P2（数据层与账号页）

- gateway_model 表 + 自动迁移 + DAO + 映射（含「三态能力位不被压成两态」的用例）
- 账号页：MiuixDraggableList 拖动排序 + MiuixBadge 三态灯（颜色随状态动画过渡）
- 概览页账号数接真实数据；:app 通过 GatewayModule（Koin）注入账号池

## 四、明确未实现（不是忘了，是还没到）

按文档阶段表，以下均未开工。界面上不做「点了没反应」的假入口：

1. GatewayProvider：真正的出站请求，以及失败切换循环的接线
2. 添加账号的三级流程（家、账号、模型）与 H5 容器
3. 导入导出的界面（解析与报告已完成，但没有落地入口）
4. 模型管理页、路由页
5. 令牌预算限流：7.2 第 6 行只实现了每账号与每家的 RPM；token 预算需要 P3 的请求记录表才有数据源
6. P3 及之后：协议编码器（Anthropic 与 Responses）、报表、日志并入、系统设置、代理、云同步、Rust 内核与 JNI、签到框架、AGPL 转换
7. 全局开关 gateway_enabled：文档 11 末尾要求「每阶段结束都要能回滚，gateway_enabled = false 时必须与现在的行为完全一致」。这一条我**没有实现**——目前没有任何总开关，网关只在用户主动进设置页、且档案切到「网关」时才起作用，但没有一个「一键回到移植前行为」的闸。做它需要一个 DataStore 键 + 设置页开关 + 模型页与选路两处都遵守它，是一整轮的量

## 五、对文档的有意偏离（每条都说明原因）

| # | 文档要求 | 实际做法 | 原因 |
|---|---|---|---|
| 1 | 9.0：分区用 SettingsSectionHeader | miuix SmallTitle + Card | 那个控件是 feature:settings 内部件，网关模块引用会造成 feature 到 feature 的依赖。若把它提到 ui:widgets 公开面，可以改回文档写法 |
| 2 | 9.3：多选批量模式用 MiuixSelectableRow | 未实现批量模式 | 该控件是 internal（模块私有），跨模块引用不到。同样需要先提到公开面 |
| 3 | 10：GatewayAccountStore 放 app/data/gateway | 放 core:data/gateway | KeystoreCipher 与两张表的 DAO 都在 core:data；放 :app 既拿不到它们的可见性，也失去纯 JVM 单测的可能 |
| 4 | 6.5：RouteState 只列了读方法与 onFailure | 补了 markSink、markLimit、breakerUntil | 7.4 的「还有额度时另加垫底」与「429 冷却到重置时刻」没有写入口就实现不了；breakerOpen 只给布尔，界面要显示「还有多久恢复」答不了 |
| 5 | 6.4：gateway_retry_count（单账号内重试） | 未实现 | 7.4 每一类失败都直接给了动作，没有一类写着先原地重试一次；硬塞等于自己发明语义 |
| 6 | 7.2 第 4 行把 sink 列在冷却过滤器里 | 按软降级实现（仍可用、排最后） | 7.3 又要求 sink 排到队尾。若在过滤阶段就滤掉，那句排序规则永远没有作用对象 |
| 7 | 8.4 的键名在熔断与压缩两组只写了前后缀 | 按「组前缀 + 后缀」拼全名 | 若网关真实键名不同，它们会出现在导入报告的 unknownKeys 里，不会静默丢失；宁可报告多一行，也不猜两套名字 |
| 8 | KeystoreCipher 既有约定：加密失败就存明文 | 网关凭证加密失败则整次导入失败 | 那正是兜底式设计：用户以为密钥加密了，实际明文躺在库里 |

## 六、已知风险

1. 180 例测试从未执行过（见第二节）。推送后必须核对 CI 的 build 与 verify。
2. Room 迁移只在编译期被验证过：v11 到 v12、v12 到 v13 都是新增表、走自动迁移，但 Room 真正的 schema 校验发生在打开数据库时。需要一台装过旧版本的设备启动一次才能确认。
3. 账号页的 Koin 接线与 Compose 代码从未编译过。
4. 缺全局回滚闸（见第四节第 7 条）：万一网关路径出问题，用户没有「关掉它」的开关，只能把模型档案切回非网关档案。

## 七、下一步建议顺序

1. 推送并核对 CI；红了先修
2. 用一台装过 v11 的设备启动一次，确认迁移不崩
3. P1 剩余：GatewayProvider 与添加账号三级流程（做完这一步，账号页才有真实数据可显示）
4. P2 剩余：模型管理页、路由页
