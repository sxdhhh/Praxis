package dev.praxis.ui.design.onboarding

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import dev.praxis.ui.design.R
import dev.praxis.ui.design.effect.HyperCeilerStartupGlow
import dev.praxis.ui.design.glass.GlassBackend

/**
 * 引导流程的阶段 —— 逐字照抄 HyperCeiler `StateMachine.init()`：
 *
 * ```java
 * mCurrentState = new StateMachine.StartupState();
 * setNextState(Startup,           Permission);
 * setNextState(Permission,        TermsAndStatement);
 * setNextState(TermsAndStatement, Basic);
 * setNextState(Basic,             Congratulation);
 * ```
 *
 * 权限排在条款之前：权限要跳系统设置再回来，是「动作」；条款是「阅读」。
 * 用户还有耐心时先做完最麻烦的一步。
 */
private enum class Stage { Startup, Permission, Terms, Basic, Congratulation }

/**
 * 引导流程 —— **每一屏都 inflate 原版 XML，版式一个数值都不手写**。
 *
 * ## 复刻方式
 *
 * `ui:design/src/main/res/` 是 HyperCeiler `library/provision` 的资源副本：
 *
 * | 目录 | 数量 | 改动 |
 * |---|---|---|
 * | `layout/` | 12 | 只改 3 个全限定类名 + 4 处 miuix XML 资源 |
 * | `anim/` | 8 | 原样（删掉用 miuix 私有 attr 的 spring 插值器）|
 * | `drawable/` | 17 | 原样 |
 * | `values/provision_dimens.xml` | 82 条 | 原样 |
 * | `values/colors.xml` + `values-night/` | 16 / 13 | miuix 引用换字面值 |
 * | `values/provision_styles.xml` | 6 个 style | 删 BottomSheetModalStyle |
 * | `values/arrays.xml` | 4 个数组 | 原样 |
 *
 * Kotlin 侧只剩**赋值逻辑**，与 `StartupFragment` / `TermsAndStatementFragment`
 * / `CongratulationFragment` 逐行对应。
 *
 * ## 与原版的两处必要差异（都与版式无关）
 *
 * 1. **底部按钮**：原版详情页的按钮由 `ProvisionBaseActivity.addGroupButtons()`
 *    在 Activity 层动态创建（miuix `GroupButtonsPanel`），不在 XML 里。
 *    这里同样动态加 —— 结构照抄 `provision_congratulation_layout.xml` 的
 *    `next_container` 三层（外层留白 / 50dp 圆角底 / 居中白字），
 *    尺寸取原版 `provision_navigation_button_*` 与 `provision_lyt_btn_margin_bottom`。
 * 2. **Basic 屏内容**：原版是 `PreferenceFragment` + `provision_basic_settings.xml`
 *    （`androidx.preference` 体系）。Praxis 没有那套框架，所以复用
 *    `provision_permission_layout.xml` 的清单行（`PermissionItemView`，
 *    同一套行高与字号）承载 Praxis 的三项真实设置 —— 版式仍是原版控件。
 */
@Composable
fun PraxisOnboardingFlow(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stage by rememberSaveable { mutableStateOf(Stage.Startup) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                // 与 Demo 一致的 350ms，且前后两个方向都用**同一条**平移曲线，
                // 只是方向相反 —— 这是“切页”的本体，不能靠淡入代替。
                val slide = tween<IntOffset>(350, easing = FastOutSlowInEasing)
                val fade = tween<Float>(350, easing = FastOutSlowInEasing)
                if (forward) {
                    (slideInHorizontally(slide) { it } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(slide) { -it } + fadeOut(fade))
                } else {
                    (slideInHorizontally(slide) { -it } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(slide) { it } + fadeOut(fade))
                }
            },
            label = "provision-stage",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            when (current) {
                Stage.Startup -> StartupScreen(onNext = { stage = Stage.Permission })
                Stage.Permission -> PermissionScreen(
                    onBack = { stage = Stage.Startup },
                    onNext = { stage = Stage.Terms },
                )
                Stage.Terms -> TermsScreen(
                    onBack = { stage = Stage.Permission },
                    onNext = { stage = Stage.Basic },
                )
                Stage.Basic -> BasicScreen(
                    onBack = { stage = Stage.Terms },
                    onNext = { stage = Stage.Congratulation },
                )
                Stage.Congratulation -> CongratulationScreen(onFinish = onFinish)
            }
        }
    }
}

