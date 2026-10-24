package dev.praxis.ui.design.effect

/**
 * 开屏光晕着色器 —— **逐字照抄 HyperCeiler `library/provision/src/main/res/raw/glow.glsl`**。
 *
 * ## 来源与授权
 *
 * HyperCeiler 以 AGPL-3.0 发布（`library/provision` 各文件头写的是
 * "GNU Affero General Public License ... either version 3 of the License"），
 * 版权声明为 `Copyright (C) 2023-2026 HyperCeiler Contributions`。
 *
 * ## 逐字程度的边界（唯一的改动）
 *
 * 只删了一处：原文件里**被注释掉**的 `uLogo` / `uVideo` 声明块
 * （`//uniform shader uLogo;` 那几行）。理由：
 *  - 它们本就没启用，删掉对渲染结果零影响；
 *  - `uniform shader` 在 AGSL 里若声明却未绑定，部分 ROM 编译期会报错；
 *  - Kotlin 原始字符串里保留大段注释掉的 GLSL 会让代码更难核对。
 *
 * 其余**一个字符都没改**：`simplex` 噪声、`oklab_mix`、`getColor` 的
 * pal 调色板、`map` 的两个重载、环形光晕几何、`parabola` 畸变、
 * `screenBlend`、圆形遮罩 `mix(vec3(0.0), finalColor, mask)` 全部原样。
 *
 * ## 为什么必须上 shader（而不是继续用 Compose 渐变）
 *
 * 上一版用 `AnimatedFluidBackground`（四层径向渐变）。差距在三处
 * **渐变在原理上给不出**的效果：
 *
 * | 效果 | 为什么渐变做不到 |
 * |---|---|
 * | 环形光晕从中心扩散并按半径扭曲 | 需要「到中心距离」的环形带 + `parabola()` 加权 UV 位移 |
 * | 开场圆形遮罩从中心展开 | 需要逐像素 `smoothstep(radius±thickness, dist)` |
 * | 两层 simplex 噪声各自不同速（`uSpeed` / `uSpeed2`）| 渐变的色标是线性的，没有空间噪声 |
 *
 * 这三处就是原版开屏「呼吸感」的全部来源。
 *
 * ## API 门槛
 *
 * `RuntimeShader` 需要 **API 33**（Android 13）。Praxis `minSdk = 28`，
 * 所以调用方必须用 `isRuntimeShaderSupported()` 门控，低版本走降级分支
 * （见 `HyperCeilerStartupGlow`）。这是有意的取舍：开屏是**第一屏**，
 * 它绝不能在低版本上白屏。
 */
