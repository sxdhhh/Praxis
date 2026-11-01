# 05-suchat

共 102 条 / 84458 字符


## suchat Phase 1 完成并提交：app API 接入层（0a54d88）

# suchat Phase 1 完成并提交：app API 接入层（0a54d88）

日期：2026-10-11。用户指正顺序（先 app 接入点 → 再写服务器）已纠正并落实。

## 提交 0a54d88 内容
- docs/suchat-api-contract.md（新增 147 行，两侧唯一依据）
- SuchatApiClient：9 → 40+ 方法（新增 patch 助手；uploadContent 是唯一非 JSON 请求）
- RemoteModels：+ 12 个 DTO（ContactsBundle/ContactDto/FriendRequestDto/MomentDto/MomentCommentDto/BottleDto/DeviceDto/NotificationDto/SettingsDto/UploadDto）
- RemoteMapper.kt（新增）：DTO → UI 模型纯函数映射
- SuchatRepository.kt（新增 256 行）：6 个 StateFlow + loading/error + 乐观更新
- LocalSuchatRepository.kt（新增）：CompositionLocal 通道
- MainActivity：AppSession.Connected 带 accessToken；建 Repository；SessionStore 恢复会话
- ServerSetupScreen：登录/注册从占位 → 真实调 API
- 4 个页面接 Repository（预览模式内部即 SampleData，观感不变）

编译：BUILD SUCCESSFUL（修掉 4 个真错误：ContactsBundle 单复数、缺 import、UserProfile 同名冲突、flow.first）

## 服务器实现清单（Phase 2 依据，必须逐条满足）

### 必做（客户端已硬依赖，字段不能缺）
1. `GET /api/v1/health` → status/service/version/time/database（**全部 string**）
2. `POST /api/v1/auth/register` + `POST /api/v1/auth/login` → `{user:{id,handle,displayName,avatarUrl,bio,status,createdAt,lastSeenAt}, tokens:{accessToken}}`
   - 错误码：`invalid_credentials` / `handle_taken` / `invalid_handle` / `weak_password` / `registration_disabled`
3. `GET/PUT /api/v1/me/appearance` → glassMode/performanceProfile/pageTransition/themeMode（string）+ reducedMotion（**boolean**）
4. `GET /api/v1/conversations` → `{items:[{id,kind,title,createdAt,updatedAt,lastMessage}]}`
5. `POST /api/v1/conversations` {memberIds,kind,title?}
6. `GET /api/v1/conversations/{id}/messages` → `{items:[{id,conversationId,senderId,content,contentType,attachmentId,createdAt,editedAt}]}`
7. `POST /api/v1/conversations/{id}/messages` {content,contentType,attachmentId?}

### 新增方法对应的端点（也需实现，否则 UI 接入点无法验证）
- `GET me` → `{user:{...}}`；`PATCH me` {displayName?,bio?,avatarUrl?}
- `GET users/search?q=` → `{items:[user]}`
- `GET contacts` → `{contacts:[{userId,handle,displayName,avatarUrl,alias,status,createdAt,lastSeenAt}], requests:[{id,fromUserId,fromHandle,fromDisplayName,fromAvatarUrl,message,status,createdAt,outgoing}]}`
- `POST contacts/requests` {userId,message}；`POST contacts/requests/{id}/accept|reject`
- `POST conversations/{id}/read`
- `PATCH messages/{id}` {content?,recalled?}
- `GET/POST moments`（含 likeCount/liked/comments[]/uploadIds[]/authorId/authorName/authorAvatarUrl/visibility）
- `POST moments/{id}/like` {liked}；`POST moments/{id}/comments` {content}
- `GET/POST bottles`；`POST bottles/pick` → `{bottle:...|null}`
- `GET/POST devices` → `{items:[{id,deviceName,platform,pushToken,lastSeenAt,createdAt,current}]}`
- `GET notifications` → `{items:[{id,kind,title,body,read,createdAt}]}`；`POST notifications/read-all`
- `GET/PUT me/settings` → notifyMessage/notifySound/notifyVibrate/notifyDetail(bool) + privacyAddMe/privacyMomentsRange/chatHistoryKeep(string)
- `POST reports` {targetType,targetId,reason}
- `POST uploads/metadata` {fileName,mimeType,sizeBytes} → `{id,fileName,mimeType,sizeBytes,uploadUrl,contentUrl}`
- `PUT uploads/{id}/content`（**raw binary body**，Content-Type 为文件 MIME）→ UploadDto

### 工程要求（用户强调“旧服务器太狗屎，要重构”）
- 零依赖：node:http + node:sqlite(DatabaseSync) + node:crypto（本机 node v24.21.0 全部可用）
- 分层：src/core/{config,crypto,db,schema,logger,http,validate,events} + src/routes/* + src/admin/*
- 密码 scrypt 加盐哈希（旧版**明文存储**是硬伤），token 用 HMAC-SHA256 自签 JWT 格式
- 无依赖请求体解析（JSON + raw binary）+ 统一错误格式 `{error, message?}` + 请求日志 + 速率限制
- 旧服务器位置：/root/gh-all/suchat-server-local（146 行单文件，参考其端点覆盖面）
- 新服务器位置：**/root/suchat_server**（已建骨架 src/{core,routes,admin} public/admin data tests；已写 src/core/config.js 与 crypto.js）
- 端口 8787（已确认空闲；8899 给 gitsrv）

## Phase 3：管理后台（用户要求“功能越全越好”）
已规划 30+ 端点（见契约文档第四节）：dashboard/metrics.timeseries/users CRUD+重置口令/admins 与角色/conversations/messages 检索/broadcast/moments/bottles/contacts/reports/audit-logs/storage+cleanup/system.status/settings/backup/CSV 导出。

---

## suchat 第七轮：液态玻璃对齐 WeKit（重力高光 + AGSL 光斑）+ 设置全实体化 + 三级转场 + 预测性返回

# suchat 第七轮：液态玻璃对齐 WeKit（重力高光 + AGSL 光斑）+ 设置全实体化 + 三级转场 + 预测性返回

## ★ 状态：编译通过 + CI 绿 + 装机验证零崩溃（2026-10-02）

- 提交链：`46d591e`（设置/转场/预测返回/鱼眼）→ `d4552b7`（液态玻璃对齐 WeKit）
- CI：`36973008395`（46d591e success）、`36976775199`（d4552b7 success）
- 装机：进程存活（pid 1758）、PSS 149MB 稳定、无新崩溃

## 一、液态玻璃「还是不对」的根因（★ 本轮最重要）

用户反馈「继续液体玻璃还是不对」。与上游 WeKit 逐文件比对
（`ui/content/FloatingBottomBar.kt` + `liquid/` + `animation/`），
找到两处**我自己引入的偏离**：

### 真因 1：完全缺「重力感应高光」（主因）
WeKit 的 `dynamicGravityHighlight = true` 是**默认开**的：
```kotlin
val tilt = rememberDeviceTilt().value          // miuix-blur 的传感器 API
val baseHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, tilt, -45f)
val pillHighlight = rememberGravityRotatedHighlight(iosIndicatorSpecular, tilt, +90f)
```
- 算法：`g=(gravityX,gravityY)`；`|g|²>0.01` 归一化否则回落 `(0,-1)`；
  再按 extraDegrees 旋转；`lightPos = (0.5 + lx, 0.7 + ly)`
- `LIGHT_REF_X/Y = 0.5f / 0.7f`（必须与 miuix `HighlightStyle` 内部值一致）
- 两个角度差 135° → 静止时两道高光错开在胶囊两条不同边上
- **效果：转手机时玻璃边缘那道高光会滑到另一边（「活」的玻璃）**
- 我的错：用完全静态的 `iosIndicatorSpecular` → 玻璃是「死」的

### 真因 2：AGSL 触摸光斑被降级成 radialGradient
我之前担心「每帧全屏 shader 重绘掉帧」，把 `RuntimeShader` 换成 `Brush.radialGradient`。
**那是凭担心的错误优化**（无实测依据），两处可见差距：
- 边缘：radialGradient 线性插值=硬边；AGSL `smoothstep(radius, radius*0.5, dist)` = 内圈全亮 + 软衰减
- 半径：我用的 0.8×minDimension；原版 **1.2×**（要盖满胶囊高度，否则光斑截断）

### 已逐字一致的部分（勿再动）
`Lens.kt`（225行圆弧折射 SDF + 七通道色散 shader）、`Vibrancy.kt`（saturation=1.5）、
`CombinedBackdrop.kt`、`InnerShadow.kt`、`DampedDragAnimation.kt`（唯一差异是我加的
`snapToValue`，当前无人调用）、`inspectDragGestures`（含 `ignoreConsumed` 观察型手势）。
`rememberLayerBackdrop()` 不传 lambda 时默认 `onDraw = DefaultOnDraw`（即 drawContent）—— 会记录内容。

## 二、设置功能全实体化（46d591e）
新增 `ui/page/settings/`：
- `SettingsPrefs.kt`：SharedPreferences 落盘 + `mutableStateMapOf` 可观察；`BoolKey` 24 项 / `ChoiceKey` 6 项
  · **用 SharedPreferences 而不是 DataStore**：同步读（不闪默认值）+ `apply()` 异步写
- `SettingsScreens.kt`（约 1537 行）：13 个真实子页
  · 账号与安全/登录设备管理/青少年模式/关怀模式/新消息通知/聊天/隐私/通用/外观/存储/字体/关于/帮助
  · `SettingsScreen` 的 7 个空壳 `onClick = {}` 全部接上（另加帮助/关于组）
- **外观改完即时生效**：`SuchatAppearance` 由 data class 改**可观察类**（mutableStateOf + var），
  实例提升到 `SuchatLauncher`（与主题层、设置页共享）；玻璃档接进 `MainTabs`

## 三、二/三级页堆叠转场（46d591e）
**真因**：旧实现 `AnimatedVisibility(visible = topPage != null)` ——
二级→三级时 `topPage` 只换个对象、`visible` 恒 true → **转场根本不触发**（瞬间切换）。

修法：**槽位（slot）栈**模型
- `SuchatPage.depth`（1=二级，2=三级）定义层级；`DEPTH_PARALLAX=0.28f` / `DEPTH_SCALE=0.94f`
- 每槽一个 `enter` 进度（0=屏外右，1=就位）；底层退让**派生**自上层 enter（一个值驱动两层 → 无双写入者）
- **不用 `Animatable`**：`snapTo/animateTo` 是 suspend 且 MutatorMutex 与跟手冲突
  → 改 `mutableFloatStateOf` + 手写 `withFrameNanos` tween（跟手=普通赋值，零协程零锁）

## 四、预测性返回（46d591e）
新增 `ui/navigation/PredictiveBack.kt`：
- `PredictiveBackContent`（给进度自己画）/ `PredictiveBackEffect`（回调式，驱动已有动画值）
- 回调用 `rememberUpdatedState` 包装（否则闭包拿到旧 lambda）
- 返回键优先级：关抽屉 > 页面层（可�ĉ跟手预览）> 交还系统

## 五、索引栏鱼眼（46d591e）
- 旧：胶囊 + 左侧大字母气泡（单选反馈）→ 新：**字母本体邻近缩放**（FisheyeIndexBar/WaveSideBar）
- 三要点：① 透镜中心取**连续** `touchY/pitch`（不取整，否则一档一档跳）；
  ② smoothstep `t²(3-2t)`（线性在半径边缘有折角）；③ 按住 `snap()` + 抬手 `spring()`
- 半径按「±2 个字母」表达（不按像素），任何数据量观感一致
- 新增 `fisheyeScale()` / `IndexLetter`；删 `LetterBubble`/`IndexBubbleWidth`/`IndexGap`

## 六、环境与工具链（复用）
- 本机 aarch64：只能 `:app:compileDebugKotlin` 静态验证，APK 必须走 CI
- 命令：`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 && export ANDROID_HOME=/opt/android-sdk && ./gradlew :app:compileDebugKotlin --console=plain`
- `git log` 必须加 `GIT_PAGER=cat git --no-pager`（否则进 less 卡死终端会话）
- 设备验证：`input swipe` 不可靠；`wm size = 1280x2772`；截图 OCR 是验证 Compose UI 的有效手段
- 传感器齐全（Gravity Sensor AOSP / Rotation Vector）→ 重力高光可用

---

## ★★ 2026-10-05：用户两次批评指出的**方法错误** + 据此修正的四处硬 bug

# ★★ 2026-10-05：用户两次批评指出的**方法错误** + 据此修正的四处硬 bug

## 用户原话（这两句必须记住）

> 「**不要盲目瞎猜多看看参考项目和文档**」

> 「**我都给你高版本写的这么好的东西做参考了你为什么还写不出来**，而且你没发现吗，好像别人网页版都可以」

**我做错的事**：一直在反编译 class 文件（`javap`+常量池）去猜 miuix 的 shader 源码，而 **miuix 的完整 Kotlin 源码就在本地 `/root/repos/miuix/`**！（还有 `/root/repos/WeKit`、`/root/repos/AndroidLiquidGlass`）。

⇒ **教训：这个沙箱里 `repos/` 下有全部参考项目的源码，改任何依赖库对接之前，先 `ls /root/repos/` 并读源码，不要反编译。**

## 四处硬 bug（都是「差得远」的直接原因，均已按源码修正）

### 1. 采样源包含了自己 → 玻璃变灰板（用户发对比图：「你自己看着 tm 是一个东西吗」）

**现象**：低版本底栏是一块**均匀灰板**，完全不像玻璃。

**根因**：为省 `toImageBitmap()` 的全屏拷贝，我把采样改成 `hostView.draw(canvas)` ——
但 `hostView` 是**整个 Activity content view，玻璃就在里面**：
每一帧抓到的图含着「上一帧画好的玻璃」→ 玻璃再折射它 → **反复迭代 ⇒ 收敛成均匀色块**。

miuix 没这个问题：`LayerBackdrop` 挂在**内容层节点**上录制，玻璃是它的**兄弟节点**、不在录制范围。

**修**：
- 采样源改回 **layer 快照**（`layer.toImageBitmap()`，录的是 `.lowLayerBackdrop()` 挂的内容层）；
- 新增 `layerOrigin`（内容层在窗口坐标里的原点）—— 因为快照原点 ≠ 玻璃元素的窗口坐标，
  裁剪时要做偏移（`renderForElement(bounds, originX, originY)`）。

### 2. 光源方向推导完全错

miuix `applyLightUniforms`（`highlight/HighlightStyle.kt`）逐字：
```kotlin
val dx = light.position.x - LIGHT_REF_X   // 0.5f
val dy = light.position.y - LIGHT_REF_Y   // 0.7f
val dz = light.position.z
val len = sqrt(dx*dx + dy*dy + dz*dz)
shader.setFloatUniform("lightDir$suffix", dx/len, dy/len, dz/len)
```
⇒ **`lightDir` 是「相对参考点 (0.5, 0.7) 的偏移」归一化**，不是 `LightPosition` 本身。

我此前直接把 `(0.5, -0.3, -0.05)` 当归一化方向 —— **方向完全错**。
正确：`normalize(pos.x-0.5, pos.y-0.7, pos.z)`。

WeKit `FloatingBottomBar.kt` 也有一份镜像实现（注释："Mirrors miuix-blur HighlightStyle's LIGHT_REF — keep in sync"）。

### 3. dualPeak 的公式用错

`iosIndicatorSpecular` 是 **`dualPeak = true`**，走 `BLOOM_STROKE_SHADER_DUAL`：
```glsl
float l1 = dot(n.xy, lightDir1.xy);        // ← **只用 xy**，不点 z
rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;
```
（`dualPeak=false` 才是 `clamp(dot(n, lightDir) * falloff, 0, 1)`，其中 `falloff = max(dot(axis, n), 0)`。）

我此前写 `dot(n, uLightDir)` —— 把 z 也点进去，等于给 3D 法线的平坦区（z=-1）叠了个固定项，**边缘亮度分布整个错**。

### 4. strokeAlphaMul 传错（放大 8 倍）

miuix：`shader.setFloatUniform("strokeAlphaMul", color.alpha)`。
而 `iosIndicatorSpecular` 的 `BloomStroke(color = Color.White.copy(alpha = 0.12f))` ⇒ **0.12**。

我此前传 **1.0** —— 描边强度放大 8 倍。

### 附带：blur σ 公式

`BlurEffect.kt`：`BLUR_RADIUS_TO_SIGMA = 0.45f`，`sigma = radius * 0.45f`。
我此前用 `radius*0.5 + 0.5` —— 那是 **progressive 变半径模糊**的公式
（`createProgressiveBlurEffect` 里 `radius = 2σ − 1` 的反解），用在普通 blur 上**糊过头**。

## 从 miuix 源码读到的其他关键事实（作参照）

| 事实 | 值/说明 |
|---|---|
| 模糊内核 | **配对高斯**（非标准高斯）：配对采样 `a=2j+1, b=a+1` 用重心合并；**部分覆盖权重** `ra=clamp(radius-a+1,0,1)`；**σ=radius×0.45**；**jitter 抖动**（`progRand`，只与坐标有关）|
| 降采样 | `downScaleExpFor(σ²)` 按半径选 1/2/4/8/16 级（阈值 σ²：12.6 / 90.25 / 400 / 1945）；大半径先降采样再模糊 |
| 降采样 shader | 2×：4 点 ±0.25；4×：4 点 ±0.75 |
| 预乘 | 全链路预乘 alpha，输出端 `color.rgb/color.a` |
| `colorControls` | 反预乘 → `pow(c,2.2)` → 亮度 mix → `pow(c,0.45454545)` → 对比度 → `mix(lum, c, sat)` → clamp → 回预乘 |
| 高光 shader | SDF 圆角矩形 + `getNormal` 造 3D 半球法线 + 双光源 Lambert |
| 渐进模糊 | **两步**：整层全强度模糊 + 遮罩 `w` 合成（不是「半径随高度衰减」）|
| 常数 | `halfViewFloor = floor(halfView)`（法线计算的基准）|
| LIGHT_REF | `(0.5, 0.7)`，`GRAVITY_DIR_THRESHOLD_SQ = 0.01`（≈6° 倾斜）|

## 顺带

- 采样节流改**动态**：交互（按压/拖拽）32ms、静止 200ms（此前固定 32ms，静止时白烧 GPU）。
  交互方通过 `backdrop.setActive(true/false)` 切换；泵用 `msUntilNextCapture()` 精确休眠。
- `TOP_BAR_BLUR_OVERHANG = 28.dp` 抽成共享常量（此前 `LowBlurredTopBar` 与 `LowAppScaffold`
  **各写死一份**，改一处漏一处是必定的）。
- 踩坑：`positionInWindow` 不可用（依赖版本无此扩展）→ 用 `boundsInWindow().left/top`；
  用 Python 改大文件时 **KDoc 里的 `/**` 嵌套会把注释弄断**（Kotlin 块注释可嵌套）——
  这次 `MainActivity.kt` 被写坏一次，靠编译器报 `Unclosed comment` 才发现。

---

## suchat 第四轮：底栏↔页面双向联动 + 负一屏 1:1 拄 WeKit（74ce6b0，CI 绿，真机验证）

# suchat 第四轮：底栏↔页面双向联动 + 负一屏 1:1 拄 WeKit（74ce6b0，CI 绿，真机验证）

## ★ 状态：三项反馈全部完成并真机验证（2026-10-02 12:11）

- 提交：`74ce6b0`，CI run `36962851678` **success**，APK 已装机实测
- 真机验证：负一屏 1:1 改造成功（截图 n1.png）；全程无崩溃（PID 14012 稳定）

## 一、WeKit 上游源码已克隆到本地（重要资产）

- 位置：`/root/repos/WeKit`（https://github.com/Ujhhgtg/WeKit）
- 负一屏源码：`app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/`（28 文件、10638 行）
- 关键文件：`HomeSidePanel.kt`（1563行，主入口+层级+动画）、`HomeSidePanelGestureState.kt`（手势状态机）、
  `HomeSidePanelLayout.kt`（卡片配置模型）、`HomeSidePanelDataCards.kt`（时间/天气/一言卡）、
  `HomeSidePanelActionCards.kt`（动作卡）、`HomeSidePanelCardFrame.kt`（卡片外框）

## 二、负一屏 WeKit 权威规格（逐条抄自源码）