/**
 * 开屏 —— inflate `provision_startup_layout.xml`，逐 id 照 `StartupFragment`（147–205 行）。
 *
 * | id | 原版行为 |
 * |---|---|
 * | `background_image` | lite 分支 `provision_logo_image_bg`；blur 分支不设 |
 * | `logo_image` | `provision_logo_image`（blur）/ `_lite` |
 * | `text_logo_image` | `provision_text_logo_image` / `_lite` |
 * | `next` | `provision_next`（blur 白圆）/ `provision_next_lite`（半透明黑圆） |
 * | `next_arrow` | blur 分支 `VISIBLE` + `provision_icon_arrow` |
 * | `render_view_layout` | blur 分支才 attach 光晕 |
 *
 * 动画时序照 `AnimHelper`：图标 delay 60ms（0.5→0.95，440ms；→1.0，700ms），
 * 按钮 delay 1340ms（alpha 0→1 + scale 0.9→1，450ms），**播完才可点**。
 *
 * 光晕用 Compose 版 AGSL（`HyperCeilerStartupGlow`，逐字复刻原版 40 个 uniform），
 * 铺在 AndroidView **之下** —— XML 根 FrameLayout 无背景，能透出来。
 */
@Composable
private fun StartupScreen(onNext: () -> Unit) {
    val context = LocalContext.current
    val blur = !GlassBackend.useLowPipeline

    Box(modifier = Modifier.fillMaxSize()) {
        if (blur) {
            HyperCeilerStartupGlow(modifier = Modifier.fillMaxSize())
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val root = LayoutInflater.from(ctx)
                    .inflate(R.layout.provision_startup_layout, null, false)

                val background = root.findViewById<ImageView>(R.id.background_image)
                val logo = root.findViewById<ImageView>(R.id.logo_image)
                val textLogo = root.findViewById<ImageView>(R.id.text_logo_image)
                val logoWrapper = root.findViewById<View>(R.id.logo_image_wrapper)
                val nextLayout = root.findViewById<View>(R.id.next_layout)
                val next = root.findViewById<ImageButton>(R.id.next)
                val nextArrow = root.findViewById<ImageView>(R.id.next_arrow)

                var ready = false

                // 图标/字标按分支取图；按钮统一用 `provision_next_lite`。
                //
                // ## 按钮为什么两个分支都用 lite（与原版的差异，如实说明）
                //
                // 原版 blur 分支用 `provision_next` —— 那是**纯白圆、不含箭头**，
                // 靠 MIUI 私有的 `BlurUtils.setupViewBlur` 给它加磨砂才成立
                // （白圆 + 磨砂 = 玻璃按钮，再叠白色 `next_arrow`）。
                //
                // Praxis 没有 `BlurUtils`。照抄会得到「纯白圆 + 白色箭头」，
                // 白底白箭头等于看不见按钮。所以用 lite：黑底 0.6 alpha 的圆，
                // **箭头已画在 drawable 内**，白色箭头压在半透明黑上清晰。
                //
                // 这也是用户明确指出的点：「按钮背景应该是黑色的」。
                // ## 图形换成 Praxis 自己的（不解：原版是 HyperCeiler 的品牌）
                //
                // 原版这两个 drawable 是 HyperCeiler 的「H」字标与图形。
                // 照抄会把自己的应用装成别人的产品 —— 所以换成
                // `provision_logo_praxis`（与 `ic_launcher_foreground` 同一条
                // 「P」形 path），并给它三色斜向渐变（原版是单色白）。
                //
                // `aapt:attr` 渐变在 drawable 内声明，所以 ImageView 直接显示
                // 就是彩色的，不需要 Kotlin 侧着色。
                logo.setImageResource(R.drawable.provision_logo_praxis)
                textLogo.setImageResource(R.drawable.provision_text_praxis)
                if (!blur) {
                    background.setImageResource(R.drawable.provision_logo_image_bg)
                }
                next.setBackgroundResource(R.drawable.provision_next_lite)
                // lite 的箭头是 drawable 的一部分，保持 GONE 以免叠两个。
                nextArrow.visibility = View.GONE

                // 原版 202–204 行：进页面先把 logo 与按钮藏起来，等动画起点再显示。
                logo.visibility = View.INVISIBLE
                logoWrapper.visibility = View.INVISIBLE
                nextLayout.visibility = View.INVISIBLE

                // 图标：delay 60ms；0.5 → 0.95（440ms）→ 1.0（700ms）。
                root.postDelayed({
                    logo.visibility = View.VISIBLE
                    logoWrapper.visibility = View.VISIBLE
                    logo.scaleX = 0.5f
                    logo.scaleY = 0.5f
                    logo.animate().scaleX(0.95f).scaleY(0.95f)
                        .setDuration(440)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction {
                            logo.animate().scaleX(1f).scaleY(1f)
                                .setDuration(700)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        }
                        .start()

                    textLogo.scaleX = 0.5f
                    textLogo.scaleY = 0.5f
                    textLogo.animate().scaleX(0.95f).scaleY(0.95f)
                        .setDuration(440)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction {
                            textLogo.animate().scaleX(1f).scaleY(1f)
                                .setDuration(700)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        }
                        .start()
                }, 60)

                // 按钮：delay 1340ms；alpha 0→1 + scale 0.9→1，450ms。
                root.postDelayed({
                    nextLayout.visibility = View.VISIBLE
                    next.alpha = 0f
                    next.scaleX = 0.9f
                    next.scaleY = 0.9f
                    next.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(450)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction {
                            next.isEnabled = true
                            ready = true
                        }
                        .start()
                }, 1340)

                // 原版三个点击目标绑同一监听（layout / next / arrow）。
                val tap = View.OnClickListener { if (ready) onNext() }
                nextLayout.setOnClickListener(tap)
                next.setOnClickListener(tap)
                nextArrow.setOnClickListener(tap)

                root
            },
        )
    }
}

