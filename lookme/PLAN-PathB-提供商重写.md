# Path B 实施计划：33 家 Agent 插件提供商 Kotlin 重写

> 用户决策：路径 B —— 在 Praxis 里用 Kotlin 重写全部 33 家 Agent 插件提供商的适配器。
> 每轮做 5 家，不推 CI，全部完成后统一审查再推。
> 上游参考：C:\creat-agent\work\a2p\Agent2Providers-main\crates\providers\src\providers\

## 核心接口设计

```kotlin
// core/gateway/ProviderAdapter.kt
interface ProviderAdapter {
    val id: String
    val label: String
    // 登录：返回登录方式列表（H5 / 设备码 / 粘贴令牌 / 短信 / Cookie）
    suspend fun loginMethods(): List<ProviderLoginMethod>
    // 执行登录（根据方式分发到 H5 / 轮询 / 验证码）
    suspend fun login(method: String, input: String): Result<ProviderCredentials>
    // 发消息（流式）
    fun chat(credential: ProviderCredentials, request: ChatRequest): Flow<AiStreamEvent>
    // 拉取模型清单
    suspend fun fetchModels(credential: ProviderCredentials): Result<List<ModelInfo>>
    // Token 续期（null = 不支持）
    suspend fun refresh(credential: ProviderCredentials): Result<ProviderCredentials>?
    // 余额 / 积分（null = 不支持）
    suspend fun credits(credential: ProviderCredentials): Result<String>?
    // 签到（null = 不支持）
    suspend fun checkIn(credential: ProviderCredentials): Result<String>?
    // 客户端伪装头（每家不同）
    fun emulationHeaders(): Map<String, String>
}
```

## 分批计划（按复杂度 + 登录类型分组）

### 批次 1（最简单的 5 家，建立模式）
| # | 提供商 | 登录方式 | Rust 行数 | Kotlin 预估 |
|---|---|---|---|---|
| 1 | modelscope | API Key | 1,488 | ~300 行 |
| 2 | modelscope-intl | API Key | （同上 Region 参数化） | ~30 行 |
| 3 | commandcode | API Key + 设备指纹 | 2,162 | ~500 行 |
| 4 | loomy | SMS 验证码 + HMAC 签名 | 2,502 | ~600 行 |
| 5 | orcaterm | JWT 粘贴 / 微信扫码 / 桌面导入 | 1,862 | ~500 行 |

### 批次 2（中等：web_login / 设备码）
| # | 提供商 | 登录方式 | Rust 行数 |
|---|---|---|---|
| 6 | atomcode | web_login（轮询型） | 2,926 |
| 7 | lobsterai | web_login + loopback | 3,259 |
| 8 | cline-free | device_code (WorkOS) | 3,585(共享) |
| 9 | cline-pass | device_code（同一实现参数化） | — |
| 10 | kuku | cookie_import + web 回调 | 3,484 |

### 批次 3（中等：web_login 轮询型）
| # | 提供商 | 登录方式 | Rust 行数 |
|---|---|---|---|
| 11 | qoder | device_code (PKCE) + PAT | 8,738 |
| 12 | qoder-intl | 同上 | — |
| 13 | qwenwork | device_code（官方设备授权页） | — |
| 14 | qwenwork-intl | 同上 | — |
| 15 | monkeycode | web_login（内嵌窗口）+ cookie | 4,145 |

### 批次 4（复杂：大参数量）
| # | 提供商 | 登录方式 | Rust 行数 |
|---|---|---|---|
| 16 | monkeycode-intl | 同上 | — |
| 17 | autoclaw | SMS + 重伪装 | 6,710 |
| 18 | autoclaw-intl | web_login + 重伪装 | — |
| 19 | raccoon | token_import + web_login | 4,883 |
| 20 | astudio | web_login（内嵌页）+ api_key | 3,605 |

### 批次 5（复杂：OAuth loopback）
| # | 提供商 | 登录方式 | Rust 行数 |
|---|---|---|---|
| 21 | accio | web_login + loopback PKCE | 5,014 |
| 22 | accio-cn | 同上 | — |
| 23 | antigravity | Google OAuth + loopback | 6,520 |
| 24 | trae | web_login + loopback | 8,246 |
| 25 | catpaw | web_login + loopback + token | 9,468 |

### 批次 6（最复杂）
| # | 提供商 | 登录方式 | Rust 行数 |
|---|---|---|---|
| 26 | zcode | CLI 轮询 | 10,289 |
| 27 | zcode-intl | 同上 | — |
| 28 | codearts | loopback + PKCE + DPoP | 12,530 |
| 29 | workbuddy | web_login（auth state 轮询） | 13,144 |
| 30 | workbuddy-intl | 同上 | — |
| 31 | officeace | 华为云 OAuth + DPoP | 4,179 |
| 32 | kiro | 门户 OAuth + AWS OIDC | 5,566 |
| 33 | doubaowork | Cookie + Chrome UA 伪装 | 4,323 |

### 网页提供商（4 家，独立插件）
| # | 提供商 | 说明 |
|---|---|---|
| 34 | chatglm | userToken + Cookie |
| 35 | deepseek-web | userToken + Cookie（已有详细文档） |
| 36 | tabbit | 未公开源码 |
| 37 | tabbit-intl | 未公开源码 |

## 架构

- `core/gateway/adapters/` 目录，每家一个文件
- 公共基础设施：`ProviderHttpClient`（带重试/超时/UA）、`TokenStorage`（加密存储）
- H5 容器回调：`ProviderLoginCallback`（PraxisWebViewSheet 登录完成后把 Cookie/URL 传回来）
- 设备码轮询：`DeviceCodePoller`（后台协程）
- 签到/积分：每个 adapter 可选实现

## 每轮纪律

1. 读 lookme/GOAL.md + STATE.md + memory/
2. 读上游源码（Rust）→ 写 Kotlin 适配器 → 单测
3. 写 lookme/memory/round-NN + delivery
4. **不推 CI** —— 全部完成后统一审查再推
