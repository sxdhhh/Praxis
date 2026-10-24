package dev.praxis.ui.design.effect

/**
 * 背景渐变预设对应的设备形态。
 *
 * ## 为什么设备形态会影响配色
 *
 * 上游的 OS3 预设分 PHONE / PAD 两套「点位 + 颜色」。差别不只是尺寸：
 * 平板的视口更宽，同样的归一化点位会让光斑显得挤在中间，所以平板版把
 * 点位在水平方向拉开、并把颜色饱和度整体压低一点。
 *
 * ## 与本项目设备判定的差异
 *
 * 上游用 `LocalWindowLayoutInfo.current.showNavigationRail`（它自己有窗口逻辑层，
 * 要支持折叠屏 / 自由窗口）。Praxis 没有那一层，用最朴素的**最小宽度 600dp**
 * 判平板 —— 与 Android 官方的 `sw600dp` 资源限定符同一口径。
 * 折叠屏展开、自由窗口拉宽都会被正确识别成 PAD，因为判定依据是**当前宽度**，
 * 不是设备型号。
 */
enum class DeviceType {
    PHONE,
    PAD,
}
