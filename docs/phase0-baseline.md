# Praxis S0 基线硬化记录

本目录记录每一期「实施」的变更与验收口径，方便下一个接手的人不必读 diff。

---

## S0：工程基线硬化（2026-10-19）

### 目标
工程能构建、能装机、改动能回归。功能面（22 个功能块的真实页面）不在本期。

### 变更清单

| 编号 | 文件 | 变更 |
|---|---|---|
| S1 | `.gitignore` | 忽略 `.kotlin/`、`*.jks`、`*.keystore`、`keystore.properties` |
| S1 | `local.properties.example` | 新增：`sdk.dir` / `praxis.baseUrl` / `praxis.apiKey` / `PRAXIS_KEYSTORE` 模板 |
| S2 | `app/src/main/AndroidManifest.xml` | 加 INTERNET / ACCESS_NETWORK_STATE / FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC；`allowBackup=false`；挂两条备份规则 |
| S2 | `app/src/main/res/xml/data_extraction_rules.xml` | 新增：Android 12+ 全量排除备份与设备迁移 |
| S2 | `app/src/main/res/xml/backup_rules.xml` | 新增：Android 11 及以下全量排除 |
| S3 | `gradle/libs.versions.toml` | 加 `okhttp 4.12.0`、`junit 4.13.2`、`kotlinx-coroutines-test`、`okhttp-core` / `okhttp-logging` |
| S3 | `app/build.gradle.kts` | `buildConfigField` 注入 `DEFAULT_BASE_URL` / `API_KEY`（源自 local.properties → 环境变量 → fallback） |
| S3 | `app/src/main/java/dev/praxis/di/NetworkModule.kt` | 新增：OkHttpClient 单例（超时约定 + debug 挂 BASIC 日志） |
| S3 | `PraxisApplication.kt` | 装 `networkModule`；启动日志带上 baseUrl |
| S4 | `gradle/libs.versions.toml` | 删掉无引用的 `navigation3 = "1.0.0"` |
| S4 | `ui/navigation/PraxisRoute.kt` | 新增 `encodeRoute` / `decodeRoute` 纯函数 + `PraxisRouteSaver` |
| S4 | `ui/PraxisApp.kt` | 状态从 `currentTabId + detailId` 收敛为单个 `PraxisRoute`；接 `BackHandler` |
| S4 | `ui/features/FeatureHost.kt` | 入参改 `route: PraxisRoute` + `onBack`；子页加返回按钮 |
| S5 | `data/db/PraxisDatabase.kt` | `exportSchema = true` |
| S5 | `app/build.gradle.kts` | `ksp { arg("room.schemaLocation", ...) }` + androidTest 挂 schemas |
| S5 | `di/AppModule.kt` | 破坏性迁移只留 debug，release 严格 |
| S6 | `app/src/test/...` | 新增 4 个测试类、共 18 个纯 JVM 用例 |
| S7 | `domain/ai/AiError.kt` | 新增：8 类错误 + `retryable` + `fromHttpCode` |
| S7 | `domain/ai/CompactionPolicy.kt` | 新增：确定性压缩，`compactArguments` 恒等 |
| S8 | `app/build.gradle.kts` | `signingConfigs.release` 全走环境变量，缺料则不建配置 |
| S8 | `scripts/ci-release.sh` | 新增：release 构建 + apksigner 验签 |
| S9 | `scripts/ci.sh` | 新增：lint + 单测 + assembleDebug 并行 |
| S9 | `scripts/smoke.sh` | 新增：装机 + 启动 + logcat 崩溃判定 |
| S9 | `app/build.gradle.kts` | lint 基线「存在才启用」，首次由 CI 脚本用 `updateLintBaseline` 生成 |

### 与原始计划的偏差（及理由）

1. **`logging-interceptor` 用 `implementation` 而不是 `debugImplementation`**
   拦截器的挂载点在 main 源集（按 `BuildConfig.DEBUG` 决定是否 `add`），
   放 `debugImplementation` 会让 release 变体编译失败。日志级别固定 BASIC，
   不打请求体，避免用户会话内容进 logcat。

2. **`PraxisRouteSaver` 的第二个泛型参数写 `Any`**
   `listSaver` 的返回类型是 `Saver<Original, Any>`，`Saver` 未声明变型，
   写成 `Saver<PraxisRoute, List<String>>` 编译不过。

3. **`PraxisRouteCodecTest` 只测 `encodeRoute` / `decodeRoute`**
   原计划测 Saver 往返，但构造 `SaverScope` 属于框架行为；
   把编解码抽成纯函数后测试意图更明确，也不需要 Robolectric。