| 项 | WeKit 原值 | 源码位置 |
|---|---|---|
| 抽屉宽度 | `DRAWER_WIDTH_FRACTION = 0.84f`（屏宽 84%） | HomeSidePanel.kt:1551 |
| 遮罩最大透明度 | `DIM_MAX_ALPHA = 0.52f` | :1552 |
| 主内容缩放 | `scale = 1f - 0.05f * eased`（缩 5%） | :86 |
| 主内容位移 | `translationX = 7dp*eased`、`translationY = 8dp*eased` | :87-88 |
| 圆角 | `28dp * easedProgress` | :1331 |
| 缓动 | `eased = 1 - (1-p)^1.35` | :83 |
| 开合动画 | `duration = 120 + 120·|from-target| ms` + `DecelerateInterpolator(1.4f)` | :1049-1050 |
| 抽屉滑入 | `panelView.translationX = -drawerWidth * (1-p)` | :1342 |
| 开合阈值 | `openThreshold = 0.38` + `velocityProjectionMs = 160f` | GestureState:9,12 |
| 手势方向 | `touchSlop=8dp`；`|dy| > |dx|*horizontalDominance(1.15)` 则放弃 | GestureState:7,10 |
| 层级 | overlayRoot(dim+panel) 加到 decorRoot 顶部，**盖住** contentWrapper | :498-530 |
| 卡片圆角/色 | 时间卡 24dp/`surfaceContainerLow`；动作卡 22dp/`surfaceContainerLow`；一言卡 22dp/`surfaceContainerHighest`；钱包卡 24dp/`primaryContainer` | DataCards:136-137,550-551; ActionCards:385-386 |
| 卡片内边距 | 统一 `padding(horizontal = 18.dp)` | 多处 |
| 用户头部 | avatar **58dp** + 名字(titleLarge/SemiBold) + 状态 + Chevron_right | Home:374-432 |
| 时间卡 | `HH:mm` displaySmall/Bold + 日期 labelMedium + 问候语 titleMedium | DataCards:143-178 |
| 问候语分档 | 5-11 早上好 / 12-17 下午好 / 其余 晚上好 | DataCards:680 |

## 三、底栏↔页面双向联动（用户澄清的完整语义）

**用户原话**：「手拖特效玻璃的时候页面就跟着特效玻璃进度平移。特效玻璃回位的时候页面也跟着回位，然后手滑页面的时候液态玻璃也跟着出现然后跟着页面滑动进度滑动然后回位」

即**双向**：
1. **拖玻璃 → 页面跟随**：`onDragFraction`（scroll{scrollBy} 逐帧）+ `onDragEnd`（吸附回位）
2. **滑页面 → 玻璃跟随**：新增 `externalFractionProvider: (() -> Float?)?`，
   pager 实时回报 `currentPage + currentPageOffsetFraction`，
   玻璃 `press()`（出现）→ `snapToValue(fraction)`（跟随）→ `release()`（回位）

## 四、玻璃消失时机 + 优先级（用户反馈）

**问题**：玻璃特效在**切换页面时**就消失了，应该在**松手时**消失。
**优先级**：优先识别是否松手（手动）→ 再识别是否切换页面。

**根因**：`DampedDragAnimation.animateToValue()` 无条件 `press()+release()`；
拖拽中 pager 翻页 → selectedIndex 变化 → LaunchedEffect 触发 animateToValue
→ 提前 release → **玻璃特效在手指未松开时就消失**。

**修法**（两处）：
1. `animateToValue()` 不再调用 press/release，只做值动画（玻璃收尾只由手势结束触发）
2. 新增 `isGestureActive` 标志（手指按住期间），`LiquidGlassTabBar` 的外部同步
   在此标志为真时一律 `return@collectLatest` 让位

## 五、关键文件改动

- `DampedDragAnimation.kt`：+isGestureActive、animateToValue 去掉 press/release、
  onDragStart/End/Cancel 设置标志
- `LiquidGlassTabBar.kt`：+externalFractionProvider 参数、外部同步让位、页面→玻璃驱动链路
- `WeKitDrawer.kt`：**全重写**（560 行）——抽屉 84%、从左侧滑入盖主营、全套 WeKit 参数、
  面板内容重构为 WeKit 同款卡片（用户头部/时间卡/收付款卡/动作列表卡/今日一句卡）
- `MainActivity.kt`：panel 参数 revealWidth→drawerWidth、externalFractionProvider 接线、负一屏 key 映射更新

## 六、旧结论修正（勿再引用）

- ⚠️ 上一轮的 `revealFraction=0.28f`（露出 34% 屏宽）**已作废** ——
  用户明确「负一屏有点太窄了」，已改为 WeKit 原值的 84% 抽屉宽 + 盖层模型。
- ⚠️ 「负一屏是露出式（内容右移露出左侧面板）」的理解**错误** ——
  WeKit 实际是**抽屉从左侧滑入盖在主内容上层**。

---

## suchat 服务器+客户端并行开工完成（2026-10-14 大轮，6 个提交）

# suchat 服务器+客户端并行开工完成（2026-10-14 大轮，6 个提交）

## 开工方式（重要经验）
用户要求「服务器和本地并行开工」。用 dsh_subagent 跑两个 sub-agent 全部秒挂（MISSING_CREDENTIAL: llm-deepseek）。
**根因**：dsh launcher 用 `--profile headless`，而 workbuddy provider 与 agent-default-model 只配在 `web` profile。
**解法**：把 `dsh-home/profiles/web/cordis.patch.yml` 复制到 `profiles/headless/cordis.patch.yml`（已备份 .bak）。
凭证只存在 `WORKBUDDY_API_KEY`（refs 里），模型 id `deepseek-v4.1-flash`，provider baseURL `http://127.0.0.1:8787/v1`。
但用户最终说「关了吧还是你跑合适点」→ sub-agent 已 cancel，改由主 agent 串行亲自做。

## 冻结契约（关键方法）
并行/分工前先落 `docs/API_V2_CONTRACT.md`（465 行）作为唯一基准，含 schema 全量 SQL、
每域路由表、字段级 DTO、客户端方法签名、Seed 清单。两侧严格按它实现。

## 服务器（/root/suchat_server，无 git，零依赖 ESM，Node v24.21.0）
- **schema v3→v4**（migrations 下标 3 追加）：wallets/wallet_ledger/bank_cards/red_packets/
  red_packet_grabs/paycodes/tags/contact_tags/official_accounts/account_articles/account_follows/
  channels/channel_likes/channel_comments/top_stories/story_likes/favorites/mini_programs/
  mini_program_uses/user_cards/sticker_packs/stickers/user_stickers/life_services/faqs
  + conversation_members 补 alias（role 列 v1 已存）
- **新增 11 个路由文件**：wallet/tags/accounts/channels/stories/favorites/miniapps/cards/stickers/extras
  + conversations.js 补 4 条群成员路由与 ?type=/?withUserId=；misc.js 补好友直加/申请列表/二维码；
  auth.js 的 GET /me 补 balance
- **su 币**：ensureWallet 懒创建默认 100，写一条 kind='grant' 流水「新人礼包」；所有金额操作在 db.transaction 内
- **seed.js**：幂等 INSERT OR IGNORE + 固定 id（公众号4/文章8/看一看6/小程序12/表情4包16项/生活服务4/FAQ6/channels 5【需库里有 user，channels 路由内另有懒补种子】）
- **实况**：迁移 4 条、**路由 138 条**
- **修的两个真 bug**：
  1) db.js transaction 嵌套检测原靠 `getAutocommit()`（Node24 node:sqlite 无此 API）→ 恒等于「不在事务」→ 嵌套 BEGIN 报 "cannot start a transaction within a transaction"。改用模块级 txDepth 计数（外层 BEGIN/COMMIT，内层 SAVEPOINT/RELEASE，finally 递减）。
  2) `/api/v1/conversations/:id/members/:userId` 的 PATCH 里用 `oneOf(body,'role',[...],undefined)` —— oneOf 实现要求 dflt!==undefined，缺省会抛 invalid_field；改手动判定。
- **验证**：/tmp/smoke.mjs 端到端 47 项全绿（注册/me balance=100/wallet grant 流水/转账 A−20 B+20/余额不足/自转拦截/标签CRUD/种子计数/空表/会话过滤/好友直加/二维码/付款码核销/银行卡/账单/生活服务支付/群成员）

## 客户端（/root/suchat_remote，提交 c7bd16b HEAD）
本轮 6 个提交：a67e7ea(契约) → ae72998(API+仓储) → 773b531(清直读+13处接线) → c7f734c(通知页/设备/举报) → c7bd16b(服务器快照)
- SuchatApiClient 新增 **51 个方法**、RemoteModels 新增 **24 个 DTO**、底部新增全部解码器（balanceOnly() 处理只回 {balance} 的写操作）
- SuchatRepository 新增 **15 个 StateFlow + 30 个 refresh/写方法**；refreshAll() 追加 refreshDevices/autoRegisterDevice/refreshWallet/refreshNotifications
- 清理 §15 六处 SampleData 直读（NewFriends/GroupChats/ContactDetail/Search×2/ChatDetail 自己头像）
- 13 处硬编码改为读 repo（标签/公众号/视频号/看一看/收藏/小程序/卡包/表情/钱包），全部保留「无 api 回落原样本」
- 新增通知中心页 `ui/page/settings/NotificationsListScreen.kt`（SuchatPage.Notifications），MeScreen 加「通知」入口
- 消息长按菜单加「举报」→ submitReport("message",id)
- **踩的坑**：FunctionalScreens 加 collectAsState 用法后忘了 import `androidx.compose.runtime.collectAsState/LaunchedEffect` → 报 30+ 连锁类型错误（Unresolved reference 'liked'/'title' 等全是同一个 import 缺失引起）。
- **Note**：`SuchatIcons` 无 `Info`，举报用 `Help`；无 Photos/Document/Folder。
- 编译命令不变：`./gradlew :app:compileDebugKotlin --console=plain`（不加 --offline），每批均 BUILD SUCCESSFUL。

## 待办（未做）
- 群设置 UI（updateConversation 仓储已就位，未接页面）
- 卡片 Card tilt/sink 效果；各页顶部限位逐页核实
- 本地领先 23 个提交，未 push LocalHub

---

## 事件：Praxis 终端虚拟键盘第一轮（结构 1:1 Operit + 莫奈配色，未构建）

# 事件：Praxis 终端虚拟键盘第一轮（结构 1:1 Operit + 莫奈配色，未构建）

## 一、我的三个口径结论（其中两条修正了逆向高手方案）
1. **SSH 归属**：保留在环境页（不是挪到终端设置页）。理由：Praxis 的 SSH 是「容器内起 sshd 供外部连入」，Operit 是「连远程服务器」，方向相反，放同层导航会混淆。
2. **颜色口径**：认可「结构尺寸 1:1 + 颜色全莫奈」，但**键帽底不能用 surfaceContainerHigh**（那是工具栏色阶，同色则键帽与面板无边界=功能问题）。三档：输出区 surfaceContainer / 键盘面板 surfaceContainerHigh / 键帽 surfaceContainerHighest；激活态 primary+onPrimary；**ANSI 16/256 色绝不动**（xterm 标准值）。
3. **范围**：先做第一轮（键盘）。

## 二、关键架构判断（本轮最重要）
`TerminalViewModel.submit` 是**一次性进程模型**（`runner.run(command)` 跑完即结束），**没有常驻 PTY**。所以 Operit 的 `decodeVirtualKeyValue` / `applyCtrlModifierChar`（发 `\e[A` / `\u0003` 到 stdin）在当前架构下接线就是假功能 —— **不写、不留死代码**。
第一轮键盘语义 = **本地输入编辑**，14 个键全部有真实行为：
- ESC 清空 / `/` `-` 插入 / HOME END 跳首尾 / ←→ 移一格（修饰符激活时按词）/ ↑↓ 历史 / PGUP PGDN 历史翻 5 条 / TAB 插两空格（**不做补全**，无持久会话无法枚举文件系统）/ CTRL ALT 修饰符开关。
键位布局完全照抄 Operit（2行×7列），等 PTY 接入后自然升级为真转义发送，UI 一行不用改。

## 三、本轮落地文件（6 个）
| 文件 | 行数 | 内容 |
|---|---|---|
| `keyboard/TerminalKeyboardModel.kt` | 177 | `TerminalKeyId` 枚举（14）/ `TerminalKey`(id+label 分层) / `TerminalKeyLayout`(require 校验 2×7) / `DefaultTerminalKeyLayout` |
| `keyboard/TerminalKeyEdit.kt` | 277 | `TerminalModifiers`(ctrl+alt 双布尔) / `TerminalKeyInput`(text+cursor+modifiers+historyIndex+draft) / `applyTerminalKey` 纯函数 / `wordLeft` `wordRight` / `HISTORY_PAGE_SIZE=5` |
| `keyboard/TerminalVirtualKeyboard.kt` | 226 | 键盘 UI。尺寸照 Operit（圆角 4dp、padding h×0.5/v×0.8、spacedBy×0.5、Monospace Medium）；颜色全莫奈 |
| `TerminalScreen.kt` | 1064 | 接入：`keyLayout` 参数、`fieldValue: TextFieldValue`（原来是 String）、键盘显隐、操作条 `⌨` 开关 |
| `TerminalViewModel.kt` | 366 | `Tab.history` per-session 历史（连续去重、上限 200、`toList()` 副本发布） |
| `test/.../TerminalKeyEditTest.kt` | 302 | 22 个用例，纯 JVM 无 Android 依赖 |

## 四、修掉的两个真 bug（都是我自己写的）
1. **historyStep 差一**：起点写 `lastIndex` 再 `+(-1)` → 第一次按 ↑ 拿到**最旧**那条（用户以为只存了一条）。修法：起点用 `history.size`（末尾之后一格）。已用 Python 独立复现验证。
2. **fieldValue 的 remember key 用错**：初版 `remember(state.active.input)` 会在键盘插入字符后重建 `TextFieldValue`，而 `TextFieldValue(String)` 默认 selection=0 → **光标跳回行首，字符插到最前面**。而按 ← 不触发（文本没变）→ 只在插入类按键出现，极易误判成插入位置算错。修法：key 改 `state.active.id`。

## 五、其他技术要点
- **TextFieldValue 而非 String**：`String` 没有光标概念，←→ HOME END 无法实现；且 TextFieldValue 带 `composition`（拼音组合态），自绘 AnnotatedString 会打断中文输入。
- **按键后 `focusRequester.requestFocus()`**：键帽是 clickable 会抢焦点，不交回则每按一键都要重新点输入框。
- **`onInputChange` 只在文本真变时回调**：光标移动也走 onValueChange，每次都回调会让 VM 白做一次 publish。
- **键盘放 Column 内（不是浮层）**：会真压缩输出区，用户能同时看到键与最后几行输出。与 `imePadding` 叠加是正确行为。
- **`TerminalIconChip` 独立于 `TerminalActionChip`**：前者 enabled（能不能点）、后者 active（是否开启），合并会变成 2×2 四种含义。

## 六、校验状态
6 文件括号全 OK。单测 22 例的关键断言用 Python 独立复算过（wordLeft/wordRight/historyStep 边界）。**构建禁令仍生效，未跑 gradle**；`./gradlew :feature:terminal:test` 是解禁后第一个要跑的。

## 七、剩余三轮（待用户确认后开工）
2. 终端设置页补 4 分区（存储管理/共享 tmp/虚拟键盘定制/FTP），SSH 归属不动；
3. 环境页补软件包组（6 类可展开勾选），保留发行版下载控件；
4. `VirtualKeyboardCustomizationDialog` 的 miuix 版。

