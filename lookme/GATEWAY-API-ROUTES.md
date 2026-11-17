# Agent2API 网关真实 API 路由（184 个，从 web_shim.rs 提取）

> 来源：`C:\creat-agent\Agent2API\desktop-tauri\src-tauri\server\src\web_shim.rs`
> 这是网关的**完整 REST API 面** —— Praxis 通过这些端点消费网关的全部功能。

## Praxis 需要的关键端点

### 认证
| 端点 | 方法 | 说明 |
|---|---|---|
| /api/panel/login | POST | 面板登录（用户名+密码） |
| /api/session | GET | 检查 session 状态 |
| /api/session/refresh | POST | 刷新 session |
| /api/session/logout | POST | 登出 |

### Agent 提供商登录（核心）
| 端点 | 方法 | 说明 |
|---|---|---|
| /api/session/login/methods?provider={id} | GET | 获取该家的登录方式列表 |
| /api/session/login/start | POST | 启动登录流程 |
| /api/session/login/wait?state={id} | GET | 轮询登录结果 |
| /api/session/login/callback | POST | 回调型登录（H5 容器用） |
| /api/session/login/cancel | POST | 取消登录 |
| /api/session/login/oauth/start | POST | OAuth 启动 |
| /api/session/login/oauth/captcha-config | POST | OAuth 验证码配置 |

### 账号管理
| 端点 | 方法 | 说明 |
|---|---|---|
| GET /api/accounts | GET | 获取全部账号 |
| POST /api/accounts/import | POST | 导入账号（从 JSON） |
| GET /api/accounts/export | GET | 导出全部账号 |
| DELETE /api/accounts/{id} | DELETE | 删除账号 |
| PATCH /api/accounts/{id} | PATCH | 更新账号 |
| POST /api/accounts/refresh | POST | 刷新凭证（Token 续期） |
| POST /api/accounts/current | POST | 设置当前账号 |

### 签到
| 端点 | 方法 | 说明 |
|---|---|---|
| GET /api/checkin/status | GET | 签到状态 |
| POST /api/checkin | POST | 手动签到 |
| GET /api/checkin-center | GET | 签到中心 |
| GET /api/checkin-records | GET | 签到记录 |
| GET /api/auto-checkin | GET | 自动签到设置 |
| POST /api/auto-checkin | POST | 设置自动签到 |
| POST /api/auto-checkin/run | POST | 立即执行自动签到 |

### 积分/余额
| 端点 | 方法 | 说明 |
|---|---|---|
| GET /api/accounts/usage | GET | 各账号用量 |
| GET /api/accounts/usage/snapshot | GET | 用量快照 |
| GET /api/usage | GET | 总用量 |

### 模型
| 端点 | 方法 | 说明 |
|---|---|---|
| POST /api/models/refresh | POST | 刷新模型目录 |
| GET /api/models/manage | GET | 模型管理 |
| GET /api/models/metadata | GET | 模型元数据 |
| PUT /api/models/metadata | PUT | 更新模型元数据 |

### 路由
| 端点 | 方法 | 说明 |
|---|---|---|
| GET /api/routing | GET | 路由状态 |
| GET /api/routing/queue | GET | 候选队列 |
| GET /api/routing/replays | GET | 路由回放 |

### 配置
| 端点 | 方法 | 说明 |
|---|---|---|
| GET /api/config | GET | 获取配置 |
| POST /api/config | POST | 保存配置 |

### 其他
| 端点 | 方法 | 说明 |
|---|---|---|
| GET /api/plugins | GET | 已安装插件 |
| GET /api/logs | GET | 运行日志 |
| GET /api/stats/summary | GET | 统计汇总 |
| GET /api/proxies | GET | 代理列表 |