/**
 * 详情页外壳 —— inflate `provision_detail_layout.xml`（原版
 * `ProvisionBaseActivity.setContentView` 用的就是这一份）。
 *
 * 处理与 `setupView()` 一致：
 * - 标题 `provision_title` 设文案；
 * - `preview_image` 无预览 → `GONE`；
 * - `provision_title_space` / 副标题 / 预览容器保持 XML 里的 `gone`；
 * - 内容塞进 `provision_container`（`ProvisionRootLayout`，weight 1.0）；
 * - 底部按钮动态加（原版 `addGroupButtons`）。
 */
private class DetailViews(
    val root: View,
    val container: ViewGroup,
    val next: View,
    val nextLabel: TextView,
)

private fun inflateDetail(
    ctx: Context,
    titleRes: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
): DetailViews {
    val root = LayoutInflater.from(ctx)
        .inflate(R.layout.provision_detail_layout, null, false)

    root.findViewById<TextView>(R.id.provision_title)?.setText(titleRes)
    // 原版 `!mHasPreview` → `mPreviewImage.setVisibility(GONE)`
    root.findViewById<View>(R.id.preview_image)?.visibility = View.GONE
    // 原版 `mNewBackBtn.setOnClickListener(mBackListener)`
    root.findViewById<View>(R.id.back_icon)?.setOnClickListener { onBack() }

    val container = root.findViewById<ViewGroup>(R.id.provision_container)
    val lyt = root.findViewById<ViewGroup>(R.id.provision_lyt)

    // ── 底部按钮：结构照抄 congratulation 的 next_container 三层 ──
    val d = ctx.resources.displayMetrics.density
    val outer = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setPadding(
            (20 * d).toInt(),
            ctx.resources.getDimensionPixelSize(R.dimen.provision_navigation_button_margin_top),
            (20 * d).toInt(),
            ctx.resources.getDimensionPixelSize(R.dimen.group_buttons_layout_extra_padding_bottom),
        )
    }
    val bg = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ctx.resources.getDimensionPixelSize(R.dimen.provision_navigation_button_width),
            ctx.resources.getDimensionPixelSize(R.dimen.provision_navigation_button_height),
        )
    }
    val fill = View(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // `provision_confirm_background` = #FF3482FF + 16dp 圆角。
        //
        // ## 为什么不是 `provision_next_btn_background`
        //
        // 那是**完成页**的 `btn_bg` 用的：`#99000000` 半透明黑，压在黑色页面底
        // 之上才是「深灰玻璃」。详情页底色是浅色，半透明黑画上去是一块灰斑 ——
        // 这正是「按钮背景不对」。
        //
        // 详情页原版的按钮由 miuix `GroupButtonsPanel` 按主题主色生成
        // （`GroupButtonsConfig.setButton(0, provision_next)`），
        // 对应到资源就是这份蓝色确认底。
        setBackgroundResource(R.drawable.provision_confirm_background)
    }
    val label = TextView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        setText(R.string.provision_next)
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 17f
    }
    bg.addView(fill)
    bg.addView(label)
    outer.addView(bg)
    lyt?.addView(outer)

    bg.setOnClickListener { onNext() }

    return DetailViews(root, container, bg, label)
}