## 八、安装链路（源码层已闭合，实测资产）
Ubuntu `ubuntu-noble-arm64.tar.gz` 魔数 1f8b=gzip，3413 成员，`Ubuntu 24.04.3 LTS`，顶层无包装目录；Kali `kali-nethunter-minimal-arm64.tar.xz` 魔数 fd377a585a00=xz，34160 成员，`Kali GNU/Linux Rolling`，**顶层是 kali-arm64/**（靠 `flattenIfSingleTopDir` 展平）。proot 四个 .so 齐全。真机装的是旧包（106MB 裸 tar），重装即用新资产。

Source: ai_created

---

## ★★★ 2026-10-05：低版本玻璃的**架构性错误**与正确方向（读参考源码后定案）

# ★★★ 2026-10-05：低版本玻璃的**架构性错误**与正确方向（读参考源码后定案）

## 用户要求的方法（先做到这个，再谈技术）

> 「**不要盲目瞎猜多看看参考项目和文档**」

> 「你看看 web 端是怎么做的把代码扒下来，然后看」

已执行：clone `https://github.com/VincentAzz/LiquidGlassKMP-Web-Test`（130MB）到 `/root/lgweb`，内含：
- `AndroidLiquidGlass-kmp/`（kyant backdrop 完整源码，含 `backdrop/src/commonMain/...`）
- `miuix-main/`（miuix 源码副本）
- `docs/miuix|kyant/`（wasm 编译产物，可看网页效果）

## ★ 根本性发现：我整个「抓快照」架构是错的方向

### miuix / kyant 的真实做法（**完全不抓 bitmap**）

**miuix `LayerBackdropModifier.kt` 全文只有两行逻辑**：
```kotlin
override fun ContentDrawScope.draw() {
    drawContent()                                              // (1) 正常画内容
    recordLayer(backdrop.graphicsLayer) { backdrop.onDraw(this) }  // (2) 同步录进图层
}
```

**miuix `LayerRecorder.kt`**（关键：**显式传 size**）：
```kotlin
internal inline fun DrawScope.recordLayer(
    layer: GraphicsLayer,
    size: IntSize = this.size.toIntSize(),   // (*)
    crossinline block: DrawScope.() -> Unit,
) {
    val density = node.requireDensity()
    layer.record(size) { ... block() }        // (*) record(size) 这个重载
}
```

**kyant/miuix `LayerBackdrop.drawBackdrop`**：
```kotlin
val offset = layerCoordinates.localPositionOf(coordinates)  // 元素相对内容层的位移
withTransform({ translate(-offset.x, -offset.y) }) {
    drawLayer(graphicsLayer)     // (*) 把图层当绘制源直接用 —— 同帧、同步
}
```

⇒ **零 bitmap 拷贝、零节流、零异步**。玻璃在**同一个绘制帧**里直接取用图层。
这是它流畅的根本原因。

### 我的错误架构

```
每帧/每 32ms：toImageBitmap() 抓全屏 → 存 Bitmap → 渲染玻璃 → 贴回
```

三个致命后果：
1. **卡顿**（全屏 GPU 合成 + 拷贝，1280×2772 ≈ 14MB / 帧）
2. **滞后**（采样节流，内容滚动时玻璃慢一拍）
3. **快照失败**（`toImageBitmap()` 是 suspend 且要求图层已有内容，
   与采样泵（独立协程）**两套时钟** ⇒ 日志刷了 **2284 条**
   `背景快照失败：width & height must be > 0`）

### 正确方向（低版本也适用）

低版本没有 GPU 纹理采样，但可以：
- **不再抓 bitmap**，而是把 **`GraphicsLayer` 的底层 `RenderNode`** 或在 draw 阶段
  **同步**把内容画进一个 **GL FBO 纹理**（`GLUtils.texImage2D` 或 FBO blit）
- 关键是**同帧同步**（在 `draw()` 里完成），而不是跨帧异步抓

⇒ **待做**（下一轮）：把 `LowGlassBackdrop.capture()` 这套异步抓图改成
「draw 阶段同步把内容层画进 GL 纹理」，彻底去掉 `toImageBitmap()`。

## 本轮已落地的修复

### 1. `record(size)` + `hasRecorded` 门控（修 2284 条快照失败）

`LowLayerBackdropNode.draw()`：
```kotlin
layer.record(size = IntSize(size.width.toInt().coerceAtLeast(1),
                            size.height.toInt().coerceAtLeast(1))) {
    this@draw.drawContent()
}
backdrop.markRecorded()
```
`capture()` 里 `if (!hasRecorded) return`（避免刷 0×0 错误日志）。

### 2. 开关采样源含自己（与底栏同源 bug）

**kyant 的正确结构**：
```kotlin
val trackBackdrop = rememberLayerBackdrop()
Box(Modifier.layerBackdrop(trackBackdrop)) { 轨道 }   // ← 只录轨道
Box { 滑块玻璃 }                                      // ← 玻璃在 layer 之外
```
**我的错误**：`lowLayerBackdrop` 挂在**最外层 Box（含轨道 + 滑块）**。

**修**：layer 移到轨道 Box 上。

### 3. 开关采样泵动态节流

`delay(64L)` → `msUntilNextCapture()`（交互 32ms / 静止 200ms）+ `setActive()`。

## 用户反馈的界面状态（截图，2026-10-05 09:35）

- **cloudflared 已正常工作**（用户确认：「cloudflare 正常了」）
- **底栏仍是均匀灰板**（当时快照全失败）
- **开关玻璃不对**（采样源含自己 + 未走渲染）
- 设置页截图显示：已选中「强制低版本管线（调试）」，底栏效果选「液态玻璃」

## 提交

- App `e9454a6`（cloudflared + 光源/σ/dualPeak 修正）
- App `2da9552`（快照 0×0 + 开关采样源）
- CI 双绿，装机验证：安装后**零错误日志**、无崩溃

## 待验收

1. 底栏灰板是否消失（有采样源后应能折射内容）
2. 开关玻璃是否正确
3. **下一轮必须做**：按上述「正确方向」去掉异步抓图（根治卡顿/滞后）

---

## suchat Phase 1 完成：app 侧 API 接入点铺设（数据层，编译通过）

# suchat Phase 1 完成：app 侧 API 接入点铺设（数据层，编译通过）

日期：2026-10-11。**用户指出顺序错误**：「先给 app 预埋 api 接入点，然后再写服务器」——完全正确，此前我在直接写服务器。已纠正。

## 调整后的正确顺序
1. **Phase 1：app 预埋接入点**（本次完成）—— 定义契约、补全网络层、建 Repository/映射层、页面接线
2. **Phase 2：按接入点写服务器** —— 服务器只是去满足已固定的契约
3. Phase 3：管理后台

## 关键发现：旧 app 根本没有接线的余地
| 事实 | 证据 |
|---|---|
| **零 ViewModel / 零 Repository / 零 StateFlow** | `grep ViewModel\|Repository\|StateFlow` 只命中 SampleData 自身 |
| `SuchatApiClient` **零调用** | 只有 1 处注释提到它（“与既有 SuchatApiClient 同源”） |
| `SessionStore` **零调用** | 只有 1 处文档提了一下 |
| `SampleData` 被 7 个页面直接 import | ChatsScreen/ChatDetailScreen/ContactsScreen/MeScreen/SecondaryScreens/MainActivity |
| ServerSetupScreen 写「登录/注册（占位：待接入服务端鉴权）」 | 预埋未接线 |
| SuchatWebSocket 只有 UnsupportedSuchatWebSocket | WS 也未接线 |

结论：接入点必须**从零建**，否则服务器写完了 app 也没法用。

## 本次交付（编译通过 BUILD SUCCESSFUL）

### 1. docs/suchat-api-contract.md（147 行）
两侧唯一依据。分五节：寻址与传输 / 客户端已实现的 9 个方法（**从代码逐行读出**的字段契约）/ 服务器额外提供（12 组）/ 管理端 API（30+）/ 约定（错误格式、id 前缀、时间、分页、布尔）。

### 2. 客户端方法补齐（SuchatApiClient.kt）
原有 9 个 → **40+ 个**。新增（全部沿用同一 get/post/put/patch 助手风格）：
- `getMe` / `updateMe`（PATCH 新增了 patch 助手）
- `searchUsers` / `listContacts`（一次返回 contacts+requests）/ `sendFriendRequest` / `resolveFriendRequest`
- `markConversationRead` / `updateMessage`（撤回）
- `listMoments` / `createMoment` / `toggleMomentLike` / `commentMoment`
- `listBottles` / `throwBottle` / `pickBottle`
- `listDevices` / `registerDevice`
- `listNotifications` / `markAllNotificationsRead`
- `getSettings` / `updateSettings` / `submitReport`
- `createUploadMetadata` / **`uploadContent`**（唯一非 JSON 请求，手写 HttpURLConnection + `setFixedLengthStreamingMode`）

### 3. RemoteModels.kt 新增 12 个 DTO
ContactsBundle / ContactDto / FriendRequestDto / MomentDto / MomentCommentDto / BottleDto / DeviceDto / NotificationDto / SettingsDto / UploadDto
+ SuchatApiClient 底部新增全部解码器（严格读必填、宽松读可选）。

### 4. RemoteMapper.kt（新增，141+31 行）—— 纯函数映射层
解决一个真问题：**UI 模型不是服务端字段的拷贝**。
- `avatarColor` 由 id 稳定派生（服务端不存颜色）
- `Chat.time` 是「已格式化的短文本」（今天 HH:mm / 昨天 / 星期X / M月d日），与 SampleData 观感一致
- `Message.isMine` 要相对当前用户计算
- `Contact.initial` 给索引栏分组
函数：colorFor / toChat / toContact / toMessage / contentTypeOf / toUiProfile / toFriendRequest / toMoment / initialOf / parseIsoMillis / formatRelative。

### 5. SuchatRepository.kt（新增 256 行）—— UI 唯一取数入口
```
       ┌─ previewMode=true → SampleData（无网也能看 UI）
页面 → ├─ 已连接+token     → SuchatApiClient
       └─ 请求失败         → 保留上次数据 + 暴露 error
```
- 6 个 StateFlow（me/chats/messages/contacts/friendRequests/moments）
- `loading`/`error` 状态（之前完全没有）
- 写操作用**乐观更新**（本地先改、再发请求、失败有 error）
- 统一错误处理：ApiError 优先取服务端 error 码

## 编译过程中修掉的 3 个真错误
1. `ContactsBundle.requests` 应为 `List<FriendRequestDto>`（写成了单数）
2. `RemoteMapper` 缺 FriendRequest/Moment/UiProfile 的 import
3. **`UserProfile` 同名冲突**：`data.model.UserProfile`（UI）与 `data.remote.UserProfile`（API）同名；RemoteMapper 在 `data.remote` 包内，同包类会盖住 import 的别名 → 已用 `toUiProfile(dto: UserProfile)`（指同包）+ `UiProfile`（别名指 UI）解决

---

## suchat UI 第二批完成（转场改 WeKit / 图片文件选择 / 进度条）+ 关键坑（2026-10-14）

# suchat UI 第二批完成（转场改 WeKit / 图片文件选择 / 进度条）+ 关键坑（2026-10-14）

## 一、本轮提交（均经 :app:compileDebugKotlin 编译通过）
- `f3aaad8` feat(nav)：转场改用 WeKit 的 NavTransition
- `c934fc3` refactor(settings)：存储页分类加 miuix LinearProgressIndicator
- `ca5edf4` feat(picker)：App 自带图片/文件选择系统（含 BreadcrumbBar 路径）
前三批：`3881cfd`（6 项 bug）/`21ac590`（TabRow+Snackbar）/`189b47e`（设置改 dropdown）/`aa81690`（搜索改 TextField）

## 二、转场：关键事实（重要，勿再重复踩坑）
**WeKit 的转场不是自绘 Compose 动画，而是 miuix-nav 的 `NavTransition` 实现：**
- `WeKit/app/src/main/java/dev/ujhhgtg/wekit/ui/animation/predictiveback/`
  - `WeKitNavTransition.kt`：`PageTransitionAnimation.MIUIX -> NavTransitions.MiuixDefault`；`AOSP -> AospNavTransition`
  - `AospNavTransition.kt`（263 行）+ `NavTransitionGeometry.kt`（snapScaleToPixelExtent / snapTranslationToPixelEdge）
  - `NavTransitionEasing.kt`（FastOutExtraSlowIn / BackGestureEasing / topProgress / coverProgress）
- WeKit 通过 `NavDisplay(transition = weKitNavTransition(...))` 使用（WeKit 有大量 NavDisplay 用法）

**suchat 的落地方式（未重写导航栈）：**
移植到 `core/nav/transition/`：NavTransitionEasing.kt / AospNavTransition.kt（1:1 数值）/ SuchatNavTransition.kt（门面+风格枚举）/ SuchatNavTransitionScope.kt（适配器）。
`PageLayerHost` 删除自写三分支位移，改 `with(navTransition) { Modifier.transformEntry(scope) }`。

**必须记住的 API 事实：**
- `NavTransition.transformEntry` 是**扩展函数** `fun Modifier.transformEntry(scope): Modifier`；
  调用必须 `with(navTransition) { Modifier.transformEntry(scope) }`，不能 `navTransition.transformEntry(...)`。
- `transformEntry` 返回 Modifier（内部 graphicsLayer），**不能在 graphicsLayer{} 内调用**，
  必须在组合期调用并 `.then()` 链到 Box 上。
- miuix-nav 公开包：`top.yukonga.miuix.kmp.nav.transition.*`（NavTransition/NavRole/NavGesture/NavSettle/
  NavSettleSpec/NavSwipeEdge/NavMotion/navGraphicsTransition/navDirectionalTransition/NavTransitions）
  与 `top.yukonga.miuix.kmp.nav.runtime.NavChange`。NavTransitionScope 可自行实现（接口），
  但 LiveNavTransitionScope 是 internal，无法复用其 gesture/settle 增强。

## 三、依赖升级（重要）
- suchat 的 miuix 已由 **0.9.4-rc01 升到 0.9.4**（stable）。
- **坑：`libs.versions.toml` 里定义了 `miuix-nav` 但 app/build.gradle.kts 从未引用** →
  `top.yukonga.miuix.kmp.nav` 整个包不可见。已补 `implementation(libs.miuix.nav)`。
- 破坏性变更核查：suchat 原本零 miuix-nav 引用，升级零破坏（NavigationRail/NavDisplay 均未用）。
- 编译命令需**去掉 --offline**（0.9.4 需从 Maven Central 拉取）。

## 四、新页面：图片/文件选择
- `ui/page/picker/MediaPickerScreen.kt`、`FilePickerScreen.kt`
- SuchatPage 新增 `MediaPicker(purpose)` / `FilePicker(purpose)`，fromRouteKey 已同步反解；
  SecondaryHost 已分发；“+”菜单已加入口。
- **两个包名/类型坑：**
  1. `MediaAsset` / `FileAsset` 的真实包是 **`feature.media.model`**（不是 `feature.media`）
  2. `SelectionState` 是**普通类**（不是 Compose 快照状态）→ 用 `selectionTick` 自增触发重组；
     另注意局部变量名不要叫 `selection`（会与 `SelectionState.items` 等解析冲突），用 `selectionState`。

## 五、仍待做
- 卡片 Card tilt + 可点击卡片 sink 效果（miuix 无此组件名，需按规范自建）
- miuix 与旧 UI 交融处统一到 MiuixTheme 语义 token
- 各页顶部限位逐页核实
- push 到 LocalHub（本地领先 7 个提交）

## 六、用户的修正
- “图标选择” → 更正为**图片选择**
- 转场 → 不要自写，**用 WeKit 的**

---

## suchat：预埋 API 接线审计 + 全页面功能完善（2026-10-14）

# suchat：预埋 API 接线审计 + 全页面功能完善（2026-10-14）

## 用户要求
「检查预埋的 api 接线是否覆盖所有功能，比如改名等等。并且所有页面的功能都要完善，越完善越好」

## 一、审计方法（可复用）
**权威路由表出处**：`/root/suchat_server/src/routes/*.js`（**声明式数组** `{method, path, handler}`，
不是 `router.get(...)` 调用形式）。取法：
```bash
cd /root/suchat_server && for f in src/routes/*.js src/admin/routes.js; do
  grep -nE "method: '|path: '" "$f" | paste - - | \
    sed "s/.*method: '\([A-Z]*\).*path: '\([^']*\)'.*/\1 \2/"
done
```
用户端 39 条端点（另有 admin 35 条）。

**客户端缺口取法**：对每个 API 方法统计 UI 层调用次数：
`grep -rn "\.$m(" --include='*.kt' . | grep -v 'SuchatApiClient.kt' | wc -l`

## 二、审计结果（改动前）
33 个 API 客户端方法中 **21 个在 UI 层零调用**。
服务端有、客户端完全缺失的路由 13 条（logout/change-password/DELETE messages/
GET+PATCH conversations/DELETE contacts/DELETE moments/bottles throw-back/DELETE devices/
notifications read/uploads 系列）。

## 三、已完成的接线（3 个提交）

### cf14f3f：API 客户端 + 仓储层 + 改名
- **API 客户端补 13 个端点** + 新增私有 `deleteJson()` 助手（DELETE 走 HttpURLConnection）
- **仓储层补 6 个 StateFlow**（bottles/devices/notifications/uploads/appearance/settings）
  + 30 个方法（updateMe/logout/changePassword/recallMessage/deleteMessage/
  updateConversation/deleteContact/toggleMomentLike/commentMoment/deleteMoment/
  refreshBottles/throwBottle/pickBottle/throwBackBottle/devices 系列/notifications 系列/
  submitReport/uploadBytes/refreshUploads/deleteUpload）
- **ProfileScreen 改名落地**：原为只读静态列表（`me = SampleData.me`，三行纯文本）→
  数据源改 Repository + 「名字」行 ArrowPreference 可点 + OverlayDialog 改名

### 1bdb984：朋友圈 / 设备 / 漂流瓶
- MomentsScreen：只读→可交互（点赞 like / 评论 comments / 删除 DELETE moments）
- DevicesScreen：硬编码假数据→真实 GET /devices + DELETE /devices/{id}
- DriftBottleScreen：空实现→扔瓶 OverlayDialog / 捞瓶 pick / 扔回 throw-back
- ★ **补模型链路**：UI 模型 `Moment` 新增 `liked` 字段
  （服务端 MomentDto.liked 一直有，RemoteMapper.toMoment 漏映射）

### b598314：联系人 / 聊天 / 账号安全
- ContactDetailScreen：数据源改 Repository + 新增「删除好友」（DELETE /contacts/{userId}）
- ChatDetailScreen：长按菜单的撤回/删除**原来只改本地列表不调 API** →
  接 PATCH /messages/{id} 与 DELETE /messages/{id}
- AccountSecurityScreen：复制（剪贴板）/ 改密码（POST auth/change-password）由空实现→真实现

## 四、发现的模型层坑（重要）
- `MediaAsset`/`FileAsset` 真实包是 **`feature.media.model`**（不是 `feature.media`）
- `SelectionState` 是普通类（非 Compose 快照）→ 用 `selectionTick` 触发重组；
  局部变量名勿用 `selection`（与 `SelectionState.items` 解析冲突）
- `Moment` 无 `likeCount`（点赞是 `likes: String` 已格式化名字串）
- `Chat` 无 `pinned` 字段（置顶只下发服务端，不在本地模型）
- `Message` **有** `recalled` 字段（撤回可直接 copy）
- `UserProfile` 只有 name/suchatId/avatarColor/qrPayload（无 bio/avatarUrl）
- LocalClipboard 的 `setClipEntry` 是 suspend，需 `ClipEntry(ClipData...)`

## 五、仍待做
- 设置页的设置项与服务端 `me/settings` 同步（仓储已有 loadSettings/saveSettings，未接 UI）
- 通知页（服务端 notifications 系列已封装，UI 未接）
- 举报入口（submitReport 已封装，UI 未接）
- 图片选择→上传→发朋友圈配图链路（uploadBytes 已封装）
- 卡片 Card tilt + sink 效果（上轮遗留）
- 各页顶部限位核实
- push 到 LocalHub（本地领先 11 个提交）

---

## 事件：Praxis 四轮全做完（软件包组 + 键盘编辑器，未构建）

# 事件：Praxis 四轮全做完（软件包组 + 键盘编辑器，未构建）

## 一、本轮新增（4 新文件 + 6 改动）
| 文件 | 行数 | 内容 |
|---|---|---|
| `core/model/linux/LinuxPackageGroups.kt` | 161 | 新增。6 个包组 + `buildPackageInstallScript` |
| `core/model/linux/VirtualKeyboardLayoutCodec.kt` | 98 | 新增。键帽标签持久化/校验（2×7=14，分隔符 `\u0001`） |
| `core/model/test/…/LinuxPackageGroupsTest.kt` | 119 | 新增 11 例 |
| `core/model/test/…/VirtualKeyboardLayoutCodecTest.kt` | 163 | 新增 11 例 |
| `feature/settings/env/PackageGroupsCard.kt` | 223 | 新增。`ArrowPreference` 手风琴展开 + `CheckboxPreference` 勾选 |
| `feature/settings/env/TerminalStorageSection.kt` | 571 | 新增 `VirtualKeyboardEditorDialog`；`VirtualKeyboardCard` 改为自带弹窗状态 |
| `feature/settings/env/LinuxEnvScreen.kt` | 649 | 加 `onInstallPackages` 参数 + 软件包分区 |
| `feature/settings/env/LinuxEnvViewModel.kt` | 954 | 加 `installPackages` |
| `feature/settings/env/TerminalStorageRoute.kt` | 150 | 接 keyboardLabels 读写 |
| `feature/terminal/keyboard/TerminalKeyboardModel.kt` | 223 | 加 `terminalKeyLayoutOf(labels)` |
| `core/data/settings/SettingsRepository.kt` | 645 | 加 `virtualKeyboardLabels`/`set…`/`reset…` + `KEY_VIRTUAL_KEYBOARD_LABELS` |
| `app/ui/PraxisApp.kt` | 668 | 采 keyboardLabels + 传 `keyLayout` |

## 二、关键架构决定
1. **持久化模型放 `core:model`，不放 `core:data`**：因 `core:data` 依赖 `core:model`（反向就成 `core → feature` 分层反转）。
2. **键盘只存标签、不存枚举名**：行为由**下标**决定（`terminalKeyLayoutOf` 第 i 格恒取 `DEFAULT_FLAT[i].id`）。这样改标签不改变功能。
3. **分隔符用 `\u0001`（SOH）而不是逗号/竖线**：终端用户会打出 `|` 等字符；SOH 在 TextField 里打不出来。类型必为 String（`joinToString` 要 CharSequence，Char 不隐式转换）。
4. **勾选状态存 `rememberSaveable` 而非 DataStore**：勾选是一次性动作中间态，保留旧勾选反而易误装；与共享 tmp / 键帽标签（跨会话配置）性质不同。
5. **包组脚本保留 recommends**（与 `SshdScript` 的 `--no-install-recommends` 相反）：`python3` 不带 recommends 就没 pip/venv。
6. **SSH 不做进包组**：Praxis 的 SSH 是容器内起 sshd（有端口/用户/密码配置），已有专属开关。
7. **Rust 用 `rustc`+`cargo` 而非 `rustup`**：rustup 要联网拉 toolchain，proot 里偶发卡死。

## 三、脚本三个必须锁的点（都有单测）
- 缺 `DEBIAN_FRONTEND=noninteractive` → debconf 无 TTY 永久挂住；
- `set -e` 裸跟 `apt-get update` → 某源 404 时整次安装中止；正确是 `|| echo 告警`；
- 空选必须返回空串（否则空跑一遍显示「安装完成」）。

## 四、修掉的错（自己引入）
- 测试索引写错：`DEFAULT_LABELS` 索引 0/1 是 `ESC`、`/`，我原写成索引 1/2 导致「c\td」被期望成 `-`；已用 Python 复算后改正为 0/1。
- `onOpenKeyboardEditor` 全链替换为 `keyboardLabels`/`onSave`/`onReset`，旧参数零残留（已 grep 确认）。
- 删掉两个未用 import（`PackageGroupsCard.remember`、`TerminalStorageSection.SmallTitle`）。

## 五、校验状态
14 文件括号全 OK；新符号全闭环。**构建禁令仍生效，全程未跑 gradle**。
解禁后验证链：`:core:model:test`（22 例）→ `:core:linux:test`（15 例）→ `:feature:terminal:test`（22 例）→ `:feature:settings:compileDebugKotlin` → `:app:assembleDebug`。

## 六、四轮整体完成情况
1. 键盘（2×7，结构 1:1 Operit + 莫奈色）✅
2. 终端设置页存储管理（体积/清空/重置 + 共享 tmp）✅
3. 环境页软件包组（6 类可展开勾选 + 一键执行）✅ 发行版下载控件保留
4. 键盘编辑器弹窗（2×7 标签编辑 + 恢复默认）✅

Source: ai_created

---

## suchat：远端源码一致性核查 + 本地 CI run #2 全绿（2026-10-14）

# suchat：远端源码一致性核查 + 本地 CI run #2 全绿（2026-10-14）

## 一、结论：本地与远端**完全一致**（无需拉取，也不存在「旧一点」）

`sxd91.kdns.fr` 其实就是 **LocalHub 的公网隧道入口**（同一实例）：
- 仓库 id 相同：`r_7e87e612656aaec3`
- `GET /api/repos/sxd/suchat` 返回 `clone_url: /sxd/suchat.git`，`pushed_at = 2026-10-11T17:55:14Z`（正是上一次推送时刻）
- 手机/PC 均可用同一 token（ghp_ 开头那个，`Name=DEEPSEEK`，scope=`repo`）

**三处 HEAD 完全相等**：
| 位置 | HEAD |
|---|---|
| `/root/suchat_remote`（本地工作区） | `36c71442631d1ec3bd55161d054903db901c869d` |
| `git fetch https://sxd91.kdns.fr/sxd/suchat.git main` → FETCH_HEAD | 同上 |
| gitsrv bare `/root/gitsrv/repos/sxd91/suchat.git` | 同上 |

