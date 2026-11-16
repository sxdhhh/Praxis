# round-04 按取证矩阵补全登录方式

日期：2026-11-16

## 做法

派智能体把上游 `ProvidersPlugin/Agent2Providers`（main@`3055f41`，pack 3.4.2）整仓源码读了一遍，
以**协议申报的 `LoginMethod` + `login_start` 实现**双证据为准，产出
`lookme/index/read/07-provider-login-matrix.md`（38 行事实表）。

## 结论（推翻了我上一轮的几处判断）

| 家 | 我上一轮 | 取证后 | 依据 |
|---|---|---|---|
| modelscope / -intl | Unknown | **ApiKey** | `ms-` 令牌，同一串兼作 Cookie 值 |
| loomy | WebLogin | **SmsCode** | 上游申报 `LoginMethod::Sms`，url 为空 + poll 0 |
| autoclaw（国内版） | Unknown | **SmsCode** | 国内版唯一入口是手机号验证码 |
| qoder / -intl | Unknown | **DeviceCode** | PKCE 设备授权；另可粘 PAT |
| cline-free / -pass | Unknown | **DeviceCode** | WorkOS RFC 8628 |
| zcode / -intl、workbuddy / -intl、atomcode、officeace | Unknown | **WebLogin** | 都是「上游终结、无本机回调」的轮询型网页登录 |
| kiro / catpaw / trae / accio / codearts / antigravity / lobsterai | Unknown | **WebLogin** | 本机回环回调（部分带 PKCE / DPoP） |
| kuku / doubaowork | CookieImport | **CookieImport**（不变） | kuku 取 BDUSS；豆包存完整 Cookie |
| raccoon | TokenImport | **TokenImport**（不变） | 可粘 auth.json / 实时读桌面端 |
| monkeycode / -intl | Unknown | **WebLogin** | 内嵌窗口登录 + 验证码，宿主回收 session |
| astudio | WebLogin | **WebLogin**（不变） | 本机内嵌登录页（手机号/密码 + 极验滑块） |
| chatglm / tabbit / tabbit-intl | Unknown | **Unknown**（不变） | 源码不在公开仓库，只有本机安装件 —— 不猜 |

新增枚举档 `ProviderLoginKind.SmsCode`（手机号验证码）：它与 `WebLogin` 不是一回事，
混在一起会让界面打开一个没有意义的页面。

## 验证

`:core:gateway:test` **408 例全绿**（新增「短信登录与网页登录分开」「只剩未公开源码的网页家有未知」等用例；
「登录方式的人话只有一处」里原本断言 qoder 未知，取证后改成断言 tabbit）。

## 教训

- 我给的 `Unknown` 里有 20 家其实是**能查到的**，只是我没去查。取证成本不高（一个智能体 10 分钟），
  比在界面里写「上游未声明」诚实得多。
- 单一数据源的价值在这一轮体现出来：改登录方式只动 `ProviderCatalog` 一张表 + 一个 `when`，
  界面与文案自动跟上。