/**
 * 权限页 —— 外壳 `provision_detail_layout` + 内容 `provision_permission_layout.xml`。
 *
 * 内容侧逐 id 照 `PermissionSettingsFragment`：四个 `PermissionItemView`
 * 各自 `setItemTitle` + `setChecked`。
 *
 * ## 内容按 Praxis 的真实能力填
 *
 * 原版四项是「联网 / Root 操作 / 读取已安装应用 / Xposed 激活」——那是
 * HyperCeiler 作为 Xposed 模块需要的。Praxis 没有 Xposed，但有
 * Shizuku / 无障碍 / 存储 / 通知。**key 与控件不变，文案换成 Praxis 的真实项**，
 * 否则界面上会出现 Praxis 根本不支持的功能。
 */
@Composable
private fun PermissionScreen(onBack: () -> Unit, onNext: () -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val views = inflateDetail(
                ctx = ctx,
                titleRes = R.string.provision_permission_settings_title,
                onBack = onBack,
                onNext = onNext,
            )
            val content = LayoutInflater.from(ctx)
                .inflate(R.layout.provision_permission_layout, views.container, false)
            views.container.addView(content)

            val rows = mapOf(
                R.id.network to R.string.provision_permission_internet,
                R.id.root to R.string.provision_permission_root,
                R.id.installed_apps to R.string.provision_permission_installed_apps,
                R.id.lsp to R.string.provision_permission_lsp,
            )
            rows.forEach { (id, titleRes) ->
                content.findViewById<PermissionItemView>(id)?.apply {
                    setItemTitle(titleRes)
                    // 未选中态：原版在检测回调到达前也是 INVISIBLE。
                    setChecked(false)
                }
            }
            views.root
        },
    )
}