`git diff --stat HEAD FETCH_HEAD` → **空**（零差异）。

> 注意一个易误判信号：API 的 `updated_at`（2026-10-12T23:01Z）比 `pushed_at` 新一天。
> 实测证明**这不代表有新提交** —— 权威依据是 `git ls-remote`（只返回 `HEAD` 与 `refs/heads/main` 两个 ref，无额外分支/tag）。
> 教训：判断远端是否有新代码，用 `git ls-remote` / `git fetch` 比对，**不要看 API 的 updated_at**。

## 二、本机访问 sxd91.kdns.fr 的关键约束（下次直接用）
- DNS 只返回 **IPv6**：`fdfe:dcba:9876::91`（无 A 记录）→ 必须走 v6，且要清代理
- **必须 `--noproxy '*'`**（curl）与清空 `http_proxy` 等（git），否则 5 秒固定超时
- `192.168.1.80:3000`（内网 IPv4 入口）偶尔 `Empty reply from server`，而公网隧道基本稳定
- LocalHub 自带的 `/api/repos/:o/:r/commits` 与 `/branches` 返回 **空数组 `[]`**（未实现），别用它判断提交历史

## 三、本地 CI（gitsrv）run #2 —— 全绿
```
run #2  workflow=Android CI  event=workflow_dispatch  ref=refs/heads/main
sha=36c71442  started=2026-10-14T08:46:35Z  completed=2026-10-14T08:51:56Z  conclusion=success
  job lint     success   5m17s   （./gradlew --no-daemon :app:lintDebug --continue）
  job build    success   2m52s   （./gradlew --no-daemon assembleDebug）
  job release  skipped    job 级 if 不满足：startsWith(github.ref,'refs/tags/android-v')
  artifact 100005  suchat-android-debug-apk   45058615 B
  artifact 100006  suchat-android-lint-report  186665 B
```
lint 结果：**0 error / 31 warnings / 1 hint**（无 error 故任务 success）。
主要 warning 类别：GradleDependency、NewerVersionAvailable、AndroidGradlePluginVersion、
ObsoleteSdkInt、UnusedResources、UseKtx、ModifierParameter、MissingApplicationIcon、NotShrinkingResources。

## 四、paths 过滤的正确行为（重要）
`android-ci.yml` 的 `on.push.paths` 仅含 `apps/suchat_android/**` + `.github/workflows/android-ci.yml`。
最近提交 `36c7144` 只动了 `apps/suchat_server/**` → **push 触发会被 paths 过滤跳过（与 GitHub 语义一致，不是 bug）**。
要跑 CI 就用：`gitsrv ci <workflow>`（等价 workflow_dispatch）或 `bash /root/gitsrv/ci-detached.sh <wf> workflow_dispatch refs/heads/main`。

## 五、操作要点
- 长构建必须用 `setsid`（`ci-detached.sh` 已封装），否则工具会话结束会回收 runner 子进程
- 启动本地服务：`gitsrv daemon`（webui 8899 + runner）；查状态 `gitsrv status`
- 跑前先 `gitsrv sync` 把工作区同步进 bare 仓库并投递事件
- 数据卫生：`artifacts/<run>/` 下可能有旧编号残留（历史 bug 遗留），判断残留用「run JSON 是否引用该 artifact id」

---

## ★ status update（2026-09-26 晚）：**迁移已完成，本条不再是半成品**

# ★ status update（2026-09-26 晚）：**迁移已完成，本条不再是半成品**

## 已完成内容（提交 `4239b2f`，CI run #81 completed/success）
1. 新增 `core/navigation/Routes.kt`：`@Serializable sealed interface Route : NavKey` + 全部子类型（RouteHome/RouteApi/RouteRepl/RouteSettings/RouteSamples/RoutePk/RouteExercise/RouteLogin/RouteOldSimian/RouteScorePump/RouteAccount/RouteExam）。
2. 新增 `core/navigation/AppNavController.kt`：包 `NavBackStack`，方法名与 `NavHostController` **同名**（`navigate`/`popBackStack`）→ 16 处调用点逻辑不变；含 100ms pop 去重、`popBackStack<T>(inclusive)`、`backStackSize()`、`LocalAppNavController`。
3. 新增 `core/navigation/transition/AppNavTransition.kt`：`appNavTransition()`（AOSP 分支完整移植：FastOutExtraSlowIn 双段贝塞尔 / 96dp 漂移 / 预测返回跟手缩放 / 弹跳物理 / 像素吸附）+ `rememberAppNavEffects()`（设备圆角裁切 + scrim 0.5）。
4. `AppNavHost.kt` 重写为 miuix `NavDisplay`。
5. `MainActivity` 改为「**单一 NavDisplay + 根 entry 放 pager**」；删除旧 `PageTransitions.kt`。
6. 12 个 feature 文件把 `NavHostController` → `AppNavController`（脚本 `swap_nav_controller.py` 批量完成，未动逻辑）；`HomeScreen.QuickEntry.route` 由 `Any` → `Route`。
7. 发布版符号已核实存在（`miuix-nav.aar` 0.9.4-rc01）：`NavDirectionalTransition`/`NavTransitions`/`NavDisplayEffects`/`rememberNavBackStack`/`rememberNavSystemCornerRadius`/`NavSettleSpec`/`NavMotion`/`NavRole`/`NavSettlePhase`/`NavSwipeEdge`/`NavGesture`。
   ⚠️ 查 aar 用脚本 `/root/cx/aar_has.py`（不是 grep）；**文件全名是 `miuix-nav.aar`**，按 `miuix-nav-android-*` find 会落空。
8. 推送用 `git push origin main`（remote URL 内已含完整 token，**不要手写 token**，曾因截断失败）。

## ★ 底栏位置二次纠正（用户指出）
第一版我写成「底栏放 `NavDisplay` **外** + `zIndex` 切层 + 手动 `coveredProgress` 模拟视差」→ **错的**（时序与转场不同步、视差基准按自身宽度而非整层）。
**正确做法**（照参考项目 `FloatingBottomBar`）：**底栏放进根 entry 内容内**，与 `HorizontalPager` 是兄弟节点（`MainPager` 里 `Box { HorizontalPager(...); LiquidGlassTabBar(align BottomCenter) }`）→ 转场时由 `NavDisplay` **统一带动**，二级页压栈后自然被盖住。
已重写 `MainActivity.kt`（213 行，编译通过）：`AppShell`（TOTP 门禁 → `CompositionLocalProvider(LocalBottomBarInset)` → `AppNavHost(root = { MainPager(...) })`）；`MainPager`（Box 内 `HorizontalPager(.layerBackdrop(backdrop))` + `LiquidGlassTabBar`）。`TAB_BAR_HEIGHT = 56.dp`。
滚动限位语义纪律不变（内容区 padding **不抬高**，只抬滚动范围终点）。

## miuix-nav 硬约束（勿再踩）
- `NavDisplay` 内部自管预测性返回（`PredictiveBackHandlerWithSessions(enabled = backStack.size > 1)`）⇒ **只能有一个** NavDisplay；四个 Tab 各挂一个会互相抢手势 → 必须「单一 NavDisplay + 根 entry 放 pager」。
- `rememberNavBackStack<Route>(start)` 要求路由 `@Serializable` 且**显式给父类型**。
- `NavDisplay.content` DSL 必须为栈里出现的**每个 key** 提供渲染。
- `navGraphicsTransition` 的 `dismissDirection` 默认 `None`（侧滑返回 opt-in，需 `entry(swipeDismiss = NavSwipeDirection.LeftToRight)`）。

---

## suchat：底栏尺寸「变小」核查 + nav_bar_scale 移植（079bc79 / 214b20e）

# suchat：底栏尺寸「变小」核查 + nav_bar_scale 移植（079bc79 / 214b20e）

## ★ 用户反馈：「怎么感觉这个 tab 栏变小了」

## 一、核查结论：底栏自身参数**从未变过**

逐版本比对（源码级铁证）：

| 提交 | defaultMinSize | 图标 | 字号 | 行高 | 胶囊高 |
|---|---|---|---|---|---|
| `e489614`（初版）| 64dp | 22dp | 11sp | 14sp | 64dp |
| `74ce6b0` | 64dp | 22dp | 11sp | 14sp | 64dp |
| `8593f2a` | 64dp | 22dp | 11sp | 14sp | 64dp |
| `b5bf77c` | 64dp | 22dp | 11sp | 14sp | 64dp |
| `d4552b7` | 64dp | 22dp | 11sp | 14sp | 64dp |

⇒ **从初版至今，宽度/高度/图标/字号全部一致**。所以严格说「没变小」。

## 二、但与上游 WeKit 有两个真实差距（均已修）

### 差距 1：`defaultMinSize` 误用 64dp（应为 76dp）
- WeKit `FloatingBottomBar.kt:351` = **76.dp**
- 宽度链路：`Box(IntrinsicSize.Min)` → `Row` → 4×`Column(weight(1f), defaultMinSize(minWidth=X))`
- 总宽 = 4X + 2×4dp：**64dp ⇒ 264dp；76dp ⇒ 312dp**
- 提交 `079bc79` 修正

### 差距 2（更重要）：完全没移植 WeKit 的 `nav_bar_scale`
WeKit `ReplaceNavigationBar.kt` 有一套**整体缩放**：
- 设置项：`prefOption("nav_bar_scale", 100)`，范围 **50–150 %**（`MIN_BAR_SCALE=50`/`MAX_BAR_SCALE=150`，`BAR_SCALE_STEP` 分档）
- 实现（**:648-651**）：
  ```kotlin
  val scaledDensity = Density(baseDensity.density * barScale, baseDensity.fontScale)
  CompositionLocalProvider(LocalDensity provides scaledDensity) { FloatingBottomBar(...) }
  ```
- **为什么覆盖 LocalDensity 而不是 graphicsLayer scale**（WeKit 注释 + 我的注释均说明）：
  底栏内每个 dp/sp（高度/图标/胶囊/模糊半径/阴影）都按新尺寸**重新布局**，
  玻璃纹理保持清晰、触摸区与所见一致；直接 scale 已渲染位图会把玻璃糊掉。
- 提交 `214b20e` 移植：
  · 新增 `IntKey` 枚举（default/min/max）+ `SuchatSettings.int()/setInt()`
  · 设置 → 外观 → **「底栏大小」miuix Slider**（每 5% 一档），拖动即时生效
  · `MainTabs` 用 `CompositionLocalProvider(LocalDensity provides scaledDensity)` 包住底栏

## 三、为什么用户会感觉「小了」
设备实测：`wm size = 1280x2772`、**`Physical density: 520`**（≈2.923x）。
- 64dp × 2.923 = 187px/每 tab；四 tab + 内边距 = 264dp = 772px
- 占屏宽 772/1280 = **60%**（视觉上偏窄）
- 76dp 后 = 312dp = 912px = **71%**

## 四、像素测量方法（可复用，环境无 PIL）
`/tmp/pngscan.py` —— 纯 `zlib`+`struct` 手写的 PNG 解码（支持 filter 0-4）+ 指定行扫描。
`/tmp/barmax.py` —— 全高度扫描找最宽亮行（= 胶囊水平直径）；`/tmp/barmax.py <png> <y0> <y1>`
⚠️ **实测值会随页面内容浮动**（Chats 页 912px / 我的页 864px），因为胶囊宽 = max(下限, 内容)。
不是稳定基准，只能作参考。

## 五、教训
1. **「感觉变小」先查 Git 历史逐版本比对参数**，不要先猜。本轮用一条命令打了 5 个版本的尺寸快照，
   才发现「参数从未变过」—— 避免了又一次盲改。
2. **抄上游要抄全**：我抄了 `FloatingBottomBar` 的 UI 与玻璃效果，却漏了外层
   `ReplaceNavigationBar` 里的 `barScale` 整套机制 —— 它才是「大小」的直接旋钮。
   以后抄某个组件，必须把**调用方**也读完。
3. **`LocalDensity` 覆盖 vs `graphicsLayer` 缩放**：前者重新布局（清晰、触摸区准），
   后者拉伸位图（糊）。需要缩放一整组 dp/sp 时用前者。

---

## suchat 服务器重构完成：零依赖分层 + 完整管理后台（36c7144）

# suchat 服务器重构完成：零依赖分层 + 完整管理后台（36c7144）

日期：2026-10-11。承接用户「服务器太狗屎要重构」「管理后台越全越好」。

## 一、架构（零依赖：node:http + node:sqlite + node:crypto）
```
apps/suchat_server/
  src/core/    config crypto db schema http router validate auth（8 个基础设施）
  src/routes/  service auth conversations misc uploads（5 个业务模块）
  src/admin/   routes.js（30+ 管理端点）
  src/server.js（入口：装配 + 请求流程 + 优雅退出）
  public/admin/ index.html + app.js + app.css（8 视图原生前端）
  tests/schema.test.mjs（7/7 通过）
```

## 二、旧服务器硬伤 → 修复对照
| 项 | 旧版（146 行单文件） | 本版 |
|---|---|---|
| 结构 | 一行一路由 | core/routes/admin 分层 |
| 口令 | **明文** + `WHERE password=?` 比对 | scrypt(N=16384)+随机 salt，verifyPassword 常量时间比较 |
| 建表 | `IF NOT EXISTS`（老库缺列不报错） | `PRAGMA user_version` 迁移，当前 v3 |
| 依赖 | fastify/websocket/better-sqlite3/jsonwebtoken | 零依赖 |
| 会话 | 无（令牌不可吊销） | sessions 表 + revoke；401 统一踢回登录 |
| 管理员 | 单一写死账号 | admins 表 + admin/owner 角色 + 强制改口令 |

## 三、关键实现决策（有实测依据）
- **未读数不用计数表**：`未读 = created_at > 我的 last_read_at 且非我发的` 条数 → markRead 只推进 last_read_at，不会与实际不一致
- **direct 会话幂等**：建会话前查「成员集合相同且 kind=direct」，有则复用（旧版会无限堆积）
- **uploads storage_key 服务端生成**（`up_<uuid>.ext`）→ 杜绝路径穿越；读取校验 owner_id
- **health 5 字段全 string**：app 用 getString 解码，缺字段会直接抛异常，属硬契约
- **事务支持嵌套**：外层已 BEGIN 时改用 SAVEPOINT，避免 SQLite 报错
- **旧明文口令透明升级**：登录时发现 isLegacyPlaintext 且匹配成功 → 改写成 scrypt

## 四、管理后台能力（30+ 端点 / 8 视图）
登录/改口令、管理员 CRUD（owner）、dashboard、14 天时间序列、
用户（搜索/分页/详情/改资料/停用/重置口令/删除）、会话、消息检索与删除、
**全站广播**、动态、漂流瓶、举报处置、审计日志、存储占用+孤儿清理、
系统状态/设置/备份（VACUUM INTO）、CSV 导出（users/messages）。
前端：概览+趋势柱状图、用户、会话、消息、动态/瓶子、举报、审计、系统。
令牌存 sessionStorage 走 `X-Admin-Token`（避免浏览器 Basic 弹窗）；危险操作 confirm；表格 escapeHtml 防 XSS。

## 五、验证（真机实测）
- 启动 85 条路由，迁移 v3；/admin 与 app.js/app.css 均 200（MIME 正确）
- health/register/login 200；user 字段与 app 的 UserProfile 解码器逐字段一致
- 建会话→发消息→拉列表(items 包层)→已读→列表带 lastMessage 全通
- **24 个端点批量探测全部 200，零 500**
- 单测 7/7（迁移幂等、约束翻译、事务回滚、外键级联）

## 六、踩坑记录
1. auth.js 导出未定义的 `conflict` → SyntaxError
2. admin/routes.js 数组字面量未闭合 `}` → SyntaxError（用 python 脚本精确替换修好）
3. server.js 从 auth.js 导入 `verifyToken`（实际在 crypto.js）
4. **8787 端口被残留 socket 占用**：ps/ss 查不到但仍 EADDRINUSE（proot 下常见），临时用 8790 验证；部署前 `fuser -k 8787/tcp`
5. 内联 `node -e` 被 shell 引号破坏 → 改用测试文件
6. `node --test tests/`（不带文件名）会把目录当测试文件→伪失败；必须 `node --test --test-reporter=spec tests/` 或指定文件

## 七、状态
Phase 1（app 接入层 0a54d88）+ Phase 2/3（服务器+后台 36c7144）均已完成并验证。

---

## 事件：Praxis 侧边栏主内容联动 + 会话切换动画（未构建）

# 事件：Praxis 侧边栏主内容联动 + 会话切换动画（未构建）

## 一、用户诉求与本次范围
用户先要求「调研 HyperCeiler 引导页 UI + 会话切换加淡入淡出 + 会话管理侧边栏 1:1 对齐 WeKit（内容不 1:1）+ 先挖差异再改」。逆向高手出差异表后，miuix 否决/修正 4 条，用户「开工」→ 本轮只落地 C9（侧边栏）+ B8（切换动画）。

## 二、被修正的 4 条（重要，避免再犯）
1. 侧边栏阈值：0.5 → 应为 **0.38**（非 0.62）。两边 progress 语义相同（0=收、1=开），取反的是比较符不是数值。
2. 入场编排只在整页首帧播一次，不能每翻一页播 1.7s。
3. animTime ping-pong 不做：Praxis 低版本是 Kotlin 侧 sin(t*0.1)，float 无退化；HyperCeiler 需要是因为 uTime 进 AGSL 被高倍频噪声放大。
4. 低版本降分辨率抄错目标：`AnimatedFluidBackground` 瓶颈是 4 个 Canvas 各挂 `Modifier.alpha()` → 4 个离屏 RenderNode，不是填充率。正解是 4 层合并成 1 个 Canvas（alpha 折进 brush 颜色）+ 限 30fps。

## 三、本轮落地（4 文件 + 1 建置）
- `ui/widgets/MiuixSidebar.kt`：`progress: Animatable<Float, AnimationVector1D>` 从参数传入（删掉内部 `remember{Animatable(0f)}`）；遮罩改 `sidebarEasedProgress(progress.value)`；新增公开 `sidebarEasedProgress(p) = 1-(1-p)^1.35`；阈值 0.38；方向判定加 `SIDEBAR_HORIZONTAL_DOMINANCE = 1.15f`。
- `app/ui/ChatSidebar.kt`：透传 `progress`。
- `app/ui/shell/MainShellPage.kt`：`sidebar: @Composable (Animatable<Float,AnimationVector1D>) -> Unit`；持有 `sidebarProgress`；内部改 `Box{ ①Box(graphicsLayer){PraxisPage(overlay=null)} ②sidebar(progress) }`；常量 SCALE_DROP=0.05 / SHIFT_X=7dp / SHIFT_Y=8dp / CORNER=28dp。
- `app/ui/PraxisApp.kt`：`AnimatedContent(targetState = sessionId to chatViewModel, contentKey = { it.first })`，fadeIn(220,delay40)+scaleIn(0.98) togetherWith fadeOut(140)，`using(SizeTransform(clip=false))`。
- `app/build.gradle.kts`：补 `implementation(libs.compose.animation)`（直接使用必显式声明）。

## 四、四个关键技术结论
1. **contentKey 是会话切换动画能用的前提**：token 到达改 state，不加 contentKey 会每 token 重起转场 = 一直闪。
2. **targetState 必须带 VM**：只带 sessionId 时退出分支读到的是新 VM，淡出的是新会话。VM 仍留在原 `viewModel(key=sessionId)` 不搬进 AnimatedContent。
3. **`using` 不是扩展函数**：已 javap 证实是 `AnimatedContentTransitionScope` 的成员（animation 1.12.0），所以 `import androidx.compose.animation.using` 是错的（会 Unresolved reference）。`togetherWith` 才是顶层扩展函数，需 import。`contentKey` 参数确实存在（Function1<? super S, Object>）。
4. **抽屉必须从 overlay 槽移到与 PraxisPage 同级**：否则外面套 graphicsLayer 会把抽屉自己也缩放；且 overlay 在 AppScaffold 内部，外部拿不到。

