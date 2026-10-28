package com.sevtinge.hyperceiler.provision.renderengine;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;

/**
 * {@code View.setRenderEffect} 的版本隔离层 + 低版本静态渐变兜底。
 *
 * <h2>为什么 setRenderEffect 也要隔离</h2>
 *
 * {@code View.setRenderEffect(RenderEffect)} 是 API 31 方法，
 * {@code RenderEffect} 是 API 31 类型。在 API 28 的 {@code View} 上
 * 根本没有这个方法 —— 直接调用会抛 {@code NoSuchMethodError}。
 * 这个错误发生在**调用点**（而不是类加载点），所以只要把调用
 * 关进 {@code @RequiresApi(31)} 的独立类，低版本就碰不到。
 *
 * <p>注意：光有 {@code if (SDK_INT >= 31)} 不够。ART 的方法解析发生在
 * 首次执行该字节码时，如果判断分支把调用写在同一个方法里，低版本
 * 走不到那一支也就不会解析 —— 严格说这样也能跑。但 Lint 的
 * {@code NewApi} 检查会报错，而本项目 {@code abortOnError = true}。
 * 抽成独立类是同时满足「运行时安全」与「编译期过 lint」的唯一写法。
 *
 * <h2>低版本兜底</h2>
 *
 * API 28–30 没有 {@code RenderEffect}，AGSL 光晕无法实现。
 * {@link #applyGlowFallback} 用径向渐变模拟「中心亮、边缘暗」的观感，
 * 让引导页不至于变成纯黑屏。视觉上与高版本有差距，但结构完整、
 * 不崩、不闪。
 */
public final class RenderCompat {

    /** {@code RenderEffect} 是否可用（API 31+）。 */
    public static final boolean HAS_EFFECT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

    /** AGSL {@code RuntimeShader} 是否可用（API 33+）。 */
    public static final boolean HAS_SHADER = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;

    private RenderCompat() {
    }

    /**
     * 给 View 设置 RenderEffect；低版本忽略。
     *
     * @param effect {@link GlowPainter#getRenderEffect()} 的返回值，
     *               实际类型是 {@code RenderEffect} 或 {@code null}
     */
    public static void setRenderEffect(View target, Object effect) {
        if (!HAS_EFFECT || target == null) {
            return;
        }
        if (effect == null) {
            Impl31.clear(target);
            return;
        }
        Impl31.apply(target, effect);
    }

    /**
     * 低版本静态渐变兜底。
     *
     * <p>用三个色标复现 {@code glow.glsl} 的主色调：深红（
     * {@code uColorBlack = 0.961,0.157,0.157}）→ 灰蓝（
     * {@code uColorMid = 0.604,0.659,0.961}）→ 蓝紫（
     * {@code uColorWhite = 0.302,0.29,0.843}）。
     *
     * <p>渐变的中心按 {@code circleYOffset} 下移，与高版本 shader 的
     * 光圈位置对齐（{@code uCircleYOffset} 默认 0.1，即中心略低于正中）。
     *
     * @param target 目标 View
     * @param yOffset 光圈纵向偏移，取值 [-1, 1]，与上游 {@code uCircleYOffset} 同义
     */
    public static void applyGlowFallback(View target, float yOffset) {
        if (target == null || HAS_EFFECT) {
            return;
        }
        // 中心点按 yOffset 在 0.5 基线上下浮动；GradientDrawable 的
        // 径向渐变用 (x, y) 归一化坐标。
        float centerY = 0.5f + yOffset * 0.5f;
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1030, 0xFF3B2A6B, 0xFF0D0A18});
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gd.setGradientRadius(0.75f);
        gd.setGradientCenter(0.5f, centerY);
        target.setBackground(gd);
    }

    /** API 31+ 的真实实现。低版本永远不会加载它。 */
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Impl31 {

        private Impl31() {
        }

        static void apply(View target, Object effect) {
            target.setRenderEffect((android.graphics.RenderEffect) effect);
        }

        static void clear(View target) {
            target.setRenderEffect(null);
        }
    }
}