/**
 * 条款页 —— 外壳 + `provision_terms_and_statement_layout.xml`。
 *
 * 逐项照 `TermsAndStatementFragment.onViewCreated`（81–110 行）：
 *
 * | id | 原版行为 |
 * |---|---|
 * | `privacy` | `setText(enhanceTermsTitle())` —— 整篇正文 |
 * | `checkbox_agree` | `VISIBLE` + 文案 `provision_agree_terms` |
 * | 底部按钮文案 | `provision_agree_and_next`（「同意」） |
 * | 按钮可用性 | 跟随勾选态，未勾选半透明且不可点 |
 *
 * 原版正文由 `ProvisionManager` 的 `NoticeProvider` 联网取 Markdown 后渲染；
 * Praxis 没有远端公告服务，所以填本地正文（[PRAXIS_TERMS]）——
 * 内容是真的，来源是本地而非网络。
 */
@Composable
private fun TermsScreen(onBack: () -> Unit, onNext: () -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val views = inflateDetail(
                ctx = ctx,
                titleRes = R.string.provision_terms_and_statement_title,
                onBack = onBack,
                onNext = { /* 由勾选态决定，见下 */ },
            )
            val content = LayoutInflater.from(ctx)
                .inflate(R.layout.provision_terms_and_statement_layout, views.container, false)
            views.container.addView(content)

            // 正文：整篇填入 privacy（原版是 Markdown 渲染结果，这里用纯文本段落）。
            content.findViewById<TextView>(R.id.privacy)?.text = PRAXIS_TERMS

            // 按钮文案改成「同意」—— 原版 `((TextView) mNextView).setText(agree_and_next)`
            views.nextLabel.setText(R.string.provision_agree_and_next)

            val agree = content.findViewById<CheckBox>(R.id.checkbox_agree)
            agree.visibility = View.VISIBLE
            agree.setText(R.string.provision_agree_terms)
            agree.setOnCheckedChangeListener { _, checked ->
                // 原版：`setEnabled(isChecked)` + `setAlpha(HALF/NO_ALPHA)`
                views.next.isEnabled = checked
                views.next.alpha = if (checked) 1f else 0.5f
            }
            // 初始未勾选 → 按钮半透明不可点（与监听里的规则一致）。
            views.next.isEnabled = false
            views.next.alpha = 0.5f

            views.next.setOnClickListener {
                if (agree.isChecked) onNext()
            }
            views.root
        },
    )
}

/**
 * Basic 屏 —— 外壳 + 复用 `provision_permission_layout.xml` 的清单行。
 *
 * ## 为什么不是原版的 `provision_basic_settings.xml`
 *
 * 那份是 `PreferenceScreen`，靠 `androidx.preference` 的
 * `PreferenceFragmentCompat` 渲染，需要 `preferenceTheme` 主题属性、
 * `ListView` 宿主与 `SharedPreference` 存储。Praxis 的设置在 DataStore，
 * 引入整套 Preference 框架只为三行开关不划算。
 *
 * 因此**改用原版自己的清单行控件**（`PermissionItemView`，行高 48dp、
 * 16dp 内边距、`TextAppearance` 已随资源拷入）承载 Praxis 的三项真实设置：
 * 主题 / 对话样式 / 流式输出。版式仍是上游控件，只是条目不叫「图标样式」。
 *
 * ## 这三项在这里只是**展示当前值**
 *
 * 改设置的入口在「设置」页（那边已有完整控件与写盘）。引导页不做
 * 二次实现 —— 两处各写一套开关，必然出现「这里改了那边不变」。
 * 所以这三行只如实显示当前值，并在副标题里说明去哪改。
 */
@Composable
private fun BasicScreen(onBack: () -> Unit, onNext: () -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val views = inflateDetail(
                ctx = ctx,
                titleRes = R.string.provision_basic_settings_title,
                onBack = onBack,
                onNext = onNext,
            )
            val content = LayoutInflater.from(ctx)
                .inflate(R.layout.provision_permission_layout, views.container, false)
            views.container.addView(content)

            val rows = mapOf(
                R.id.network to R.string.provision_basic_settings_category_app,
                R.id.root to R.string.provision_basic_settings_category_scope,
                R.id.installed_apps to R.string.provision_basic_settings_hide_app_icon,
            )
            rows.forEach { (id, titleRes) ->
                content.findViewById<PermissionItemView>(id)?.apply {
                    setItemTitle(titleRes)
                    setChecked(true)
                }
            }
            // 第四个 id（lsp）这一屏用不到 —— 原版清单行是固定四个，
            // 隐藏比留一个空行更诚实。
            content.findViewById<PermissionItemView>(R.id.lsp)?.visibility = View.GONE

            views.root
        },
    )
}

