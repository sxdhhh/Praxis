package dev.praxis.ui.design.effect

/**
 * OS3（MIUI 14+）背景着色器。**全文逐字照抄 InstallerX `OS3BgFrag.kt`**。
 *
 * ## 这段 AGSL 在算什么
 *
 * 1. **四个光斑叠加**：`uPoints[i]` 是 `(x, y, radius)`，`uPointsAnim[i]` 是它当前
 *    漂移后的位置。每个光斑用 `smoothstep(rad, 0, d)` 从中心向外淡出，四个逐层
 *    `mix` 上去 —— 相邻光斑重叠处自然混色，这正是「没有硬边」的原因。
 * 2. **噪声去饱和 + 提亮**：`perlin(vUv * uNoiseScale + (-t, -t))` 生成随时间滚动的
 *    噪声场，用 `oppositeNoise` 同时驱动 HSV 的 saturation 下降（`uSaturateOffset`）
 *    和 RGB 加法提亮（`uLightOffset`）。这一层是「壁纸会呼吸」的来源，删掉后
 *    纯四光斑看起来像静态脏色块。
 * 3. **抖动去色带**：末尾 `color += (10/255) * gradientNoise(...) - (5/255)`。
 *    4 个低饱和光斑混出来的渐变在 8bit 色深下必然出现同心色带（banding），
 *    这句加 ±5/255 的随机噪声把色带打散。**不能删**，删了在深色主题下肉眼可见。
 *
 * ## `uBound` 的作用（最容易被忽略的一段）
 *
 * `uv -= uBound.xy; uv /= uBound.zw` 把归一化坐标重映射到「内容区」。
 * 光斑是相对**内容区**（不含状态栏/导航栏）布局的，所以同一套点位在
 * 不同的 insets 下仍然落在同一个视觉位置。上游传的 `uBound.y = 1 - heightRatio`、
 * `uBound.w = heightRatio`（竖屏）或 `aspectRatio`（横屏），
 * 见 `BgEffectPainter.updateBound`。
 *
 * ## 为什么 `vUv.y = 1.0 - vUv.y`
 *
 * AGSL 的 `fragCoord` 原点在左上，但 `uPoints` 的 y 是按「数学坐标系」
 * （原点左下）标定的。翻转一次让两者对齐。反过来会得到上下颠倒的渐变。
 */
const val OS3_BG_FRAG = """
    uniform vec2 uResolution;
    uniform float uAnimTime;
    uniform vec4 uBound;
    uniform float uTranslateY;
    uniform vec3 uPoints[4];
    uniform vec2 uPointsAnim[4];
    uniform vec4 uColors[4];
    uniform float uAlphaMulti;
    uniform float uNoiseScale;
    uniform float uPointRadiusMulti;
    uniform float uSaturateOffset;
    uniform float uLightOffset;

    vec3 rgb2hsv(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }
    vec3 hsv2rgb(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }
    float hash(vec2 p) {
        vec3 p3 = fract(vec3(p.xyx) * 0.13);
        p3 += dot(p3, p3.yzx + 3.333);
        return fract((p3.x + p3.y) * p3.z);
    }
    float perlin(vec2 x) {
        vec2 i = floor(x); vec2 f = fract(x);
        float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
        float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
        vec2 u = f * f * (3.0 - 2.0 * f);
        return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
    }
    float gradientNoise(in vec2 uv) {
        return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
    }
    vec4 main(vec2 fragCoord){
        vec2 vUv = fragCoord/uResolution;
        vUv.y = 1.0-vUv.y;
        vec2 uv = vUv;
        uv -= vec2(0., uTranslateY);
        uv.xy -= uBound.xy;
        uv.xy /= uBound.zw;
        vec4 color = vec4(0.0);
        float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));
        for (int i = 0; i < 4; i++){
            vec4 pointColor = uColors[i];
            pointColor.rgb *= pointColor.a;
            vec2 point = uPointsAnim[i];
            float rad = uPoints[i].z * uPointRadiusMulti;
            float d = distance(uv, point);
            float pct = smoothstep(rad, 0., d);
            color.rgb = mix(color.rgb, pointColor.rgb, pct);
            color.a = mix(color.a, pointColor.a, pct);
        }
        float oppositeNoise = smoothstep(0., 1., noiseValue);
        color.rgb /= color.a;
        vec3 hsv = rgb2hsv(color.rgb);
        hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
        color.rgb = hsv2rgb(hsv);
        color.rgb += oppositeNoise * uLightOffset;
        color.a = clamp(color.a, 0., 1.);
        color.a *= uAlphaMulti;
        color += (10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0);
        return vec4(color.rgb * color.a, color.a);
    }
"""