## 五、遗留风险
- `PraxisPage` 的 `modifier` 改为 `Modifier`（原来传 `modifier`），因外层 Box 已消费，避免 padding 双倍。
- 旧 VM 留存 ViewModelStore 是特性（后台流式），未加 LRU 清理。
- 构建禁令仍生效，未跑 gradle；仅静态校验（4 文件括号全配平 OK）。

Source: ai_created

---

## suchat 第四轮：双向线性联动 + 负一屏 1:1 拄 WeKit + 卡顿修复\n\n## ★ 状态：完成，CI 绿，装机验证（2026-10-02）\n\n提交链（均在 main，CI 全绿）：\n- `74ce6b0` 底栏↔页面…

# suchat 第四轮：双向线性联动 + 负一屏 1:1 拄 WeKit + 卡顿修复\n\n## ★ 状态：完成，CI 绿，装机验证（2026-10-02）\n\n提交链（均在 main，CI 全绿）：\n- `74ce6b0` 底栏↔页面双向联动 + 负一屏 1:1 对齐 WeKit\n- `62e1e44` 拖拽跟手改 CONFLATED 通道（消除每帧协程）\n- `30e5314` 消除双写入者冲突（卡顿主因）\n- `593b45e` externalFractionProvider 用 rememberUpdatedState 包装\n- `0c6e458` 页面滑动时玻璃改线性速度跟随\n- `d7e0169` 拖玻璃时页面也做线性位移\n\n## 一、负一屏 1:1 拄 WeKit（用户提供 https://github.com/Ujhhgtg/WeKit）\n\n源：`app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`（28 文件 10638 行）\n\n| 项 | WeKit 原值 |\n|---|---|\n| 抽屉宽 | `DRAWER_WIDTH_FRACTION = 0.84f`（屏宽 84%）|\n| 遮罩 | `DIM_MAX_ALPHA = 0.52f` |\n| 主内容 | `scale = 1 - 0.05*eased`、`translationX = 7dp*eased`、`translationY = 8dp*eased` |\n| 圆角 | `28dp * eased` |\n| 缓动 | `eased = 1 - (1-p)^1.35` |\n| 开合动画 | `duration = 120 + 120*|from-target|` ms + DecelerateInterpolator(1.4) |\n| 抽屉滑入 | `panelView.translationX = -drawerWidth * (1-p)` |\n| 手势 | 阈值 0.38、速度投影 160ms、touchSlop 8dp、纵/横 1.15 判定 |\n\n关键：面板**从左侧滑入盖在主内容上层**（非露出式）。\n\n## 二、双向线性联动（用户两次澄清）\n\n**用户原话**：\n1. 「特效玻璃消失的时机应该是松手的时候而不是切换页面的时候」\n2. 「手拖特效玻璃的时候页面就跟着特效玻璃进度平移。特效玻璃回位的时候页面也跟着回位，然后手滑页面的时候液态玻璃也跟着出现然后跟着页面滑动进度滑动然后回位」\n3. 「页面滑动做成线性速度，不然直接锁死跟随看起来不好看」\n4. 「玻璃滑动时界面也要线性移动」\n\n**最终实现（两侧对称的恒速逼近）**：\n- 拖玻璃 → 页面：`pageTargetFraction` + 每 8ms 推 0.048 页（6 页/秒）\n- 滑页面 → 玻璃：`followTarget` + 同一速度逼近\n- 两向都是**线性速度**，不是 snapTo 硬锁死\n\n**优先级**：`isGestureActive`（手指按住）优先于 `pageDragFraction`（页面手势）；两者期间外部同步均让位。\n\n## 三、卡顿修复（三个真因）\n\n1. **每帧新建协程**：三处 `scope.launch { snapTo/scroll }` → CONFLATED 通道 + 单消费协程\n2. **双写入者冲突**：滑页面时 `externalFractionProvider`（snapTo）与 `selectedIndexUpdated`（animateToValue spring）同时写 `valueAnimation` 互抢 → `pageDragFraction` 作让位信号\n3. **lambda 作 effect key**：`externalFractionProvider` 每重组新实例 → `rememberUpdatedState` 包装\n\n附带修正：`animateToValue` 不再无条件 `press()/release()`（拖拽中翻页会提前收玻璃特效）。\n\n实测：`dumpsys gfxinfo` Janky 1.12%（1972 帧 / 22 帧），内存 PSS 87-123MB。\n\n## 四、教训\n\n1. **「锁死跟随」与「跟手」是两个不同诉求**：手指直接拖动必须即时（snapTo）；间接驱动（页面↔玻璃）要线性速度。\n2. **异步写入后必须维护同步镜像值**：`WeKitDrawer.dragProgressSync`（否则读 `progress.value` 拿旧值丢步）。\n3. **Compose 性能三坑**：每帧 launch、双写入者、lambda 当 key。\n4. `input swipe` 合成事件在 Compose 自定义手势下大部分被吞（Total frames 仅 26），帧率实测不可靠；应以进程稳定性/PSS/无 ANR 为辅证。

---

## suchat：预埋 API 接线审计完整结果（2026-10-14，本轮共 13 个提交）

# suchat：预埋 API 接线审计完整结果（2026-10-14，本轮共 13 个提交）

## 权威路由表取法（可复用）
`/root/suchat_server/src/routes/*.js` 是**声明式数组** `{method, path, handler}`：
```bash
cd /root/suchat_server && for f in src/routes/*.js src/admin/routes.js; do
  grep -nE "method: '|path: '" "$f" | paste - - | \
    sed "s/.*method: '\([A-Z]*\).*path: '\([^']*\)'.*/\1 \2/"
done
```
用户端 39 条 + admin 35 条。

## 审计前后对比
**改动前**：33 个 API 客户端方法中 21 个在 UI 层零调用。
**改动后**：23 个仓储方法中 **19 个已接线**（✅），剩 4 个未接：
- `updateConversation`（群设置：改标题/免打扰/置顶）
- `refreshNotifications` / `markNotificationRead` / `markAllNotificationsRead`
  （无通知列表页；服务端端点已封装）
- `submitReport`（无举报入口）

## 本轮 13 个提交（eaca1d3 之后）
1. `3881cfd` 6 项点名 bug（开关/遮罩/限位/底栏取色/触感/展开动画）
2. `21ac590` 聊天页 TabRow + Snackbar
3. `189b47e` 设置选项改 dropdown
4. `aa81690` 搜索改 TextField
5. `f3aaad8` 转场改用 WeKit 的 NavTransition
6. `c934fc3` 进度条 ProgressIndicator
7. `ca5edf4` 图片/文件选择系统（BreadcrumbBar）
8. `1aaeffd` 旧 UI 清理（只剩 kyant 底栏与开关）
9. `cf14f3f` API 客户端补 13 端点 + 仓储补 30 方法 + 改名落地
10. `1bdb984` 朋友圈点赞评论删除 + 设备管理 + 漂流瓶
11. `b598314` 联系人删除 + 聊天撤回删除 + 改密码/复制
12. `67e9b64` 设置同步服务端 + 退出登录
13. `05971e7` 朋友圈发布动态 + 配图上传链路

## 重要发现（模型/架构层）
- **朋友圈此前「只能看不能发」**：createMoment 与整条 uploads 链路 UI 零调用；
  现已补「发表动态」入口 + 配图 uploadBytes 完整链路
- **退出登录此前完全没有入口**：现新增（POST /auth/logout + 清本地会话）
- **设置只落本机**：现 PUT /me/settings 同步
- UI 模型 `Moment` 原缺 `liked` 字段（服务端一直有，RemoteMapper 漏映射）→ 已补
- `SuchatRepository.createMoment` 原签名不含 uploadIds → 已加参数

## 关键架构决策（可复用）
**LocalSuchatLogout（CompositionLocal 下发全局动作）**：
登出入口在四级深层设置页，动作需作用在 MainActivity 的 session 状态。
穿透 SuchatAppShell → PageLayerHost → SecondaryHost 三层签名不划算，
改用与 LocalSuchatSettings/LocalSuchatSnackbar 同款模式：根层 provide 一次。
**后续类似「全局动作」都沿用这个模式。**

## 编译命令（已验证）
cd /root/suchat_remote/apps/suchat_android && \
  export ANDROID_HOME=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 && \
  export PATH=$JAVA_HOME/bin:$PATH && ./gradlew :app:compileDebugKotlin --console=plain
（不能加 --offline；需联网拉 miuix 0.9.4）

## 仍待做
- 通知列表页（服务端 notifications 三端点已封装未接 UI）
- 举报入口（submitReport 已封装未接）
- 群聊设置（updateConversation 已封装未接）
- 卡片 Card tilt + sink 效果（早期遗留）
- 各页顶部限位逐页核实
- **push 到 LocalHub**（本地领先 13 个提交）

---

## suchat 真已读回执 + 访问 IP/地理定位（2026-10-14 第三轮，提交 77c8ef5 + b005d1b）

# suchat 真已读回执 + 访问 IP/地理定位（2026-10-14 第三轮，提交 77c8ef5 + b005d1b）

## 任务：用户要求「继续做 + 添加已读功能 + 管理员能看到访问 IP + 接入查 IP 线下位置 的 API」

## 一、已读功能（关键发现：早已留坑但零实现）
`messages.read_by` 列在 schema v1→v2 就加了，但**零代码使用**；
客户端 ChatDetailScreen 用 `delay(1200)` 本地把「已读」假置真（与对方无关）。

实现：
- schema v4→v5：conversation_members 加 last_read_message_id
- core/auth.js: markConversationRead(convId, userId, upToMessageId) / readersOf(messageId)
  双口径：last_read_at（未读数）+ read_by（回执 JSON 数组）
- conversations.js: messageRow(row, selfId) 输出 readCount/readBy/readByMe；
  POST /conversations/{id}/read 支持 upToMessageId；新增 GET /messages/{id}/readers（仅发送者，否则 403）
- 客户端：Message DTO 加 readCount/readBy/readByMe；
  RemoteMapper.toMessage：自己发的看 readCount>0，收到的看 readByMe；
  ChatDetailScreen 删假定时器 → 上报 POST read + upToMessageId；长按菜单加「谁读了」

## 二、管理员可见访问 IP + 地理位置
- schema v4→v5：sessions 加 ip/user_agent/last_seen_at；新增 user_access_log + ip_geo_cache
- core/auth.js: recordSession 记 IP/UA；新增 touchAccess(user, req, path)
  （刷新会话 last_seen + 访问日志，同用户同 IP 每分钟最多一条）
- server.js 中间件：ctx.user 存在时 touchAccess（旁路）
- **新建 core/geoip.js**：默认 ip-api.com（零依赖用全局 fetch，中文 lang=zh-CN）
  · isPrivateIp 不外呼（127/10/192.168/172.16-31/169.254/::1/fc/fd/fe80）
  · lookupManyAsync 给列表用（命中即返，未命中返 null + 后台抓，**不阻塞**）
  · lookupNow/lookupBatchNow 同步（4s 超时）/ cacheStats；30 天 TTL
  · 可换源 SUCHAT_GEOIP_URL / SUCHAT_GEOIP_PROVIDER
- 5 个新 admin 路由（总 141→147）：security/access、security/user-ips、security/online、
  security/geo/lookup、security/geo/refresh
- 管理后台新增「访问IP」Tab（在线会话/用户位置概览/访问明细/单 IP 查询）

## 验证（三套冒烟脚本已入库 docs/）
- docs/smoke.mjs 功能 47/47、docs/readsmoke.mjs 32/32、docs/secsmoke.mjs 17/17
- 迁移 5 条 / 路由 147 条

## 重要坑（重复踩到要记牢）
1. **validate.js 的 `oneOf(obj,key,allowed,undefined)` 会抛 invalid_field**
   （实现要求 dflt!==undefined）——可选枚举字段必须手动判定。本次在 sessions kind 踩到。
2. **同一文件重复 import 同名符号会报错**，改代码时要合并 import（本次 auth.js/clientIp）。
3. **重写 handler 时易残留旧代码尾部**（markConversationRead 重写后 leftover 6 行），改完必须读回。
4. **多套 smoke 串行跑同一库会互扰**：前一套用掉的登录限流窗口会让后一套出现假失败
   （secsmoke 混跑 21/24，独立库跑 17/17）。验证时用独立 SUCHAT_DB_FILE。

## 待办（仍未做）
- 群设置 UI、卡片 tilt/sink、各页顶部限位核实
- 本地领先 26 个提交，未 push LocalHub

---

## suchat UI 修复进度（2026-10-14 三批提交，全部编译通过）

# suchat UI 修复进度（2026-10-14 三批提交，全部编译通过）

仓库 /root/suchat_remote，本轮 3 个提交，均经 `:app:compileDebugKotlin` 验证：

## 3881cfd fix(ui)：6 项点名 bug
1. 开关二次失效 → LiquidToggle 用 rememberUpdatedState 包 selected/onSelect +
   LaunchedEffect(Unit)（根因：DampedDragAnimation 由 remember 建一次，闭包捕获首帧 lambda）
2. 按钮只能按一次 → WeKitDrawer pointerInput(Unit) + 遮罩判 p 而非 eased + onOpen/onClose 用 rememberUpdatedState
3. PayCodeView 顶部限位 → verticalScroll + LocalTopBarInset
4. 底栏取色 → LiquidGlassTabBarDefaults 改 MiuixTheme.colorScheme.*；isInDark 由 miuix background.luminance()；LocalContentColor 换 miuix 的
5. 触感失效 → 新增 ui/component/SuchatHaptics.kt（ProvideSuchatHaptics 替换 LocalHapticFeedback），MainActivity 根部已包
6. 卡片展开无退出动画 → CardsScreen/HelpScreen FAQ 改 AnimatedVisibility(enter/exit 都配)

## 21ac590 feat(ui)：TabRow + Snackbar
7. ChatsScreen 顶部加 miuix TabRow（已读/未读/群聊/单聊），过滤真实生效
   （已读=unreadCount==0、未读>0、群聊=isGroup、单聊=!isGroup），空结果有提示
8. 新增 ui/component/SuchatSnackbar.kt（LocalSuchatSnackbar）；SuchatScaffold 每页持
   SnackbarHostState 挂 miuix Scaffold 的 snackbarHost 槽并下发；AboutScreen 复制优先走 Snackbar，缺宿主回退 Toast

## 189b47e refactor(settings)：设置选项改 dropdown
9. 新增 DropdownRow（包装 miuix OverlayDropdownPreference）；7 处 ChoiceRow+ChoiceSheet
   → DropdownRow（颜色来源/调色风格/色彩规范/深浅色/页面转场/玻璃渲染/性能档位）
10. 删 pickerKey 状态 + ChoiceSheet 定义 + ChoiceRow 定义 + OverlayBottomSheet import（净删 112 行）

## 仍未做（用户清单剩余）
- 搜索框改 TextField/InputField（当前 SearchScreen 在 FunctionalScreens.kt:115，已有 BasicTextField？需确认）
- 进度条改 ProgressIndicator（当前无 ProgressIndicator 用点）
- 卡片 Card tilt + 可点击卡片 sink 效果
- App 自带图标选择系统 + 文件选择系统（路径用 BreadcrumbBar）
- miuix 与旧 UI 交融处统一到 MiuixTheme 语义 token
- 页面进入/返回改 miuix-nav（NavDisplay/NavKey/NavTransitions），仍为自写转场
- 会话/消息等页顶部限位逐页核实

## 关键证据/环境
- 编译：cd /root/suchat_remote/apps/suchat_android && export ANDROID_HOME=/opt/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 PATH=$JAVA_HOME/bin:$PATH && ./gradlew :app:compileDebugKotlin --offline --console=plain（约 30-50s）
- 花括号自检：python3 /tmp/brace.py <file>
- miuix v0.9.4 API 核实源：/root/repos/miuix（已 checkout v0.9.4）
- 尚未 push 到 LocalHub（本地 3 个提交领先）

---

## 事件：Praxis 第9轮（UI 规范审计 + 首次构建成功 + 真机安装）

# 事件：Praxis 第9轮（UI 规范审计 + 首次构建成功 + 真机安装）

2026-10-24。构建禁令解除，首次对第5–9轮累积改动做真实编译。共 4 次构建。

## 一、编译暴露的 4 个真实错误（全是我前几轮引入）
1. `PraxisPermissionPage.kt:801` 用了 `Colors.onSurfaceVariant` —— miuix 没有这个字段，正确是 `onSurfaceVariantSummary`。
2. 同类错误共 6 处：`ChatSettingsBar.kt` 5 处 + `MessageBubble.kt` 1 处，已被第1次的编译覆盖不到（增量编译），sed 批量修正。
3. `MessageBubble.kt:277 CursorMessage` 的 `when(kind)` 缺 `BubbleKind.Summary` 分支 → 非穷举。（上一轮给 BubbleRole 加了 Summary 但漏了 Cursor 风格渲染）
4. `AiErrorKind.PROTOCOL` 不存在 → `AiException(AiError(kind=PROTOCOL))` 两处编译失败。修法：在 `AiErrorKind` 新增 `PROTOCOL` 枚举（与 INVALID_REQUEST 区分：前者是「服务端回来的东西不合协议」，后者是「发出去的东西不合协议」），并同步 `retryable`（PROTOCOL=false）与 `displayText`。

## 二、关键修正：Summary 不能再映射到 Assistant
`ChatScreen.kt:655` 原写 `BubbleRole.Summary -> BubbleKind.Assistant`，会让摘要在 Cursor 风格下走助手分支（无标签、全尺寸正文），显示与普通回复一致。已改为 `BubbleKind.Summary`，并在 `CursorMessage` 补 Summary 分支（标签「对话摘要」+ 正文 `onSurfaceVariantSummary`，不加边框，与气泡风格有意不同）。

## 三、16KB 提示确认消失
重建后 APK 内 7 个 .so 的 `p_align` 全部 `0x4000`，含 `libpty.so`（原 0x1000）。设备安装后实测同样全部 0x4000 → 进入 app 的提示根因已消除。

## 四、构建/安装结果
- `:app:assembleDebug` 成功，仅 2 条无害警告（`when` 多余 else、多 Kotlin daemon）。
- APK 255MB，路径 `app/build/outputs/apk/debug/app-debug.apk`。
- **坑：`pm install /sdcard/...` 会失败**（SELinux `avc denied u:r:system_server tclass=file fuse`），必须先 `cp` 到 `/data/local/tmp/` 再 `pm install`。
- 顺带清理了改名前的残留包 `sxd.praxis.app.debug`。
- 启动实测：`Status: ok`，`TotalTime: 965ms`，无 FATAL，`topResumedActivity=dev.praxis.debug/dev.praxis.MainActivity`。

## 五、UI 规范审计结论
- 莫奈取色：唯一色彩源是 `MiuixTheme.colorScheme`；`PraxisTheme.kt` 用 `ThemeController(colorSchemeMode=Monet*, keyColor=...)`，M3 色板由 `rememberM3FromMiuix` 直接照抄 miuix（不再自算）。`MaterialTheme.colorScheme` 直引仅 2 文件（主题桥接 + AppScaffold），可接受。
- 硬编码 `Color(0x…)` 共 29 处，逐个核对后全部属合理例外：ANSI 16 色表（终端协议色）、日志级别色、非动态色降级分支、设计性混色（AnimatedFluidBackground / About 的 BlendColorEntry）。
- 动画：二级页转场由导航层统一提供（`PraxisApp.kt:359 navTransitionFor` → `PraxisNavDisplay(transition=)` → miuix `NavDisplay`），设置类页面自身「零 animate」不是缺陷。`PraxisMotion`（Durations/Curves/Springs/Scales/Offsets）为统一动效语汇，仅 5 文件引用，后续可推广。

---

## 背景

# 背景
用户 2026-10-22：「对话的侧边栏去看看 suchat 的实现，我们的侧边栏 ui 有问题」。取证源：`/root/suchat_remote/apps/suchat_android/.../ui/component/WeKitDrawer.kt`（611 行，1:1 对齐 WeKit 上游 `HomeSidePanel.kt`）。

## suchat 侧边栏的真实规格（可复用）
| 项 | 值 | 出处 |
|---|---|---|
| 宽度 | 屏宽 × 0.84 | HomeSidePanel.kt:1551 |
| 遮罩 | 0.52 × eased，且 `p>0.001` 才画、`dimAlpha>0.01` 才拦点击 | :1552 |
| 主内容变换 | scale 1-0.05·eased、translationX 7dp、translationY 8dp、圆角 28dp·eased | :86-88 / :1331 |
| 缓动 | `eased = 1-(1-p)^1.35`；时长 `120+120·|Δ|` ms | :83 / :1049 |
| 松手判定 | `进度 + 速度×160ms/宽度 ≥ 0.38` | GestureState:9,12 |
| 手势识别器 key | 必须 `pointerInput(Unit)` + `rememberUpdatedState` | 它修过「按钮只能按一次」 |