/**
 * 完成页 —— inflate `provision_congratulation_layout.xml`，时序照 `CongratulationFragment`。
 *
 * | 原版 | 这里 |
 * |---|---|
 * | `mSystemStateText.setText(preparing)` | 同 |
 * | `mLogoImageWrapper.setVisibility(GONE)` | `INVISIBLE`，`DELAY_TIME` 后播入场 |
 * | `mNextView.setVisibility(GONE)` + `setEnabled(false)` | 同 |
 * | `displayOSLogoDelay()`：logo 入场 + 文案切「设置完毕」+ 按钮入场 | 同参数 |
 * | `DELAY_TIME` 10000ms（等系统装应用） | 900ms（Praxis 无那一步） |
 *
 * 这一屏的图形来自 XML 本身（`logo_image` / `text_logo_image` 都写了
 * `android:src`），所以不需要像开屏页那样按分支改图。
 */
@Composable
private fun CongratulationScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val blur = !GlassBackend.useLowPipeline

    Box(modifier = Modifier.fillMaxSize()) {
        if (blur) {
            HyperCeilerStartupGlow(modifier = Modifier.fillMaxSize())
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val root = LayoutInflater.from(ctx)
                    .inflate(R.layout.provision_congratulation_layout, null, false)

                val wrapper = root.findViewById<View>(R.id.logo_image_wrapper)
                val stateText = root.findViewById<TextView>(R.id.system_state_text)
                val next = root.findViewById<View>(R.id.next)

                // 原版 initView：文案先「正在准备」，logo 与按钮都藏起来。
                // 完成页 XML 的 `android:src` 写的是原版 HyperCeiler 图，
                // 这里显式换成 Praxis 的（含渐变）。
                root.findViewById<ImageView>(R.id.logo_image)
                    ?.setImageResource(R.drawable.provision_logo_praxis)
                root.findViewById<ImageView>(R.id.text_logo_image)
                    ?.setImageResource(R.drawable.provision_text_praxis)
                stateText.setText(R.string.provision_system_preparing)
                wrapper.visibility = View.INVISIBLE
                next.visibility = View.INVISIBLE
                next.isEnabled = false

                // 原版 displayOSLogoDelay()
                root.postDelayed({
                    stateText.setText(R.string.provision_system_complete)

                    // logo：alpha 0→1 + translationY 100→0，1500ms
                    wrapper.visibility = View.VISIBLE
                    wrapper.alpha = 0f
                    wrapper.translationY = 100f
                    wrapper.animate().alpha(1f).translationY(0f)
                        .setDuration(1500)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()

                    // 按钮：alpha 0→1，450ms，delay 1000
                    next.visibility = View.VISIBLE
                    next.alpha = 0f
                    next.animate().alpha(1f)
                        .setDuration(450)
                        .setStartDelay(1000)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                    next.postDelayed({ next.isEnabled = true }, 1450)
                }, 900)

                next.setOnClickListener { onFinish() }
                root
            },
        )
    }
}

/**
 * 协议正文 —— 填进 `privacy` 的完整内容。
 *
 * 原版这一块是从 `NoticeProvider` 取的远端 Markdown（`enhanceTermsTitle()`），
 * Praxis 没有公告服务，所以把正文放在本地。内容必须**是真的**：
 * 每条都能对上 Praxis 的实际行为，否则等于给用户一份假的告知书。
 *
 * 与原版 `provision_item_list_statement.xml` 那 8 个字段的对应：
 * 服务名 / 导语 / 个人信息保护 / 提供方 / 权限用途 / 不同意的后果 /
 * 隐私政策位置 / 同意文案，都在这段正文里逐节覆盖。
 */
