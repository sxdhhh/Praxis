package com.sevtinge.hyperceiler.provision.renderengine;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * {@code RuntimeShader} / {@code RenderEffect} 的版本隔离层。
 *
 * <h2>为什么必须隔离，而不是在原类里加 if 判断</h2>
 *
 * 上游 {@code GlowPainter} 的写法是：
 * <pre>
 * RuntimeShader mShader;              // 字段类型
 * mShader = new RuntimeShader(src);   // 构造
 * return RenderEffect.createShaderEffect(mShader);
 * </pre>
 *
 * {@code RuntimeShader} 是 API 33 才有的类，{@code RenderEffect} 是 API 31。
 * 把它们的类型直接写在 {@code GlowPainter} 的**字段签名**上，意味着
 * 「只要 {@code GlowPainter} 这个类被加载，就必须能解析这两个类型」。
 * 在 API 28 的设备上加载它会当场抛 {@code NoClassDefFoundError} ——
 * 而且这个异常发生在**类初始化阶段**，连 {@code if (SDK_INT >= 33)}
 * 都来不及执行。所以「原类里加 if」这条路是不通的。
 *
 * <h2>隔离方式</h2>
 *
 * 把 shader 的全部操作抽成本接口的三个方法，{@code GlowPainter} 只持有
 * {@code GlowShader} 类型：
 * <pre>
 * GlowShader mShader;                          // 接口，低版本也安全
 * mShader = GlowShader.create(src);            // 工厂内部分支
 * mShader.setFloatUniform("uTime", t);         // 40+ 处调用一行未改
 * Object effect = mShader.createRenderEffect();// 返回值改 Object
 * </pre>
 *
 * {@link Impl33} 用 {@code @RequiresApi(33)} 标注，且**只在 SDK >= 33 时
 * 被 new**。ART 的类加载是惰性的，低版本永远不会触碰 {@code Impl33}，
 * 也就不会解析 {@code RuntimeShader} —— 这是 Android 官方推荐的
 * 「新 API 用法隔离到独立类」模式。
 *
 * <h2>低版本的行为</h2>
 *
 * {@link Noop} 的所有 setter 都是空实现，{@link #createRenderEffect()}
 * 返回 {@code null}。调用方（{@code GlowController}）在拿到 null 时
 * 改走静态渐变兜底，见 {@link RenderCompat#applyGlowFallback}。
 *
 * @see RenderCompat
 */
interface GlowShader {

    void setFloatUniform(String name, float value);

    void setFloatUniform(String name, float value1, float value2);

    void setFloatUniform(String name, float[] values);

    /**
     * 返回 {@code RenderEffect} 实例；低版本或无 shader 时返回 {@code null}。
     *
     * <p>返回类型刻意写成 {@code Object} 而不是 {@code RenderEffect} ——
     * 后者是 API 31 类型，写进返回值会在方法签名里引入高版本符号，
     * 低版本调用方做 {@code instanceof} 或强转时也会连带解析。
     */
    Object createRenderEffect();

    /**
     * 按当前 SDK 选实现。
     *
     * @param vertexShader AGSL 源码；为 {@code null} 时直接返回 {@link Noop}
     *                     （上游 {@code loadShader} 失败会返回 null）
     */
    static GlowShader create(String vertexShader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vertexShader != null) {
            return new Impl33(vertexShader);
        }
        return Noop.INSTANCE;
    }

    /** API 33+ 的真实实现。低版本永远不会被加载，因此可以安全引用 RuntimeShader。 */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    final class Impl33 implements GlowShader {

        private final RuntimeShader mShader;

        Impl33(String vertexShader) {
            mShader = new RuntimeShader(vertexShader);
        }

        @Override
        public void setFloatUniform(String name, float value) {
            mShader.setFloatUniform(name, value);
        }

        @Override
        public void setFloatUniform(String name, float value1, float value2) {
            mShader.setFloatUniform(name, value1, value2);
        }

        @Override
        public void setFloatUniform(String name, float[] values) {
            mShader.setFloatUniform(name, values);
        }

        @Override
        public Object createRenderEffect() {
            return RenderEffect.createShaderEffect(mShader);
        }
    }

    /** 低版本占位实现：所有写入丢弃，不产生任何 effect。 */
    final class Noop implements GlowShader {

        static final Noop INSTANCE = new Noop();

        private Noop() {
        }

        @Override
        public void setFloatUniform(String name, float value) {
        }

        @Override
        public void setFloatUniform(String name, float value1, float value2) {
        }

        @Override
        public void setFloatUniform(String name, float[] values) {
        }

        @Override
        public Object createRenderEffect() {
            return null;
        }
    }
}