注：suchat 的负一屏是「从左侧**向左划**」（左边再放一屏），方向与系统返回相反，所以不冲突；会话抽屉没有这个空间。

# 本轮已落盘（/root/Praxis，未构建）
全重写 `ui/widgets/.../MiuixSidebar.kt`（425 → 599 行）。

## 修掉的 5 个真缺陷
1. **状态栏压住标题**：`SidebarHeader` 只给 `top=20dp`，而 MainActivity 是 `enableEdgeToEdge()` → 现 header 吃 `WindowInsets.statusBars`。
2. **底部被手势条压**：内容区现吃 `navigationBars`。
3. **右滑一碰就关**：旧阈值是**裸像素** `60f`（3x 屏≈零20dp），且无速度投影 → 现改比例 0.5 + 速度投影 160ms。
4. **不跟手**：`AnimatedVisibility(slideIn/slideOut)` 两态 → 现 `Animatable` 进度跟手（`progress -= dx/width`）+ CONFLATED 通道写入（避免每帧新建协程抢 MutatorMutex）。
5. **关闭动画期吃货点击**：遮罩按 `visible` 拦 → 现按 `open && !dragging` 拦。

另：宽度改比例 0.84、上限 400dp（旧固定 300dp）；遮罩 0.32→0.52。

## 上游那行投影符号是错的（重要）
suchat/WeKit 写 `progress + velocity×160/width`，但它的 velocity 来自 `dx`，**左滑打开时 dx<0** → 越用力甩 projected 越小 → 越甩越达不到阈值（反作用）。Praxis 方向相反（右滑收起 dx>0），必须用 **减号** 才能让「甩一下更容易收起」。已在代码里写清。

## 抄写时的四个坑（全部已修）
1. `remember { derivedStateOf { open || ... } }` 直接捕获参数 `open` → 闭包拿首帧值，之后开关不生效。必须 `rememberUpdatedState(open)`。
2. `pointerInput(Unit)` 闭包捕获 `drawerWidthPx` → 转屏/折叠屏展开后仍用旧宽。只能 `rememberUpdatedState(drawerWidthPx)`，**不能**换 key（会打断拖拽）。
3. 通道消费端必须加 `if (dragging)` 守卫：松手后残留的 `snapTo` 会把刚起步的完成动画跳到终点（闪一下）。
4. `min(Dp, Dp)` 不存在于 kotlin.math → 用 `minOf`。

# 与 suchat 的故意差异
- **不做主内容缩放/位移/圆角**：Praxis 的抽屉挂在 `MainShellPage` 的 `overlay` 槽（在 layerBackdrop 采样源之外，避免玻璃采到自己），主内容不在它手里。要做需改 overlay 约定且与 Pager 横滑抢手势。
- **不做左缘右滑打开**：与系统返回手势完全冲突。打开只留顶栏按钮。

---

## 2026-10-21 记录。R37 实施中的硬事实与坑，均为本机/设备实测。

2026-10-21 记录。R37 实施中的硬事实与坑，均为本机/设备实测。

## 1. Android 上跑 proot 的三个硬约束
- **可执行位置**：Android 10+ 禁从 app 私有目录 exec（W^X）。唯一稳定可执行位是 `nativeLibraryDir`（实测 `-rwxr-xr-x`），且只有 `lib*.so` 会被放进去 → proot 必须改名 `libproot.so` 放 `jniLibs/arm64-v8a/`。
- **前提**：`useLegacyPackaging = true`（Praxis 已设），否则 native 库是 APK 内未压缩条目，不可执行。
- **proot 需补丁**：`/root/repos/WeKit/patches/proot/android-ptrace-events.patch` 是 Android ptrace 适配补丁，上游 proot 直编出来的二进制在 Android 上会挂。

## 2. 设备 toybox 能力边界（实测）
`toybox 0.8.13-android`：
- tar 支持 `-z`（gzip）/ `-J`（xz）/ `-j`（bzip2）/ `-o`（忽略 owner）。
- 有：`gzip kill ps sed sha256sum tar`。
- **无**：`wget curl awk`。→ 容器内下载必须靠容器里的 curl（apt 装的），不能用系统 toybox。

## 3. SSH 在 proot 里的三个必改项
- **端口 ≥ 1024**：普通应用无 `CAP_NET_BIND_SERVICE`，写 22 会让 sshd 直接退出（报 Bind failed），而用户只看到「开了连不上」。
- **`UsePAM no`**：proot 里 `pam_loginuid`/`pam_systemd` 写不了 `/proc/self/loginuid`，会把所有密码登录拒掉。
- **`sshd -D` + `-e`**：不加 `-D` 会 fork 到后台，进程句柄立即退出 → UI 永远显示已停止；不加 `-e` 日志走 syslog（容器里没有），报错完全看不到。

## 4. 换源的两个必做项
- **arm64 必须 `ubuntu-ports` 池**，用 `archive.ubuntu.com` 会报「找不到包」（`Skipping acquire ... binary-arm64/Packages`）。
- **deb822 与传统 sources.list 都要写**：24.04 读 `/etc/apt/sources.list.d/ubuntu.sources`，22.04 读 `/etc/apt/sources.list`。只写一个 = 「换源后 apt 还在连官方」。

## 5. ubuntu-base 下载 URL 三个陷阱
实测可用：`https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz`（200，29.8MB）。
- 路径是 `ubuntu-cdimage/ubuntu-base`（不是 `ubuntu-base/`）；
- 中间有 `releases/<version>/release/`（两个 release，一复一单）；
- 文件名补丁号 `24.04.3` ≠ version `24.04`，**不能凭 version 猜**，必须用候选列表逐 HEAD 探测。

## 6. FRP 的两个易错点
- frp 0.52+ **只认 toml**，旧 ini（`[common]` / `local_ip` / `remote_port`）会解析失败。
- 资源名架构是 `arm64`，前面已有 `linux_`：`frp_0.61.0_linux_arm64.tar.gz`。写成 `linux_arm64` 拼出 `linux_linux_arm64` 是 404。
- frpc 没有 `-s -p -t` 命令行参数，只收 `-c`。

## 7. Android 的 resolv.conf 是空的
DNS 由 netd 管，`/etc/resolv.conf` 通常不存在或空。把它 bind 进容器 = apt 报 `Temporary failure resolving`，而报错完全不提 DNS。必须在容器内写一份（用 223.5.5.5 / 119.29.29.29，不用 8.8.8.8）。

---

## suchat 代码审计：已定位的 bug 清单（2026-10-14，证据已核对）

# suchat 代码审计：已定位的 bug 清单（2026-10-14，证据已核对）

用户报 5 类现象，逐条在代码中定位（文件:行）：

## A. 顶部限位/不可滚动（付款码那个）
- PayCodeView（FunctionalScreens.kt:1365）纯 Column + padding(top=16.dp)：**不读 LocalTopBarInset、不可滚动** → 顶栏盖二维码、拖不动
- ChannelsScreen（:618）全屏黑，无返回键、无顶部限位、无 nav.pop
- ServicesScreen 的页内多状态（Home/PayCode/Wallet/Detail）**不走导航栈**，入/退无转场
- SuchatScaffold 下发 top=0.dp，全靠页面自己用 LocalTopBarInset

## B. 底部限位全站失效
- LocalBottomBarInset（SuchatScaffold.kt:80）**从未被 provide** → LocalScrollBottomLimit 恒 0
- MomentsScreen(:264) 用 bottomInset 自身作 contentPadding.bottom（自相矛盾）
- ChatDetailScreen 不接 bottomInset

## C. miuix 不规范
- SuchatSegmentedComponents.kt（176 行）**整个是 Material3**（MaterialTheme/Surface/Text/outlineVariant），仅 ServerSetupScreen 在用
- ServerSetupScreen 用 M3 Scaffold/Button/OutlinedTextField/TextButton；PreviewMediaPicker 用 M3 Button/Text
- 大量 “像素级” 重建（Divider 用 Box+1dp 背景，图标用文字 “›”）
- 硬编码 38dp 滑动条高度写在本项目（应属库）

## D. 进入/回退逻辑与动画
- 预测性返回有**两套并存**：PredictiveBackContent（死代码）+ PredictiveBackEffect（在用）
- BackHandler 只管抽屉，**抽屉打开时不注册 PredictiveBack**（手势不一致）
- ChannelsScreen 无返回入口
- 页内状态切换（ServicesScreen）无转场

## E. 按钮只能按一次（真凶）
- WeKitDrawer:178 pointerInput(drawerOpen){detectHorizontalDragGestures} 包住**整个主内容层**
  —— detect 会等待/消费水平指针事件；drawerOpen 变化会**重启** pointerInput
- WeKitDrawer:234-239 关闭遮罩 `if(eased>0.01f)`：关闭动画期间遮罩仍在，吃一次点击
- 收付款页「付款码/收款码」切换按钮是最大受害者（用户复现点）

## F. 其他隐患
- SuchatScaffold.kt:125 M3 Scaffold 作为骨架（与 miuix SmallTopAppBar 混用）
- MainActivity.kt:816 缩进异常（SuchatPage.SetHelp 行）
- 后端未接：Repository 只覆盖 me/chats/messages/contacts/moments；缺 appearance/settings/devices/notifications/uploads
- 服务器：/root/suchat_server（85 路由）与 /root/gitsrv/repos/... 不在一处，服务器需部署到公网才能被 app 连

---

## suchat 管理页面 + 安全性加固（2026-10-14，提交 546c1d5）

# suchat 管理页面 + 安全性加固（2026-10-14，提交 546c1d5）

## 背景
用户报「完蛋后端的管理页面和安全性」。fix 型 inspect-first 审计后发现：
**管理页面本身其实很完整**（index.html 156 + app.js 567 + app.css 170；8 个视图：概览/用户/会话/消息/动态瓶子/举报/审计/系统；admin 路由 35 条），
但**安全性有 6 个真缺口**（3 个中危以上）。

## 6 个缺口与修法
1) 【严重】限流零实现：config 定义了 rateLimitMax/loginRateLimitMax 但无代码消费。
   → 新建 src/core/ratelimit.js（固定窗口 + 惰性清理，无定时器）；server.js 最前置 enforce()；
   登录/注册最严、写次之、读最宽；429 带 Retry-After（http.js）。
2) 【严重】用户登出失效：register/login 从不 recordSession → revokeSession 吊销不到东西。
   → routes/auth.js 补 recordSession。实测 200 → logout{revoked:true} → 401。
3) 【中】trustProxy 默认 true → 伪造 XFF 可绕限流、污染审计 IP。→ 默认改 false。
4) 【中】CORS 反射任意 Origin（origin||'*'，无 Vary）。
   → 白名单：同源/publicUrl/本机；非白名单不下发 ACAO，预检 403。
5) 【中】GET / 未鉴权返回 configSummary()（含 dataDir/dbFile）。→ 移除；并加 CSP；
   静态文件改白名单扩展名 + 拒隐藏文件 + path.normalize/realpath 包含检查。
6) 【中】CSV 注入：csvEscape 不处理 = + - @ 前缀。→ 前置单引号。

## 新增能力
- 新文件 src/core/ratelimit.js（export consume/enforce/snapshot/reset）
- 3 个 admin 路由（总路由 138→141）：
  GET /api/admin/v1/security/status、GET /api/admin/v1/sessions、
  POST /api/admin/v1/sessions/revoke（仅 owner，写审计）
- 管理后台新增「安全」Tab：配置告警、限流窗口 Top IP、活跃会话表、强制下线
- 新增 config.production（SUCHAT_PRODUCTION=1）：缺 ADMIN_PASSWORD/JWT_SECRET 直接 exit 1
- 新增 config.publicUrl 参与 CORS 白名单

## 验证（可复用脚本）
- /tmp/smoke.mjs 功能回归 47/47
- /tmp/secsmoke.mjs 安全验收 17/17（信息泄露/CSP/CORS/路径穿越/限流/会话/CSV）
- /tmp/verify_prod.sh 生产模式两案（缺配置 exit=1；齐备正常启动）
- /tmp/verify_all.sh 一键跑两个 smoke

## 关键坑
- validate.js 的 `oneOf(obj,key,allowed,undefined)` 会抛 invalid_field（其实现要求 dflt!==undefined）——
  可选枚举字段必须手动判定。本次在 sessions 路由踩到（上次群成员 PATCH 也踩过）。
- 服务器无 git，改动同步到客户端仓 server-snapshot/（现含 src + public）。

---

## suchat 本地联调环境（2026-10-14 实测可用，重要）

# suchat 本地联调环境（2026-10-14 实测可用，重要）

## 一、suchat server 启动命令（本机）
```bash
cd /root/suchat_server && rm -rf data && mkdir -p data && \
SUCHAT_PORT=8791 SUCHAT_HOST=0.0.0.0 \
SUCHAT_JWT_SECRET='suchat-local-dev-secret-please-change-0123456789' \
SUCHAT_ADMIN_PASSWORD='admin12345' \
setsid nohup node src/server.js > /tmp/srv8791.log 2>&1 < /dev/null & disown
```
- **端口用 8791**：8787 被 Termux 的 `wb-proxy`（python3，`--port 8787`）占用，不是我们的服务
- 启动输出：85 路由、迁移 3 条、监听 0.0.0.0:8791
- 验证：`curl --noproxy '*' http://10.153.202.88:8791/api/v1/health` → 200；`/admin` → 200

## 二、本机网络事实
- 本机就是 Android 设备（`25053RT47C`，Android 17 / SDK 37，uid=0 KSU root）
- **局域网 IP = `10.153.202.88`**（proot 内 `ip addr` 看不到，需在 Android 侧 `super_admin:shell` 查）
- proot 里 `ip -4 addr` 拿不到 wlan0 地址 —— 必须用 Android 侧命令

## 三、adb 不可用，装机走 Android shell
- `/opt/android-sdk/platform-tools/adb` 是 **x86-64 二进制**，aarch64 上跑报 `Illegal instruction`
- 正确做法（本机即设备）：
```bash
# /sdcard 是 fuse，system_server 读不了 → 必须放 /data/local/tmp
cp /sdcard/Download/xxx.apk /data/local/tmp/ && chmod 644 /data/local/tmp/xxx.apk
pm install -r -d /data/local/tmp/xxx.apk   # → Success
am start -n io.github.sxd91.suchat/.MainActivity
```
- 典型报错：`avc: denied { read } ... /sdcard/Download/...` → 换成 `/data/local/tmp/` 即可

## 四、日志取证（不用截图，用户要求）
```bash
logcat -d -t 80 --pid=$(pidof io.github.sxd91.suchat)
ls -t /data/system/dropbox/data_app_crash@*.txt* | head -3   # 无输出 = 无崩溃
```

## 五、本轮构建/安装结果
- 本地 CI run #5：**success**（lint success / build success / release skipped）
- APK：`/root/gitsrv/artifacts/5/100009/.../app-debug.apk`，**45,582,903 字节**
- 已装到手机（`lastUpdateTime=2026-10-14 19:47:50`），启动无崩溃

---

## 文件：`ui/library/FloatingBottomBar.kt`（583 行）。来源注释明写改编自 compose-miuix-ui 示例 `IosLiquidGlassNavigationBar`（Apache 2.0）+ Ker…

文件：`ui/library/FloatingBottomBar.kt`（583 行）。来源注释明写改编自 compose-miuix-ui 示例 `IosLiquidGlassNavigationBar`（Apache 2.0）+ KernelSU 迁移。

# 完整签名
```kotlin
fun <T> FloatingBottomBar(
    items: List<T>, selectedIndex: () -> Int, onSelected: (Int) -> Unit,
    backdrop: Backdrop, modifier: Modifier = Modifier,
    mode: FloatingBottomBarMode = LiquidGlass,
    colors: FloatingBottomBarColors = FloatingBottomBarDefaults.colors(),
    iconContent: @Composable (T, Int) -> Unit,
    labelContent: @Composable (T, Int) -> Unit,
)
enum class FloatingBottomBarMode { LiquidGlass, Blur, None }
```

# 八个硬参数
| # | 值 | 行 |
|---|---|---|
| 1 | `pressedScale = 78f / 56f` ≈ 1.3929 | 254 |
| 2 | 每 tab `.defaultMinSize(minWidth = 76.dp)` | 317 |
| 3 | LiquidGlass 容器色 `.copy(0.4f)`，Blur 不透明 | 210 |
| 4 | 底栏底部 `12.dp + navigationBars` | MiuixSettingsPage:89 |
| 5 | 拖拽橡皮筋 `4.dp`，`EaseOut` 阻尼 | 222-232 |
| 6 | 停下回弹 `spring(1f, 300f, 0.5f)` | 269 |
| 7 | 阴影 `radius=10.dp`，alpha 暗0.2/亮0.1 | 403-410 |
| 8 | 光学链 `vibrancy() → blur(4dp) → lens(24dp)` | 417-430 |

# 三层 backdrop
```kotlin
val tabsBackdrop = rememberLayerBackdrop()                    // 底栏自己
val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
```
外部内容 backdrop + 底栏自身合成 → 底栏能折射自己下面的东西。

# 高光（视觉核心）
`iosIndicatorSpecular = Highlight(1.dp, alpha=1, BloomStroke(color=White@0.12, innerBlurRadius=2.dp, dualPeak=true, primaryLight=(0.5,-0.3,-0.05)@1.0, secondaryLight=(0.5,0.8,-0.5)@0.4))`
重力感应：`rememberDeviceTilt()` 取 gravityX/Y 归一化，阈值 `0.01f`（≈6°），基准点 `LIGHT_REF=(0.5, 0.7)`。
**同一高光两层不同角度**：底座 `extraDegrees=-45f`，药丸 `+90f`。

---

## ★★ 2026-10-05 第六轮：低版本玻璃**放弃 1:1 复刻，改降级处理**（App b0919ad，净删 587 行）

# ★★ 2026-10-05 第六轮：低版本玻璃**放弃 1:1 复刻，改降级处理**（App b0919ad，净删 587 行）

## 用户指令（逐字）

> 「算了低版本还是做降级处理吧，底栏就用毛玻璃，然后顶部 scaffold 用毛玻璃不用 blur」

直接后果：低版本管线（API 24–32）**放弃液态玻璃 1:1 复刻**（折射/高光/按压玻璃/渐变模糊全部下线），只保留「能看、流畅、不崩」的降级观感。此前 `2da9552`（快照 0×0 + 开关采样源修复）的方向就此终止，不再继续修。

## 本轮改动（5 文件，+195 / -782）

| 文件 | 改动 |
|---|---|
| **新增** `low/LowFrostedTopBar.kt` | 顶栏 = 静态半透明磨砂（surfaceColor α0.85 覆盖，**不采样、不模糊、无 overhang**）；唯一可调点 `TOPBAR_FROSTED_ALPHA` |
| `LowAppScaffold.kt` | 改用 `LowFrostedTopBar`；**删除采样泵 + 内容层录制**（二级页不再有后台采样链路）；`topBarInset` 不再减 overhang |
| `LowLiquidGlassTabBar.kt` | 底栏降级：LiquidGlass/Blur 档 → **毛玻璃**（`blur(25dp)` + 容器色 α0.65，无折射/无高光/饱和1.0）；None 档 → 容器色实心；指示器 → 半透明块（indicatorColor α0.15）；删除按压玻璃/色散/指示器玻璃与动态采样档 |
| `LowLiquidToggle.kt` | **整体重写**：降级为纯色滑块（轨道 `lerp(track, accent, f)` + 滑块 thumbSurface 实色胶囊 + 柔性阴影）；保留全部拖拽/弹簧/点击动画（`DampedDragAnimation` 逐字一致） |
| **删除** `low/LowBlurredTopBar.kt` | GPU 渐变模糊顶栏 + `TOP_BAR_BLUR_OVERHANG` 常量，均不再需要 |

## 关键对齐口径

- 底栏毛玻璃 = **高版本 `TabBarMode.Blur` 档同款**：`blur(25.dp) + onDrawSurface { drawRect(containerColor α0.65) }`，且效果链里**没有 vibrancy()**（故 GlassSpec.saturation = 1f）。
- 顶栏「毛玻璃不用 blur」= 静态半透明磨砂，**不接任何采样源**。
- 开关签名与调用方保持不变（`LiquidToggle` 的低版本分流点无需改动）。
- 采样链路仍在的：主页面底栏（MainActivity 的 `LowCapturePump` + HorizontalPager 的 `lowLayerBackdrop`）——底栏毛玻璃仍需背景快照。二级页（LowAppScaffold）已完全无采样。