const val GLOW_AGSL = """
//------------------------Basic Params------------------------
uniform float uTime;
uniform vec2 uResolution;

//------------------------Base Layer (Color)------------------------
// Animation
uniform float uScale2;
uniform float uSpeed2;
// Range
uniform float uColorInMin;
uniform float uColorInMax;
uniform float uColorOutMin;
uniform float uColorOutMax;
uniform float uColorMidPoint;
// Colors
uniform float uUseOklab;
uniform vec3 uColorBlack;
uniform vec3 uColorMid;
uniform vec3 uColorWhite;

//------------------------Second Layer (Grayscale)------------------------
// Animation
uniform float uScale;
uniform float uSpeed;
// Range
uniform float uBrightnessInMin;
uniform float uBrightnessInMax;
uniform float uBrightnessOutMin;
uniform float uBrightnessOutMax;

//------------------------Circle Settings------------------------
// Visibility
uniform float uShowCircle;
// Size & Position
uniform float uCircleThickness;
uniform float uCircleFinalRadius;
uniform float uCircleYOffset;
// Animation
uniform float uCircleSpeed;
uniform float uCircleColorFreq;
uniform float uCircleColorSpeed;
uniform float uCircleEasing;
uniform float uCircleAnimationOffset;
uniform float uMaskDelay;
uniform float uMaskThickness;
// Color and Blending
uniform float uCircleScreenBlend;
uniform float uCircleAddBlend;
uniform float uCircleColorOffset;
// Distort
uniform float uCircleUVDistort;
uniform float uColorToDistortWidthRatio;
uniform float uDistortStartTime;
uniform float uDistortEndTime;
uniform float uDistortStart;
uniform float uDistortEnd;

//------------------------Stripe Settings------------------------
uniform float uStripeFrequency;
uniform float uStripeStrengthX;
uniform float uStripeStrengthY;
uniform float uStripeUVDistort;

float PI = 3.14159265359;
float TWO_PI = 6.28318530718;

vec3 oklab_mix(vec3 colA, vec3 colB, float h)
{
    // https://bottosson.github.io/posts/oklab
    const mat3 kCONEtoLMS = mat3(
    0.4121656120, 0.2118591070, 0.0883097947,
    0.5362752080, 0.6807189584, 0.2818474174,
    0.0514575653, 0.1074065790, 0.6302613616);
    const mat3 kLMStoCONE = mat3(
    4.0767245293, -1.2681437731, -0.0041119885,
    -3.3072168827, 2.6093323231, -0.7034763098,
    0.2307590544, -0.3411344290, 1.7068625689);

    // rgb to cone (arg of pow can't be negative)
    vec3 lmsA = pow(kCONEtoLMS*colA, vec3(1.0/3.0));
    vec3 lmsB = pow(kCONEtoLMS*colB, vec3(1.0/3.0));
    // lerp
    vec3 lms = mix(lmsA, lmsB, h);
    // gain in the middle (no oaklab anymore, but looks better?)
    lms *= 1.0+0.2*h*(1.0-h);
    // cone to rgb
    return kLMStoCONE*(lms*lms*lms);
}

vec3 pal(in float t, in vec3 a, in vec3 b, in vec3 c, in vec3 d)
{
    return a + b*cos(6.28318*(c*t+d));
}

// Screen blend mode
vec3 screenBlend(vec3 base, vec3 blend) {
    return 1.0 - (1.0 - base) * (1.0 - blend);
}

vec3 getColor (float p){
    return pal(p, vec3(0.5, 0.5, 0.5), vec3(0.5, 0.5, 0.5), vec3(1.0, 1.0, 1.0), vec3(0.0, 0.1, 0.2));
}

float map(float value, float min1, float max1, float min2, float max2) {
    float val = min2 + (value - min1) * (max2 - min2) / (max1 - min1);
    return min(max(val, min(min2, max2)), max(min2, max2));
}

vec2 map(vec2 value, vec2 min1, vec2 max1, vec2 min2, vec2 max2) {
    vec2 val = min2 + (value - min1) * (max2 - min2) / (max1 - min1);
    return vec2(
    min(max(val.x, min(min2.x, max2.x)), max(min2.x, max2.x)),
    min(max(val.y, min(min2.y, max2.y)), max(min2.y, max2.y))
    );
}

// ========== Hash ==========
vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.11369, 0.13787));
    p += dot(p, p.yxz + 19.19);
    return -1.0 + 2.0 * fract(vec3(
    (p.x + p.y) * p.z,
    (p.x + p.z) * p.y,
    (p.y + p.z) * p.x
    ));
}

// ========== Simplex Noise ==========
float simplex(vec3 p) {
    const float K1 = 1.0/3.0;
    const float K2 = 1.0/6.0;
    vec3 i = floor(p + dot(p, vec3(K1)));
    vec3 d0 = p - (i - dot(i, vec3(K2)));

    vec3 e = step(vec3(0.0), d0 - d0.yzx);
    vec3 i1 = e * (1.0 - e.zxy);
    vec3 i2 = 1.0 - e.zxy * (1.0 - e);

    vec3 d1 = d0 - i1 + K2;
    vec3 d2 = d0 - i2 + 2.0*K2;
    vec3 d3 = d0 - 1.0 + 3.0*K2;

    vec4 h = max(0.6 - vec4(dot(d0, d0), dot(d1, d1), dot(d2, d2), dot(d3, d3)), 0.0);
    vec4 w = h * h * h * h;
    vec4 n = w * vec4(
    dot(d0, hash33(i)),
    dot(d1, hash33(i + i1)),
    dot(d2, hash33(i + i2)),
    dot(d3, hash33(i + 1.0))
    );
    return dot(n, vec4(31.316));
}

// RGB to HSL conversion
vec3 rgb2hsl(vec3 rgb) {
    float h = 0.0;
    float s = 0.0;
    float l = 0.0;
    float r = rgb.r;
    float g = rgb.g;
    float b = rgb.b;
    float cmin = min(min(r, g), b);
    float cmax = max(max(r, g), b);
    float delta = cmax - cmin;

    // Calculate lightness
    l = (cmax + cmin) / 2.0;

    // Calculate saturation
    if (delta > 0.0) {
        s = delta / (1.0 - abs(2.0 * l - 1.0));

        // Calculate hue
        if (cmax == r) {
            h = 60.0 * (mod(((g - b) / delta), 6.0));
        } else if (cmax == g) {
            h = 60.0 * (((b - r) / delta) + 2.0);
        } else {
            h = 60.0 * (((r - g) / delta) + 4.0);
        }
    }

    return vec3(h, s, l);
}

// HSL to RGB conversion
vec3 hsl2rgb(vec3 hsl) {
    float h = hsl.x;
    float s = hsl.y;
    float l = hsl.z;

    float chroma = (1.0 - abs(2.0 * l - 1.0)) * s;
    float x = chroma * (1.0 - abs(mod(h / 60.0, 2.0) - 1.0));
    float m = l - chroma / 2.0;

    vec3 rgb;
    if (h < 60.0) {
        rgb = vec3(chroma, x, 0.0);
    } else if (h < 120.0) {
        rgb = vec3(x, chroma, 0.0);
    } else if (h < 180.0) {
        rgb = vec3(0.0, chroma, x);
    } else if (h < 240.0) {
        rgb = vec3(0.0, x, chroma);
    } else if (h < 300.0) {
        rgb = vec3(x, 0.0, chroma);
    } else {
        rgb = vec3(chroma, 0.0, x);
    }

    return rgb + m;
}

// Color burn blend mode
vec3 colorBurn(vec3 base, vec3 blend) {
    vec3 result = 1.0 - (1.0 - base) / (blend + 0.0001);
    return result;
}

float getCircleGradient(vec2 uv, vec2 center, float outerRadius, float innerRadius, vec2 screenAspect) {
    float dist = length((uv - center)*screenAspect);
    return map(dist, outerRadius, innerRadius, 0.0, 1.0);
}

float easeOutQuad(float x) {
    return 1 - (1 - x) * (1 - x);
}

float easeOutQuint(float x) {
    return 1.0 - pow(1.0 - x, 5.0);
}

float parabola(float x, float k)
{
    return pow(4.0*x*(1.0-x), k);
}

vec4 main(vec2 fragCoord) {
    vec2 uv = fragCoord / uResolution;
    vec2 screenAspect = uResolution.x > uResolution.y ? vec2(1.0, uResolution.y / uResolution.x) : vec2(uResolution.x/ uResolution.y, 1.0);
    vec2 center = vec2(0.5, 0.5 - uCircleYOffset);

    float animatedTime = uTime * uCircleSpeed;
    vec3 circleColor = vec3(0.0);
    bool circleEnd = false;
    float circleMask = 0.0;
    if (animatedTime <= 1.0) {
        float k = clamp(uCircleEasing, 0.0001, 10000.0);
        animatedTime = clamp(animatedTime/(k - animatedTime * (k - 1.0)) + uCircleAnimationOffset, 0.0, 1.0);
        float distortStrength = map(animatedTime, uDistortStartTime, uDistortEndTime, uDistortStart, uDistortEnd);

        // Calculate circle properties
        float outerRadius = map(animatedTime, 0.0, 1.0, 0.0, uCircleFinalRadius);
        float innerRadius = outerRadius - uCircleThickness;

        // Calculate stripe distortion
        float stripesUVx = uv.x*TWO_PI*uStripeFrequency;
        vec2 uvDistort = vec2(sin(stripesUVx)*uStripeStrengthX, cos(stripesUVx)*uStripeStrengthY);

        // Handle circle visibility
        float stripeDistortStrength = uStripeUVDistort;
        float circleDistortStrength = uCircleUVDistort;
        if (uShowCircle < 0.5) {
            uvDistort = vec2(0.0);
            stripeDistortStrength = 0.0;
            circleDistortStrength = 0.0;
        }

        // Calculate circle gradients
        float circleGradient = getCircleGradient(uv+uvDistort*stripeDistortStrength*distortStrength, center, outerRadius, innerRadius, screenAspect);
        float circleGradientDistort = getCircleGradient(uv+uvDistort, center, outerRadius, innerRadius, screenAspect);
        circleMask = smoothstep(0.0, 1.0, clamp(circleGradient * 2.0, 0.0, 1.0));

        // Calculate circle color and distortion
        circleColor = getColor(circleGradientDistort*uCircleColorFreq+uTime*uCircleColorSpeed+uCircleColorOffset)
        *parabola(circleGradientDistort, 3.0);
        float circleGradientRemap = map(circleGradient, 0.0+0.5*(1.0-uColorToDistortWidthRatio), 1.0-0.5*(1.0-uColorToDistortWidthRatio), -1.0, 1.0);
        uv += normalize(uv-center)*sign(circleGradientRemap)*parabola(abs(circleGradientRemap), 3.0)*circleDistortStrength*distortStrength;
    } else {
        circleEnd = true;
    }

    // 计算出现时的遮罩
    float dist = length((uv - center) * screenAspect);
    float radius = uTime * 0.9 - uMaskDelay;
    radius = easeOutQuad(min(radius, 1.0));
    float mask = 1.0 - smoothstep(radius - uMaskThickness, radius + uMaskThickness, dist);

    // Calculate noise layers
    vec3 pos = vec3(uv * screenAspect, uTime * uSpeed);
    vec3 pos2 = vec3(uv * screenAspect, uTime * uSpeed2);
    float f1 = simplex(pos * uScale);
    float f2 = simplex(pos2 * uScale2);

    // Remap noise values
    float v1 = clamp(map(f1 * 0.5 + 0.5, uBrightnessInMin, uBrightnessInMax, uBrightnessOutMin, uBrightnessOutMax), 0.0, 1.0);
    float v2 = clamp(map(f2 * 0.5 + 0.5, uColorInMin, uColorInMax, uColorOutMin, uColorOutMax), 0.0, 1.0);

    // Calculate color mixing
    vec3 col2;
    if (v2 < uColorMidPoint) {
        float mixFactor = map(v2, 0.0, uColorMidPoint, 0.0, 1.0);
        col2 = uUseOklab > 0.5 ? oklab_mix(uColorBlack, uColorMid, mixFactor) : mix(uColorBlack, uColorMid, mixFactor);
    } else {
        float mixFactor = map(v2, uColorMidPoint, 1.0, 0.0, 1.0);
        col2 = uUseOklab > 0.5 ? oklab_mix(uColorMid, uColorWhite, mixFactor) : mix(uColorMid, uColorWhite, mixFactor);
    }

    // Calculate final color
    vec3 finalColor = screenBlend(col2, vec3(v1));

    // Apply circle effects
    if (!circleEnd) {
        if (uShowCircle > 0.5) {
            finalColor = screenBlend(finalColor, circleColor * uCircleScreenBlend);
            finalColor += circleColor * uCircleAddBlend;
        }
    }

    if (uShowCircle > 0.5) {
        finalColor = mix(vec3(0.0), finalColor, mask);
    }

    return vec4(finalColor, 1.0);
}
"""