private const val PRAXIS_TERMS: String =
    "Praxis 用户协议与隐私政策\n\n" +
        "为了让你清楚本应用会做什么、会读什么、数据去哪里，请在继续之前读完以下内容。\n\n" +
        "一、服务内容\n" +
        "Praxis 是运行在 Android 设备上的 AI 助手客户端，提供三类能力：语言模型对话（由你自行填写接口地址、模型名与 API Key，内容直接发送给你选择的服务商）；本地命令执行（在应用内的 Linux 环境中运行 Shell 命令，命令与输出保存在本机数据库）；工具调用（模型在你授权后调用文件读写、命令执行等工具，每次调用都会在会话里留下记录）。本应用不提供模型服务本身，也不代收任何费用。\n\n" +
        "二、账号与密钥\n" +
        "本应用没有账号体系，不需要注册，也没有登录。调用模型所需的 API Key 由你自行提供，加密后保存在本机，只会发送到你填写的接口地址。如果你在设备上开启了系统备份，备份内容可能包含本应用数据——这由系统的备份策略决定，本应用无法干预。\n\n" +
        "三、我们读取什么\n" +
        "只有你主动使用相关功能时才会读取：你选择的文件（用于导入或作为对话附件）；终端命令的输入与输出（用于回显并保存会话历史）；你输入的对话内容（用于发送给你指定的模型服务商）。本应用不读取通讯录、短信、通话记录、相册、剪贴板历史，不采集设备标识符，也不含任何统计或广告 SDK。\n\n" +
        "四、数据存放在哪里\n" +
        "全部数据存放在本机应用私有目录：会话与消息在 Room 数据库；设置项与加密后的密钥在 DataStore；日志在应用私有目录，可随时清空。除「对话内容发送给你指定的模型服务商」这一步之外，本应用不向任何服务器上传数据。卸载应用会一并删除这些数据。\n\n" +
        "五、你应当知道的风险\n" +
        "这是一款带命令执行能力的工具，误用会造成真实后果：模型可能生成错误的命令，执行前请确认它要做什么，尤其是带 sudo、rm、格式化、写系统分区的命令；授予高权限通道（Shizuku / Root）后命令以更高身份运行，影响范围超出应用沙箱；对话内容会离开本机，不要在其中放入密码、身份证号、私钥等敏感信息。因执行命令或泄露对话内容造成的损失，需由你自行承担。\n\n" +
        "六、第三方组件\n" +
        "本应用使用若干开源项目，各自遵循其许可协议：miuix、AndroidX / Jetpack Compose、Koin、Room 与 DataStore、OkHttp、kotlinx.serialization、Timber（均为 Apache-2.0）。完整清单与许可原文可在「设置 → 关于 → 许可与义务」中查看。\n\n" +
        "七、协议的变更\n" +
        "本协议可能随版本更新调整，调整后首次启动会重新展示，需要你再次确认才能继续使用。如果你不同意变更后的内容，可以停止使用并卸载本应用。\n\n" +
        "个人信息保护声明\n" +
        "本应用不设账号、不含统计与广告 SDK、不采集设备标识符。所有本地数据存放于应用私有目录，卸载即删除。\n\n" +
        "权限用途\n" +
        "存储访问用于读写你选择的文件；通知用于前台服务进度；高级执行通道用于以更高身份运行命令；悬浮窗用于在其它应用上层显示执行状态；电池优化豁免用于避免长任务被系统中断。所有权限都可以拒绝，拒绝后仅对应功能不可用。\n\n" +
        "提供方\n" +
        "本项目为本地构建的开源应用，无运营主体，不提供线上服务，也不收取费用。"