## 验证

- 本地 `:app:compileDebugKotlin` 通过
- 已推送 `b0919ad` → CI 待跑（组装验证后装机）

## 代码简化收益

低版本管线从 4640 行（low 目录）降到现在（删 -782 行）；二级页无后台协程；开关无采样泵（此前每个 40×24dp 开关挂一个全屏抓图泵——纯粹浪费）。

# 后续

- 装机验证：低版本管线（强制低版本）下底栏毛玻璃观感、顶部磨砂、开关纯色滑块
- 高版本（AUTO 默认）路径**一字未动**

---

## suchat 第六轮（用户三项待办，2026-10-02 启动）

# suchat 第六轮（用户三项待办，2026-10-02 启动）

## 基准
- HEAD `155988d`（tab 栏已回退到 e489614 纯展示版），本地/远端一致，工作区干净。
- 仓库 `/root/suchat_remote`，Android 工程 `apps/suchat_android`，包名 `io.github.sxd91.suchat`。
- 本机 aarch64：**只能 `:app:compileDebugKotlin` 静态验证，APK 打包走 CI**。

## 用户三项待办（原话）
> 「设置页面里面的功能都没有写而且二级页面进入三级没有动画，还有预测性返回也没做」

## 真因（已读代码确认）
1. **设置功能空壳**：`SecondaryScreens.kt` 的 `SettingsScreen` 里 7 个 `SuchatEntryRow` 的 `onClick = {}` 全空。
2. **二→三级无动画**：`MainActivity` 的二级层用 `AnimatedVisibility(visible = topPage != null)` —— 只在 null↔非 null 边界播动画；二级→三级时 `topPage` 只是**换了个对象**，`visible` 始终 true → **瞬时切换无动画**。
3. **预测性返回未接**：`AndroidManifest` 已声明 `enableOnBackInvokedCallback="true"`，但代码层只有 `BackHandler`，没有 `PredictiveBackHandler`。

## 方案（本轮实施）
- **索引栏鱼眼**：联系人索引条字母跟随触摸点做邻近缩放（WaveSideBar 效果），删除原来的独立字母气泡浮层。
- **设置全实体化**：新增 `ui/page/settings/SettingsScreens.kt`（约 11 个真实子页）+ `SettingsPrefs.kt`（SharedPreferences 落盘）+ 开关状态页。
- **外观设置可热切**：`SuchatAppearance` 从数据类改**可观察类**（mutableStateOf + setter），主题/玻璃档/性能档/转场风格/减少动态全部实时生效。
- **三级页转场**：把页面的「层级」定义为静态属性 `SuchatPage.depth`（1=二级，2=三级）；二级层改**滞后渲染槽**方案（slots 列表 + AnimatedVisibility 进入/退出），底层页随深度差做 pushBack（左移 28% + 缩 6%），实现真·堆叠转场。
- **预测性返回**：新建 `ui/navigation/PredictiveBack.kt`，接 `PredictiveBackHandler`，支持「按住拖拽预览上一页 + 松手确认/取消回退」。

---

## 任务（2026-10-22 一次性抛出，真实执行任务）

# 任务（2026-10-22 一次性抛出，真实执行任务）
用户对 Praxis 一次列出 17 条缺陷：①Ubuntu 下载失败 ②控件几乎都不正规 + 二级页背景透明 ③权限管理无正经授权检测 ④UI 上一堆 bug ⑤长按 AI/用户消息没有菜单 ⑥聊天侧边栏一堆 bug（要求照 WeKit）⑦可展开的 button 点了不展开 ⑧全面扫描不规范 UI，照 InstallerX ⑨对话记录退出后丢失且不能切其他对话 ⑩丝滑滚动时机错（应新内容输出时滚到底，非切页）⑪主页切 tab 没有平移动画 ⑫终端环境配置与设置指向同一界面、设置页多出「高级」、入口重复 ⑬关于页非真照 InstallerX ⑭没有修改系统提示词的界面 ⑮工具数量与 Operit 不在一个量级 ⑯点击工具展开没有动画 ⑰没有思考过程痕迹。

协作铁律（用户确立）：**每轮只解决一项**，顺序固定「先读记忆 → 开工 → 再写记忆」。第 1 轮另明确「只用从代码层看就可以了，你还不熟悉手机怎么操作」。

## 进度（2026-10-22）
已推进 **1–11 轮**：
1 Ubuntu 下载失败 → 改 sealed DownloadOutcome + downloadAny 逐候选重试
2 二级页透明 → 页面根容器铺 surface（SettingsList 一处覆盖 7 页）
3 权限层 → 新建 PraxisCapability / CapabilityDetector / CapabilityRequester + manifest 补运行时权限
4 UI bug 扫描 → 新建 UiDiagnostics + 修 fillMaxSize 顺序、items 无 key
5 长按无菜单 → 弹窗错位，移进每条气泡自己的 Box
6 侧边栏 → 修手势方向优先、删误用 AutoFollowScrollLazy、新建会话先 ensureSession
7 点击不展开 → 链路正确，定位到 miuix popup 宿主缺失
8 全面扫描 → 根因：AppScaffold 用了 M3 Scaffold，零调用 MiuixPopupHost；换 miuix Scaffold
9 对话丢失/不能切 → 修 LaunchedEffect(Unit) key、restoreIfNeeded 整表覆盖、补 onCleared + drainPending
10 丝滑滚动时机 → 重写 AutoFollowScroll.kt（275 行），修 last==null 判底误判
11 tab 无平移动画 → switchDomain 改真 push/pop（miuix-nav 转场只由栈深驱动）

## 剩余 6 项未动
12（终端环境配置/设置指向同一界面 + 多出「高级」）、13（关于页）、14（无系统提示词界面）、15（工具数量）、16（工具展开无动画）、17（无思考过程痕迹）。

---

## 2026-10-20 记录。本项目已发生三次同类事故，本质都是「单测全绿但组件没人调」：

2026-10-20 记录。本项目已发生三次同类事故，本质都是「单测全绿但组件没人调」：

1. MemoryWritePipeline（R12）：DI 里注册了，从没被调用，界面能看库里永远空。
2. NavBackStack.ensureSeeded（R20）：有定义有单测，生产零调用，栈被清空时 NavDisplay 抛异常→白屏。
3. CompactionPolicy + 整层 kyant 液态玻璃 701 行（R21）：全是零调用。

## 为什么单测发现不了
单测拿被测函数当入口，天然验证不到「有没有生产调用点」。覆盖率也无效：未执行的代码不会拉低已覆盖的比例。

## 解法：tools/audit_unwired.py
静态引用扫描，判据是「名字在 src/main 里出现次数 <= 1（只有定义本身）」。关键细节：
- 必须先去掉注释（包括块注释），否则 KDoc 里提到一次就会被算成已接线。
- 只看 src/main，src/test 的引用不算生产接线。
- 只取列 0 起始的顶层声明（成员函数由所在类被调用来代表）。
- 白名单分两类：EXPECTED_EXTERNAL（Manifest/构建脚本引用）与 KNOWN_UNWIRED（已知但未接，必须写原因）。
- 双向校验：已知项若已不存在于未接线列表，脚本主动报「白名单过期」，防止白名单替现实撒谎。

## 另一个同类型陷阱：依赖存在 ≠ 已接线
P1 底栏玻璃被误标「已达成」，依据是「kyant backdrop 依赖在 build.gradle 里 + LocalKyantBackdrop 定义了」。实际上没有任何 CompositionLocalProvider 提供它，所有玻璃控件走纯色回退。
判断「某能力已实现」的标准必须是「有生产调用链」，不是「依赖和类型存在」。

## 本轮踩坑
1. `mutableIntStateOf` 读写要用 `.intValue`，否则 lint 报 AutoboxingStateValueProperty（读/写各一条）。
2. Python 插 import 后重排 import 块，会把 `package` 声明一起卷进排序范围导致重复/丢失 package 行。重排时必须先锁定 package 行。
3. 测试里比较 data class 用 `contains` 会因值相等而误命中：4 条文本相同的 ChatMessage，`out.contains(list[0])` 恒为 true，断言永远成立测不出东西。必须改用不同文本来区分。

---

## LocalHub 推送通道故障（2026-10-14 实测，重要）

# LocalHub 推送通道故障（2026-10-14 实测，重要）

## 现象
| 入口 | 结果 |
|---|---|
| 内网 `http://192.168.1.80:3000` | **6/6 全部 `code=000 t=5.00s`**（固定 5 秒超时，与上次同一症状：代理劫持），TCP 3000 是 OPEN 的 |
| 公网 `https://sxd91.kdns.fr` | API 正常（200/401），`git ls-remote` 正常（能读） |
| **公网 git push** | **git 报成功 `36c7144..fb197f0 main -> main`，但服务端 ref 没变** |

## 决定性证据（不是猜测）
1. push 后 `git ls-remote https://sxd91.kdns.fr/sxd/suchat.git` 仍返回 `36c71442631d...`（旧）
2. `GET /api/repos/sxd/suchat` 的 `pushed_at` 仍是 `2026-10-11T17:55:14Z`（上一次成功推送时刻）—— **没写入**
3. `updated_at` 也停在 `2026-10-12T23:01Z`
4. 本地 HEAD = `fb197f02894a...`，远端 = `36c71442...` → 差 5 个提交

## 结论
- 公网隧道**只能读不能写**（或写不落盘）；内网入口当前不可达。
- `push-lh.mjs` 脚本存在**验证盲点**：它用 `GET /api/repos` 的 `default_branch` 判定"完成"，
  而该字段推送后仍为 main → 误报成功。**正确做法是用 `git ls-remote` 比对 ref**（已实测）。

## 待办
等内网恢复后：`cd /root/suchat_remote && git push <内网url> main:main` 然后用 `git ls-remote` 复核。
本地 5 个未推送提交：1104fc8 / 76c280c / 68e0fba / 354717f / fb197f0。

---

## 用户指令（2026-10-05，逐字）

# 用户指令（2026-10-05，逐字）

> 「算了低版本还是做降级处理吧，底栏就用毛玻璃，然后顶部 scaffold 用毛玻璃不用 blur」

## 解读（对照项目既有语义）

- 低版本管线（API 24–32）**放弃 1:1 液态玻璃复刻**，不再修折射/高光/按压玻璃那一整套。
- **底栏**：降级为 `BottomBarMode.FROSTED`（毛玻璃）语义 —— **只模糊、不折射**（高版本 Blur 模式同款：`blur(25dp) + containerColor α0.65`，无高光、无 lens、普通半透明指示器）。
- **顶部 scaffold**：毛玻璃观感，**不做真实 blur**（去掉现有 GPU 渐变模糊层 + 背景采样），改为半透明磨砂覆盖层。
- 开关（LowLiquidToggle，此前用户也反馈「不对」）：按同一降级策略处理（毛玻璃化，去折射）。

## 背景

上一轮（2da9552）仍在修「快照 0×0 + 开关采样源」，用户验收后放弃复刻方向，改要求降级：低版本设备只要能看、流畅、不崩即可。

## 待做

1. `LowLiquidGlassTabBar`：非纯色模式一律渲染毛玻璃（blur+tint，无折射/高光）；保留手势/弹簧动画。
2. `LowBlurredTopBar` → 移除 GPU 渐变模糊与 backdrop 采样，改半透明磨砂覆盖（含下缘柔和过渡）。
3. `LowAppScaffold`：同步简化（去掉顶栏采样泵/录制，若底栏不再依赖本页 backdrop）。
4. `LowLiquidToggle`：降级为毛玻璃开关。
5. 编译（本地 `:app:compileDebugKotlin`）→ 提交推送 → CI → 装机（无崩溃）→ 请用户验收。

## 状态

进行中（2026-10-05 下午）。

---

## x	x	x	陷阱）` —— 构建命令、pm install 流程、三大参照源、PraxisMotion 令牌、UiDiagnostics、CASCADE 陷阱。

x	x	x	陷阱）` —— 构建命令、pm install 流程、三大参照源、PraxisMotion 令牌、UiDiagnostics、CASCADE 陷阱。
2. `[项目] Praxis 工程基线：/root/Praxis 为唯一在用工程（旧基线已过时）` —— 旧的 16 文件基线已作废；operit-next-v2 丢失教训。
3. `[项目] Praxis 17 项缺陷清单与逐轮修复进度（1–11 轮已完成）` —— 17 条原文 + 已修内容 + 剩 6 项。
4. `[约束] miuix-nav 转场只由栈深驱动：同级替换永不产生动画` —— 第 11 轮根因 + switchDomain 真 push/pop + 三处配套改动。
5. `事实：Praxis ui:navigation 落地 + miuix-nav 六个硬 API 约束`
6. `事实：Praxis AutoFollowScroll.kt 当前实现（第 10 轮重写，275 行）`
7. `架构约束：Praxis 跨模块类型定义位置 + miuix popup 宿主链`
8. `事实：miuix 0.9.4 组件签名速查（源码 /root/repos/miuix）`
9. `事实：Praxis 控件与设置页建设结论（R31–R34 合并：转场/弹层/权限诚实）`
10. `约束：Praxis 全项目统一 miuix 引擎（禁 material3）+ 巡检命令`
11. `[约束] Praxis 所有开关统一用 LiquidToggle（kyant 液态玻璃

---

## 用户原话（2026-10-14）：「阅读 skill 以后都按照它的来」→ 长期生效行为准则，优先级高于默认习惯。skill 包位置：/storage/emulated/0/Download/Operit/skills/skill/。五条准…

用户原话（2026-10-14）：「阅读 skill 以后都按照它的来」→ 长期生效行为准则，优先级高于默认习惯。skill 包位置：/storage/emulated/0/Download/Operit/skills/skill/。五条准则：1) 先搜开源，别从零造轮子：写新代码前先搜 GitHub/GitLab/PyPI/npm/HuggingFace；判断可用性（Star/维护/issue/License/匹配度）；能直接用→用，差一点→改装，都不合适才自己写并说明原因；不假装搜过、不编链接、说清来源；/root/repos 已有上游参考（WeKit、AndroidLiquidGlass、miuix、PiliPlus 等）。2) 改前先读、一次改全：动手前读文件、真写文件、定义/调用/配置/文档一起改、改完能跑、同文件最多修3次，第3次还不行就停下来重新分析。3) 省 token 少说废话：结论先行、去头去尾、只答所问、不重复、该长才长；列表优先；完整代码不省略号；涉及安全/风险/不可逆时必须说清楚。4) 记忆管理只记重点：只记核心目标/偏好习惯/定下不能再变的规矩/重要结论决策；上下文满了先压缩；要用再取；别记垃圾、及时更新。5) 防 AI 跑偏/防幻觉/别越改越乱：防幻觉（不确定就说不确定）、防目标漂移（每步对原始目标）、防串味、防过度修改（改动最小化）、防死循环讨好（卡三次就停）。

---

## 2026-10-23 用户报「打开 app 先白屏，然后闪一下莫奈取色才跟上」。只做取证，未改码（构建禁令仍在生效）。

2026-10-23 用户报「打开 app 先白屏，然后闪一下莫奈取色才跟上」。只做取证，未改码（构建禁令仍在生效）。

## 已定位根因（第一层）
`app/src/main/java/dev/praxis/ui/PraxisApp.kt` 里主题设置取自 `ThemeSettings` 的 Settings Flow 采集，写了 `initialValue = ThemeSettings()`，即**非莫奈的默认主题**。首帧先按默认配色绘制，真实设置（含莫奈 key color）异步抵达后才重组成莫奈配色，观感就是「白屏 → 闪一下」。

## 第二层（同一链路）
`core/data/.../settings/SettingsRepository.kt` 的读法为异步；`ui/theme/.../PraxisTheme.kt` 用 `MaterialTheme(colorScheme = m3Scheme)` 包裹 miuix，首帧与 m3Scheme 的派生时机绑定。

## 状态
仅完成定位与代码位置确认，未落地修复；修复方向（首帧同步取值 / 预置莫奈种子）尚未实施。

---

## 侧边栏对齐 WeKit 的确认口径：①关闭阈值取 0.38（WeKit HomeSidePanelGestureState:12 为 projected >= 0.38 → open，反向即 projected < 0.38 → close…

侧边栏对齐 WeKit 的确认口径：①关闭阈值取 0.38（WeKit HomeSidePanelGestureState:12 为 projected >= 0.38 → open，反向即 projected < 0.38 → close；此前「0.5→0.62」方向错误，会让抽屉更难关）；②联动落点在 MainShellPage 内部，不改 AppScaffold——内容层套 graphicsLayer（scale 1-0.05*eased、平移 7dp/8dp、圆角 28dp*e），抽屉作兄弟层画在最后，盖住底栏且不污染 layerBackdrop 采样；③Animatable 上提到 MainShellPage，通过 ChatSidebar(progress=…) 传入，签名只影响 PraxisApp 一个调用点；④遮罩用 0.52f * eased 与缩放同一条缓动；⑤visible 用 derivedStateOf 门控，关闭时不挂 graphicsLayer。eased = 1 - (1-p)^1.35。

---

## 用户下达 Praxis UI 三方对齐任务：①引导页 UI 骨架参照 HyperCeiler，内容沿用 Operit；②会话切换补淡入淡出动画；③会话管理侧边栏 1:1 对齐 WeKit（内容不 1:1）；并明确要求先挖掘对比差异、给出修改…

用户下达 Praxis UI 三方对齐任务：①引导页 UI 骨架参照 HyperCeiler，内容沿用 Operit；②会话切换补淡入淡出动画；③会话管理侧边栏 1:1 对齐 WeKit（内容不 1:1）；并明确要求先挖掘对比差异、给出修改方案，确认后再改。本轮取证：GitHub 恢复可达（github=200），已拉取 HyperCeiler（40MB，HEAD 5af3dae），其引导页实现在 library/provision 模块；本地 miuix 源码位于 /root/repos/miuix（v0.9.4），miuix 无 ChevronDown 图标；Praxis 的 AnimatedFluidBackground 在 :ui:widgets（四层径向渐变流光）；SettingsRepository 中已存在 onboardingCompleted / completeOnboarding() / ONBOARDING_VERSION。本轮未写代码、未跑 gradle。

---

## ★ 低版本玻璃第二轮（2026-10-05）：四个真 bug + cloudflared 路径 bug（162628e）

# ★ 低版本玻璃第二轮（2026-10-05）：四个真 bug + cloudflared 路径 bug（162628e）
上一轮（579d69d/45b19df）把玻璃从 CPU 换成 GPU 后，用户实测继续反馈，最终已修并 CI 绿（162628e）。

## 用户本轮澄清/要求
- 特效玻璃指**按压控件后出现的效果玻璃**（按压高亮/反馈），不是底栏常驻液态玻璃背景。
- 低版本管线应支持**非液态玻璃底栏效果**（降级/开关），除液态玻璃底栏外也要可用。
- 顶部 Scaffold 在低版本管线**必须渲染效果并 1:1 复刻**，此前未渲染。

## 本轮缺陷现象
- 渲染依旧卡顿。
- tab 栏背景上下颠倒（GL 坐标 Y 翻转处）。
- cloudflared 找不到/启动 EACCES。

## 处理
- 助手并行取证定位上下颠倒、顶栏未渲染、cloudflared 路径根因；修复后 CI 绿（162628e）。

---

## 本轮目标是跑通 Ubuntu/Kali 下载安装全链，并把终端设置页、环境配置页、键盘输入框 1:1 对齐 Operit，控件优先用 Miuix 并接入莫奈取色。代码取证确认：Praxis `TerminalViewModel.submit…

本轮目标是跑通 Ubuntu/Kali 下载安装全链，并把终端设置页、环境配置页、键盘输入框 1:1 对齐 Operit，控件优先用 Miuix 并接入莫奈取色。代码取证确认：Praxis `TerminalViewModel.submit` 是一次性进程模型（`runner.run(command)` 跑完即结束），底层无常驻 PTY，故 Operit「直连模式」无法 1:1 实现；Praxis 终端现有 ANSI 解析完备、渲染仅 16 色、缺 IME；Ubuntu 安装根因已定性为 tar 格式元数据不匹配（上一轮已修）；SSH 归属确认为环境页。Operit 虚拟键盘为 2 行×7 列（ESC、/、-、HOME、↑、END、PGUP；TAB、CTRL、ALT、←、↓、→、PGDN）。已确认 miuix 色阶、Surface 签名与 feature:terminal 依赖齐全，随后开始写第一轮代码。

