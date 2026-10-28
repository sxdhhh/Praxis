/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.provision.activity;

import android.os.Handler;

import androidx.fragment.app.Fragment;

import com.sevtinge.hyperceiler.provision.R;
import com.sevtinge.hyperceiler.provision.fragment.PermissionSettingsFragment;
import com.sevtinge.hyperceiler.provision.widget.PermissionWarningSheet;

public class PermissionSettingsActivity extends BaseActivity {

    private final Handler mBottomHandler = new Handler();

    @Override
    protected int getLogoDrawableId() {
        return 0;
    }

    @Override
    protected int getPreviewDrawable() {
        return R.drawable.provision_service_state;
    }

    @Override
    protected int getTitleStringId() {
        return R.string.provision_permission_settings_title;
    }

    @Override
    protected CharSequence getListDescCharSequence() {
        return null;
    }

    @Override
    protected Fragment getFragment() {
        return new PermissionSettingsFragment();
    }

    @Override
    protected String getFragmentTag() {
        return PermissionSettingsFragment.class.getSimpleName();
    }

    /**
     * 权限不全时的确认框（Operit 的 `showPermissionWarning` 同款）。
     *
     * ## 为什么拦在这里
     *
     * 「下一步」按钮的点击监听是 `ProvisionBaseActivity` 里的私有 `final`
     * `mNextClickListener`（第 93 行），子类无法替换。但它在动画路径的终点
     * 一定会调 `onNextAminStart()` —— 那就是拦截点。
     *
     * ## 为什么只警告不阻断
     *
     * Operit 的行为是：权限不全时点「下一步」弹一个 AlertDialog，
     * 文案说明「可以继续，但部分功能可能不可用」，两个按钮
     * （继续 / 去授权）。**不是**禁用按钮。理由是引导页不该把用户
     * 卡死在一台没有 root、或 ROM 不给电池豁免的设备上 ——
     * 那样用户除了卸载没有别的出路。
     *
     * 本方法复现同一行为：不全就弹框，用户选「继续」才真的往下走。
     */
    @Override
    public void onNextAminStart() {
        if (!mPermissionsAllGranted()) {
            // ## 为什么不是 AlertDialog（2026-10-29）
            //
            // 原来是 `android.app.AlertDialog.Builder`：在 miuix 引导页里它是
            // 一块白色方角弹窗，与前后页（圆角、大内边距、同字体、同主题色）
            // 完全不搭。而这是用户在引导里看到的**第一个**弹窗。
            //
            // 换成 miuix 的 BottomSheetModal（与协议页
            // `TermsAndStatementBottomSheet` 同容器），见 `widget/PermissionWarningSheet`。
            //
            // ## 为什么不复用同一个实例
            //
            // `BottomSheetModal` 一旦 dismiss 就不能再 show（内部 View 已被移除）。
            // 缓存实例会让「第二次点下一步」什么也不发生，所以每次都 new。
            new PermissionWarningSheet(this)
                    .onContinue(this::finishStep)
                    // 「去授予」不做事：用户就是不授，停在当前页即可。
                    .onBack(() -> {})
                    .show();
            return;
        }
        finishStep();
    }

    /** 真正离开本页。抽出来是为了让「警告框确认」与「直接通过」走同一条路。 */
    private void finishStep() {
        super.onNextAminStart();
        setResult(-1);
        finish();
    }

    /**
     * 四项权限是否全部授予。
     *
     * <p>与 `PermissionSettingsFragment` 里的四个 `hasXxxPermission()` 同一判定，
     * 这里重写一份而不是回调 Fragment：Activity 拿不到 Fragment 的私有方法，
     * 而把四个判定提到静态工具类又要多一个类。四行 Android API 调用，
     * 重复的代价小于新增一个跨层工具类。
     *
     * <p><b>两处必须同步改</b>：若以后增删检测项，`PermissionSettingsFragment`
     * 与本方法要一起改，否则会出现「界面显示全绿但点下一步还是弹警告」。
     */
    private boolean mPermissionsAllGranted() {
        // 存储
        boolean storage;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            storage = android.os.Environment.isExternalStorageManager();
        } else {
            storage = androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        // 悬浮窗
        boolean overlay = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(this);
        // 电池优化豁免
        boolean battery;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } else {
            battery = true;
        }
        // 位置
        boolean location =
                androidx.core.content.ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                || androidx.core.content.ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;

        return storage && overlay && battery && location;
    }
    public void enableBtnClick() {
        addClickable(false);
        mBottomHandler.postDelayed(() -> addClickable(true), 1000L);
    }

    public void addClickable(boolean clickable) {
        if (mConfirmButton != null) {
            mConfirmButton.setAlpha(clickable ? NO_ALPHA : HALF_ALPHA);
            mConfirmButton.setClickable(clickable);
        }
        if (mNewBackBtn != null) {
            mNewBackBtn.setAlpha(clickable ? NO_ALPHA : HALF_ALPHA);
            mNewBackBtn.setClickable(clickable);
        }
    }

}
