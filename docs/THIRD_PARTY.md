# 第三方组件与协议

本项目自身以 MIT 协议发布（见根目录 [LICENSE](../LICENSE)）。
下面列出随仓库或随 APK 分发的第三方组件，以及各自的协议与来源。

本文件**不是**法律意见，只是为遵守各组件许可要求而做的归集。
若某个组件在此处描述与其实际许可不一致，以该组件官方声明为准。

---

## 一、随仓库内置的 Maven 仓库

`third_party/maven/` 与 `third_party/miuix/` 中存放的是 `fan.miuix:*:1.0.13.0` 一组 AAR。

| 项 | 内容 |
|---|---|
| 包名 | `fan.miuix:animation / appcompat / basewidget / bottomsheet / cardview / core / flexible / folme / graphics / navigator / nestedheader / pickerwidget / popupwidget / preference / recyclerview / slidingwidget / smooth / springback / stretchablewidget / theme / transition / viewpager / viewpager2` |
| 上游 | `maven.pkg.github.com/ReChronoRain/HyperCeiler` |
| 上游声明的可见性 | public |
| 为什么入库 | GitHub Packages 的 Maven 网关要求「对承载仓库有读权限」。包虽公开，非组织成员带有效 token 仍会被限流（表现为 429，且 `/rate_limit` 显示配额正常）。CI 没有凭证，远程解析必然失败，所以 AAR 直接入库 |
| 本项目用法 | Miuix Compose 设计体系（按钮、卡片、输入框、滑块、主题、动效） |

**这一组组件的协议以其上游仓库声明为准。** 本仓库只做二进制分发，未修改其代码。
若你是权利方并认为此处的分发方式不妥，请开 issue，会立刻移除。

---

## 二、Gradle / AndroidX / Kotlin 生态依赖

以下依赖全部通过版本目录（`gradle/libs.versions.toml`）声明，从官方仓库拉取，**不在本仓库内分发**：

| 类别 | 典型组件 | 协议 |
|---|---|---|
| AndroidX | `androidx.core`, `androidx.activity`, `androidx.lifecycle`, `androidx.navigation`, `androidx.datastore`, `androidx.room`, `androidx.compose.*` | Apache License 2.0 |
| Kotlin | `kotlin-stdlib`, `kotlinx-coroutines`, `kotlinx-serialization` | Apache License 2.0 |
| 构建工具 | Android Gradle Plugin, Gradle | Apache License 2.0 |
| HTTP | OkHttp, Okio | Apache License 2.0 |
| 图标 | `androidx.compose.material:material-icons-extended` | Apache License 2.0 |

AndroidX 与 Kotlin 生态的完整协议文本见各自仓库的 `LICENSE` 文件。

---

## 三、proot 与 Linux rootfs

| 项 | 说明 |
|---|---|
| `proot` 二进制 | GNU GPL v2。以**独立可执行文件**形式随 APK 分发，通过 `ProcessBuilder` 调用，不与本项目代码链接 |
| Ubuntu / Kali rootfs | 由 `scripts/fetch-rootfs.sh` 在**用户设备上按需下载**，不随 APK 打包、不入仓库。各自协议见发行方（Canonical / Offensive Security）的声明 |

GPL v2 的 `proot` 作为独立进程调用（mere aggregation），不使本项目整体受 GPL 约束。
若需要 `proot` 源码，见其上游仓库。

---

## 四、只在开发期使用、不分发的工具

| 项 | 用途 | 协议 |
|---|---|---|
| `tools/*.py` | 本仓库自写的静态检查脚本（括号平衡、实体转义、接线审计） | 同本项目 MIT |
| `scripts/*.sh` | 构建与 rootfs 拉取脚本 | 同本项目 MIT |

---

## 五、APK 内会出现的协议文本

`app/src/main/assets/licenses/` 下收集了需要随二进制分发的协议全文。
应用内「设置 → 关于 → 开源协议」会渲染这些文件。

若你新增了协议要求「随二进制附带声明」的依赖，请同时：
1. 把协议文本放进上述目录；
2. 在本文件加一行来源说明。