---

## `ui/theme/src/main/java/dev/praxis/ui/theme/PraxisTheme.kt` 的结构是：外层套 `MaterialTheme(colorScheme = m3Scheme)`，内部再包 miuix …

`ui/theme/src/main/java/dev/praxis/ui/theme/PraxisTheme.kt` 的结构是：外层套 `MaterialTheme(colorScheme = m3Scheme)`，内部再包 miuix 主题。也就是说 Praxis 的「莫奈取色」同时影响 m3 配色与 miuix 语义色两条通道。

这带来一个已记录的影响：任何依赖 MaterialTheme 默认色（未显式接 m3Scheme/miuix 语义色）的控件，都会在主题切换/首帧阶段表现出「不跟莫奈」，此前的 `MaterialTheme.colorScheme` 直接引用扫描已作为排查手段使用。

---

## suchat Android 端侧边栏实现位于 `/root/suchat_remote/apps/suchat_android/app/src/main/java/io/github/sxd91/suchat/ui/component/W…

suchat Android 端侧边栏实现位于 `/root/suchat_remote/apps/suchat_android/app/src/main/java/io/github/sxd91/suchat/ui/component/WeKitDrawer.kt`，另有配套的 WeKitPanelContent；Praxis 侧边栏（MiuixSidebar）的跟手手势改造以此为对照实现。注意 Praxis 与 suchat 是两套独立工程，Praxis 侧边栏实际使用点为 app 的 ChatSidebar.kt 与 shell/MainShellPage.kt 的 overlay 槽。

---

## 本地 CI runner gitsrv 端到端走通：run #1 lint/build success、release skipped（if 不满足 refs/tags/android-v），artifact suchat-android-…

本地 CI runner gitsrv 端到端走通：run #1 lint/build success、release skipped（if 不满足 refs/tags/android-v），artifact suchat-android-debug-apk 44878391B、lint-report 181991B；Web UI 渲染 job 徽章、artifact 大小、跳过原因正确。修复两个 CLI bug：runs 的 head 缺 -n（改为 head -n），status 检测不到手动启动进程；清理两枚探针测试提交，历史收敛为干净提交。

---

## 用户指出 Praxis 对话页侧边栏 UI 有问题，要求对照 suchat 的实现来修。取证确认根因：`ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixSidebar.kt` 原本是「…

用户指出 Praxis 对话页侧边栏 UI 有问题，要求对照 suchat 的实现来修。取证确认根因：`ui/widgets/src/main/java/dev/praxis/ui/widgets/MiuixSidebar.kt` 原本是「`AnimatedVisibility` + 累计 60px 直接关闭」的开关式手势，不是跟手手势。本轮已删除旧文件并整体重写该组件，把 inset（statusBars/navigationBars）、遮罩、关闭阈值一并改为跟手逻辑，避免只改手势导致逻辑不自洽。

---

## 2026-10-14 审计 SuchatApiClient 共 33 个 API 方法，其中 21 个在 UI 层零调用（含 updateMe 改名、updateAppearance、toggleMomentLike、瓶子、设备、通知、设置…

2026-10-14 审计 SuchatApiClient 共 33 个 API 方法，其中 21 个在 UI 层零调用（含 updateMe 改名、updateAppearance、toggleMomentLike、瓶子、设备、通知、设置、举报、上传等），仅 healthCheck/register/login/listConversations/listMessages/sendMessage 等少数已接线；用户要求继续补全所有预埋 API 接线并完善所有页面功能，越完善越好。

---

## 用户反馈“现在应该正常了”后重测：SSH 到 A 机 3.148.103.19 已通（hostname ip-172-26-5-123）；sxd91.kdns.fr 的 HTTP/HTTPS 访问正常，但 API 写入 suchat 仓库仍…

用户反馈“现在应该正常了”后重测：SSH 到 A 机 3.148.103.19 已通（hostname ip-172-26-5-123）；sxd91.kdns.fr 的 HTTP/HTTPS 访问正常，但 API 写入 suchat 仓库仍返回 not a git repository。SSH 查服务端 /opt/localhub/data/repos/sxd/ 共 17 个仓库、缺少 suchat.git，确认为批量迁移时漏建该裸仓库。

---

## 用户纠正开发顺序：必须先给 app 预埋 API 接入点，再按接入点写服务器；旧服务器写过但质量差需重构，管理后台功能越全越好。现状确认：旧服务器 /root/gh-all/suchat-server-local 仅 146 行；app 侧…

用户纠正开发顺序：必须先给 app 预埋 API 接入点，再按接入点写服务器；旧服务器写过但质量差需重构，管理后台功能越全越好。现状确认：旧服务器 /root/gh-all/suchat-server-local 仅 146 行；app 侧 SuchatApiClient 零调用（死代码）、SampleData 被 7 个文件直接引用、无 ViewModel/Repository/StateFlow；需先建状态层接线再写服务器。

---

## ★ 低版本玻璃第二轮（2026-10-05）：四个真 bug + cloudflared 路径 bug（162628e）

# ★ 低版本玻璃第二轮（2026-10-05）：四个真 bug + cloudflared 路径 bug（162628e）
上一轮（579d69d/45b19df）把玻璃从 CPU 换成 GPU 后，用户实测继续反馈，最终已修并 CI 绿（162628e）。

## 用户本轮澄清/要求
- 特效玻璃指**按压控件后出现的效果玻璃**（按压高亮/反馈），不是底栏常驻液态玻璃背景。
- 低版本管线应支持**非液态玻璃底栏效果**（

---

## 用户要求继续完善 suchat：添加真正的已读功能；管理后台能查看用户访问 IP，并接入可查 IP 线下位置的 API。本轮侦察确认：服务端 messages.read_by 列已存在（v1→v2 已建）但零实现；客户端 isRead 由本…

用户要求继续完善 suchat：添加真正的已读功能；管理后台能查看用户访问 IP，并接入可查 IP 线下位置的 API。本轮侦察确认：服务端 messages.read_by 列已存在（v1→v2 已建）但零实现；客户端 isRead 由本地定时器伪造；sessions 表无 ip 字段；拟从 schema v4 升 v5 落地已读与 IP 采集链路。

---

## 用户要求 Praxis 终端设置页与环境配置页 1:1 对齐 Operit；环境页必须保留自有的 Linux 发行版下载控件；Operit 的键盘与输入框也要 1:1 对齐。控件优先用 Miuix，Miuix 缺失时才用 Operit 实现…

用户要求 Praxis 终端设置页与环境配置页 1:1 对齐 Operit；环境页必须保留自有的 Linux 发行版下载控件；Operit 的键盘与输入框也要 1:1 对齐。控件优先用 Miuix，Miuix 缺失时才用 Operit 实现；终端 UI 需接入莫奈取色。SSH 归属确认为环境页（放在容器里起 ssh）。

---

## 截至本轮侦察：suchat 服务端 sessions 表没有 ip 字段；管理后台无法查看用户访问 IP，目前只有 admin 操作写入 audit_logs。用户已要求补 IP 采集并在后台展示，同时接入可查 IP 线下位置的 API 服…

截至本轮侦察：suchat 服务端 sessions 表没有 ip 字段；管理后台无法查看用户访问 IP，目前只有 admin 操作写入 audit_logs。用户已要求补 IP 采集并在后台展示，同时接入可查 IP 线下位置的 API 服务。

---

## 截至本轮侦察：suchat 服务端 messages 表已含 read_by 列（v1→v2 迁移建立）但没有任何读写实现；App 端 Message.isRead 由本地定时器置真，并非真实的服务端已读回执。

截至本轮侦察：suchat 服务端 messages 表已含 read_by 列（v1→v2 迁移建立）但没有任何读写实现；App 端 Message.isRead 由本地定时器置真，并非真实的服务端已读回执。

---

## 同一条 LocalHub 迁移主线的后续：从单仓库 suchat 上传扩展到批量仓库上传、补 description/release，并确认 ngrok 失败后内网直连与 tag 逐个推送方案。

同一条 LocalHub 迁移主线的后续：从单仓库 suchat 上传扩展到批量仓库上传、补 description/release，并确认 ngrok 失败后内网直连与 tag 逐个推送方案。

---

## [项目] suchat 本地联调环境：server 起在 8791（8787 被 wb-proxy 占）+ 本机 IP 10.153.202.88 + 装机走 /data/local/tmp

[项目] suchat 本地联调环境：server 起在 8791（8787 被 wb-proxy 占）+ 本机 IP 10.153.202.88 + 装机走 /data/local/tmp

---

## 条 LocalHub 迁移主线的后续：从单仓库 suchat 上传扩展到批量仓库上传、补 description/release，并确认 ngrok 失败后内网直连与 tag 逐个推送方案。

条 LocalHub 迁移主线的后续：从单仓库 suchat 上传扩展到批量仓库上传、补 description/release，并确认 ngrok 失败后内网直连与 tag 逐个推送方案。

---

## [项目] LocalHub 上传通道打通：/root/suchat_remote → http://192.168.1.80:3000/sxd/suchat（含代理劫持排查与逐字节验证）

[项目] LocalHub 上传通道打通：/root/suchat_remote → http://192.168.1.80:3000/sxd/suchat（含代理劫持排查与逐字节验证）

---

## [项目] suchat 第八轮进展：面板穿透修复（PointerBlock）+ 索引栏紧凑化 + 颜色来源打通 + Kyant 依赖接入（27e189a/873da6d）

[项目] suchat 第八轮进展：面板穿透修复（PointerBlock）+ 索引栏紧凑化 + 颜色来源打通 + Kyant 依赖接入（27e189a/873da6d）

---

## [事故·教训] suchat 底栏闪退真因（BoxWithConstraints × IntrinsicSize 互斥）+ 我虚报装机验证的纪律纠正（863479c）

[事故·教训] suchat 底栏闪退真因（BoxWithConstraints × IntrinsicSize 互斥）+ 我虚报装机验证的纪律纠正（863479c）

---

## [项目] suchat 第八轮启动：6 项反馈（Kyant backdrop 接入 / tab 栏 1:1 / 融球 / 索引栏 / 面板穿透 / 外观设置）

[项目] suchat 第八轮启动：6 项反馈（Kyant backdrop 接入 / tab 栏 1:1 / 融球 / 索引栏 / 面板穿透 / 外观设置）

---

## [项目] suchat Phase 1 完成：app API 接入点铺设（契约文档 + 40 个客户端方法 + 映射层 + Repository，编译通过）

[项目] suchat Phase 1 完成：app API 接入点铺设（契约文档 + 40 个客户端方法 + 映射层 + Repository，编译通过）

---

## [项目] suchat：tab 栏「变小」核查（参数从未变）+ 补齐 WeKit nav_bar_scale 整体缩放（079bc79/214b20e）

[项目] suchat：tab 栏「变小」核查（参数从未变）+ 补齐 WeKit nav_bar_scale 整体缩放（079bc79/214b20e）

---

## [项目] LocalHub push 失败根因+修复：服务端 suchat.git 裸仓库缺失（已在 A 机重建，三处一致 7c37e0b）

[项目] LocalHub push 失败根因+修复：服务端 suchat.git 裸仓库缺失（已在 A 机重建，三处一致 7c37e0b）

---

## [突破] suchat 第八轮：底栏换 Kyant backdrop 库重写 + 苹果 HIG 校对（b93cc4e，CI 绿，真机验证）

[突破] suchat 第八轮：底栏换 Kyant backdrop 库重写 + 苹果 HIG 校对（b93cc4e，CI 绿，真机验证）

---

## [项目] suchat 导航重构+登录页重做+莫奈对齐WeKit（NavDisplay 驱动，schema v6，2026-10-15）

[项目] suchat 导航重构+登录页重做+莫奈对齐WeKit（NavDisplay 驱动，schema v6，2026-10-15）

---

## [项目] suchat 第三轮：7 bug 修复 + 模糊顶栏全应用 + 11 占位页实体化（8593f2a，CI 绿，真机验证）

[项目] suchat 第三轮：7 bug 修复 + 模糊顶栏全应用 + 11 占位页实体化（8593f2a，CI 绿，真机验证）

---

## [项目] suchat 第四轮：双向线性联动 + 负一屏 1:1 对齐 WeKit + 三处卡顿修复（d7e0169，CI 绿）

[项目] suchat 第四轮：双向线性联动 + 负一屏 1:1 对齐 WeKit + 三处卡顿修复（d7e0169，CI 绿）

---

## 项目/LocalHub 事件：推送通道报 not a git repository，根因是服务端缺 suchat.git 裸仓库

项目/LocalHub 事件：推送通道报 not a git repository，根因是服务端缺 suchat.git 裸仓库

---

## [项目] suchat 转场改 WeKit NavTransition + 图片/文件选择系统（2026-10-14 第二批）

[项目] suchat 转场改 WeKit NavTransition + 图片/文件选择系统（2026-10-14 第二批）

---

## [项目] suchat 服务器+客户端并行开工完成（契约冻结 + schema v4 + 51 API，2026-10-14）

[项目] suchat 服务器+客户端并行开工完成（契约冻结 + schema v4 + 51 API，2026-10-14）

---

## [突破] suchat 第四轮：底栏↔页面双向联动 + 负一屏 1:1 拄 WeKit（74ce6b0，CI 绿，真机验证）

[突破] suchat 第四轮：底栏↔页面双向联动 + 负一屏 1:1 拄 WeKit（74ce6b0，CI 绿，真机验证）

---

## [决策] 低版本玻璃改为降级处理：底栏=毛玻璃、顶部 scaffold=毛玻璃（不用 blur）——放弃 1:1 液态玻璃

[决策] 低版本玻璃改为降级处理：底栏=毛玻璃、顶部 scaffold=毛玻璃（不用 blur）——放弃 1:1 液态玻璃

---

## [项目] suchat 真已读回执 + 访问 IP 与地理定位（schema v5，路由 147，2026-10-14）

[项目] suchat 真已读回执 + 访问 IP 与地理定位（schema v5，路由 147，2026-10-14）

---

## [项目] suchat 服务器重构完成：零依赖分层 + 完整管理后台（36c7144，85 路由，24 端点全 200）

[项目] suchat 服务器重构完成：零依赖分层 + 完整管理后台（36c7144，85 路由，24 端点全 200）

---

## [突破] suchat 第七轮：液态玻璃「不对」双真因 = 缺重力感应高光 + AGSL 光斑被降级（d4552b7）

[突破] suchat 第七轮：液态玻璃「不对」双真因 = 缺重力感应高光 + AGSL 光斑被降级（d4552b7）

---

## [项目] suchat Phase 1 提交（0a54d88）+ Phase 2 服务器实现清单（逐条对应客户端契约）

[项目] suchat Phase 1 提交（0a54d88）+ Phase 2 服务器实现清单（逐条对应客户端契约）

---

## [项目] suchat UI 三批修复提交记录（3881cfd/21ac590/189b47e，2026-10-14）

[项目] suchat UI 三批修复提交记录（3881cfd/21ac590/189b47e，2026-10-14）

---

## [项目] suchat 预埋 API 接线审计 + 全页面功能完善（cf14f3f/1bdb984/b598314）

[项目] suchat 预埋 API 接线审计 + 全页面功能完善（cf14f3f/1bdb984/b598314）

---

## [项目] suchat 旧 UI 清理完成：只剩 kyant 底栏与开关（1aaeffd，2026-10-14）

[项目] suchat 旧 UI 清理完成：只剩 kyant 底栏与开关（1aaeffd，2026-10-14）

---

## [项目] suchat 管理页面审计 + 6 项安全加固（限流/登出/CORS/CSV，2026-10-14）

[项目] suchat 管理页面审计 + 6 项安全加固（限流/登出/CORS/CSV，2026-10-14）

---

## [项目] suchat 服务器重构：app 预埋 API 契约盘点 + 旧服务器诊断（146 行单文件待重构）

[项目] suchat 服务器重构：app 预埋 API 契约盘点 + 旧服务器诊断（146 行单文件待重构）

---

## [项目] suchat 代码审计：已定位的 bug 清单（顶部限位/底部限位/miuix/转场/按钮二次失效）

[项目] suchat 代码审计：已定位的 bug 清单（顶部限位/底部限位/miuix/转场/按钮二次失效）

---

## [逆向] 莫奈取色完整链：miuix流传keyColor就不读壁纸 + 9种调色板限制（2026-10-19）

[逆向] 莫奈取色完整链：miuix流传keyColor就不读壁纸 + 9种调色板限制（2026-10-19）

---

## [项目] suchat 预埋 API 接线审计完整结果（13 提交，19/23 已接，2026-10-14）

[项目] suchat 预埋 API 接线审计完整结果（13 提交，19/23 已接，2026-10-14）

---

## MiuixSidebar 的跟手手势重写以 suchat 的 WeKitDrawer 为对照实现取证后进行。

MiuixSidebar 的跟手手势重写以 suchat 的 WeKitDrawer 为对照实现取证后进行。

---

## 事件：Praxis 侧边栏重写——照 suchat WeKitDrawer 改跟手拖拽 + 修 5 个真缺陷

事件：Praxis 侧边栏重写——照 suchat WeKitDrawer 改跟手拖拽 + 修 5 个真缺陷

---

## [项目] suchat 第五轮：曲线速度 + 修抽动（反馈环）+ 动效热调面板（b5bf77c，CI 绿）

[项目] suchat 第五轮：曲线速度 + 修抽动（反馈环）+ 动效热调面板（b5bf77c，CI 绿）

---

## [项目] suchat 远端一致性核查（本地==kdns.fr，零差异）+ 本地 CI run #2 全绿

[项目] suchat 远端一致性核查（本地==kdns.fr，零差异）+ 本地 CI run #2 全绿

---

## [项目] suchat UI 第一批修复完成（6项点名bug）+ 第二批需求清单（2026-10-14）

[项目] suchat UI 第一批修复完成（6项点名bug）+ 第二批需求清单（2026-10-14）

---

## [约束] Praxis 所有开关统一用 LiquidToggle（kyant 液态玻璃）+ 莫奈语义色

[约束] Praxis 所有开关统一用 LiquidToggle（kyant 液态玻璃）+ 莫奈语义色

---

## [项目] suchat 第六轮启动：设置功能补全 + 三级页转场 + 预测性返回（计划）

[项目] suchat 第六轮启动：设置功能补全 + 三级页转场 + 预测性返回（计划）

---

## 事件：Praxis 终端虚拟键盘第一轮（1:1 Operit 结构 + 莫奈色，未构建）

事件：Praxis 终端虚拟键盘第一轮（1:1 Operit 结构 + 莫奈色，未构建）

---

## suchat 技术事实：messages.read_by 已有列但未实现，客户端为假已读

suchat 技术事实：messages.read_by 已有列但未实现，客户端为假已读

---

## 事实：suchat WeKitDrawer 是 Praxis 侧边栏手势的参考实现

事实：suchat WeKitDrawer 是 Praxis 侧边栏手势的参考实现

---

## suchat 技术事实：sessions 无 ip 字段，后台缺用户访问 IP

suchat 技术事实：sessions 无 ip 字段，后台缺用户访问 IP

---

## 事件：Praxis 启动白屏 + 莫奈取色闪烁（根因定位到主题首帧默认值）

事件：Praxis 启动白屏 + 莫奈取色闪烁（根因定位到主题首帧默认值）

---

## [suchat] 需求确认：真已读回执 + 管理后台用户 IP/地理定位

[suchat] 需求确认：真已读回执 + 管理后台用户 IP/地理定位

---

## Suchat 后端重构顺序确认：先 app API 接入点再写服务器

Suchat 后端重构顺序确认：先 app API 接入点再写服务器

---

## 决策：Praxis 会话侧边栏 1:1 对齐 WeKit 的落地参数

决策：Praxis 会话侧边栏 1:1 对齐 WeKit 的落地参数

---

## suchat API 接线审计：33 个预埋方法中 21 个零调用

suchat API 接线审计：33 个预埋方法中 21 个零调用

---

## Suchat 客户端-服务器契约文档建立（2026-10-11）

Suchat 客户端-服务器契约文档建立（2026-10-11）

---

## 本轮任务包含侧边栏对齐 WeKit，该决策为差异方案的核心部分

本轮任务包含侧边栏对齐 WeKit，该决策为差异方案的核心部分

---

## gitsrv CI 验证与后端重构同属 suchat 仓库推进

gitsrv CI 验证与后端重构同属 suchat 仓库推进

---