4. **lint 不开基线**
   现有告警都是 warning，`abortOnError = true` 只拦 error，开基线反而要额外对齐
   lint 的 `format` 版本。等真出现存量 error 再用 `updateLintBaseline` 生成并单独提交。

5. **`androidTest` 不挂 `app/schemas`**
   `getByName` / `maybeCreate` 指向一个尚不存在的目录只增加配置期风险；
   等第一个 `MigrationTest` 落地时再按 Room 官方模板挂。

6. **`fullBackupContent` 指向 `@xml/backup_rules` 而不是 `false`**
   两个备份规则文件都提供，覆盖 Android 11 与 12+ 两套机制。

7. **`localProp(key, envKey, fallback)` 三个参数**
   键名带点（`praxis.baseUrl`）没法在 shell 里 `export`，
   所以环境变量名单独给：`PRAXIS_BASE_URL` / `PRAXIS_API_KEY` / `PRAXIS_KEYSTORE`。

8. **签名配置用 `signingConfigs.create(...)` 的返回值直接持有**
   不做事后 `findByName`：口令缺失时容器里没有该条目，`findByName` 返回 null，
   而 `BuildType.signingConfig` 的赋值语义在 AGP 版本间变过。

9. **`PraxisCapabilities` 三个属性加 `@get:ChecksSdkIntAtLeast`**
   `PraxisTheme` 用 `if (PraxisCapabilities.dynamicColorSupported) dynamicDarkColorScheme(...)`
   包裹 API 31 调用。lint 的 `NewApi` 只认 `Build.VERSION.SDK_INT >=` 与带该注解的 API，
   不认自定义布尔属性 —— 没有注解时这是 **error**，配 `abortOnError = true` 直接红 CI。
   加注解是标准做法（androidx 自身也这么写），运行时行为不变。

10. **`data_extraction_rules.xml` 的 `exclude` 补 `path="."`**
    与 `backup_rules.xml` 写法对齐，避免不同 aapt2 版本对省略 `path` 的宽容度差异。

11. **`okhttp` 别名改为 `okhttp-core`**
    版本目录里 `okhttp` 同时作 version 与 library 名时，`libs.okhttp` 与
    `libs.okhttp.logging` 会形成「同名叶子 + 同名父节点」的 accessor 冲突隐患；
    改名为 `okhttp-core` 后访问路径是 `libs.okhttp.core`，无歧义。

### 验收命令

```bash
chmod +x scripts/*.sh
bash scripts/ci.sh                 # lint + test + assembleDebug
bash scripts/smoke.sh              # 装机冒烟（需要 adb 设备）
PRAXIS_KEYSTORE=/root/.keys/praxis-release.jks \
PRAXIS_STORE_PASS=*** PRAXIS_KEY_PASS=*** \
  bash scripts/ci-release.sh       # release + 验签
```

### 真实构建前的准备（必须做一次）

```bash
# 1) keystore（只放在仓库外）
mkdir -p /root/.keys
keytool -genkeypair -v -keystore /root/.keys/praxis-release.jks \
  -alias praxis -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PRAXIS_STORE_PASS" -keypass "$PRAXIS_KEY_PASS" \
  -dname "CN=Praxis, O=sxd91"

# 2) 新依赖（okhttp / junit / coroutines-test）首次需要联网；缓存里已有 4.12.0 与 4.13.2，
#    离线跑可用 PRAXIS_CI_OFFLINE=1 bash scripts/ci.sh
```

### 已知未验证项

- 本次改动由文件编辑完成，**尚未执行 Gradle 构建**；首次 `scripts/ci.sh`
  需要确认三点：`BuildConfig` 生成、Room schema 导出、JUnit 用例全绿。
- `scripts/*.sh` 需要先 `chmod +x`（编辑工具写出来的文件权限是 644）。
- lint 基线文件尚未生成，首次跑 CI 时由 `updateLintBaseline` 产出，产出后单独提交。

### 用例清单（18 条，全部纯 JVM）

| 测试类 | 条数 | 覆盖 |
|---|:-:|---|
| `FeatureBlockTest` | 5 | 每域非空 / id 全局唯一 / `blockOf` 命中与落空 / `byId` 往返 / 域 id 唯一 |
| `PraxisRouteCodecTest` | 5 | Home 往返 / Detail 往返 / 编码形状 / 未知域回落 CHAT / 损坏数据只降级 |
| `CompactionPolicyTest` | 5 | 预算内原样 / 首尾保留并标注省略量 / 工具阈值 / `argumentsJson` 恒等 / 代理对不切半 |
| `AiErrorTest` | 3 | HTTP 码归类 / `retryable` 表 / `retryAfterMillis` 只透传不估算 |
