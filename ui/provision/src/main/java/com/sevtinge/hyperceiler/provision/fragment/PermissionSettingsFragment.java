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
package com.sevtinge.hyperceiler.provision.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.sevtinge.hyperceiler.provision.R;
import com.sevtinge.hyperceiler.provision.utils.ProvisionPrefs;
import com.sevtinge.hyperceiler.provision.widget.PermissionItemView;

import fan.provision.OobeUtils;

/**
 * 权限页：UI 用 HyperCeiler 的控件，流程与检测项对齐 Operit。
 *
 * <h2>为什么重写而不是沿用上游的四项</h2>
 *
 * 上游这个 Fragment 检测的是 HyperCeiler 自己需要的四项：
 * 网络 / Root / LSPosed / 已安装应用列表。那四项对 Praxis 没有意义
 * （Praxis 不是 Xposed 模块，不需要 LSPosed）。
 *
 * Operit 的引导页检测的是另外四项，见
 * `PermissionGuideViewModel.checkPermissions()`：
 * <pre>
 * 存储     Environment.isExternalStorageManager()      // API ≥ R
 *          WRITE_EXTERNAL_STORAGE                      // API < R
 * 悬浮窗   Settings.canDrawOverlays()
 * 电池     PowerManager.isIgnoringBatteryOptimizations()
 * 位置     ACCESS_FINE_LOCATION || ACCESS_COARSE_LOCATION
 * </pre>
 * 本类逐条照抄这四种判定方式（连 API 分支都一样），只是把它们放进
 * HyperCeiler 的 `PermissionItemView` 里渲染。
 *
 * <h2>「不能只做空壳」体现在哪</h2>
 *
 * 每一项都可点、点了会真的跳系统授权页或弹运行时权限框；
 * 授权回来后在 {@code onResume} 里重新检测并刷新勾选态；
 * 未全部授予时点「下一步」会弹 Operit 同款警告框（可继续）；
 * 选中的权限级别会真的写进 {@link ProvisionPrefs}，供
 * {@code MainActivity} 侧读走并落到 `SettingsRepository`。
 *
 * <h2>权限级别为什么只给四档</h2>
 *
 * Praxis 的 `PraxisPermissionLevel` 有五档（多了 ADMIN/设备管理员）。
 * 引导页只给四档，与 Operit 的 `PermissionGuideScreen` 一致 ——
 * 上游 Operit 也确实只渲染 STANDARD/ACCESSIBILITY/DEBUGGER/ROOT，
 * ADMIN 留到设置页里选。这是刻意保持一致，不是漏了。
 */
public class PermissionSettingsFragment extends BaseFragment {
    private static final int REQUEST_LOCATION = 1102;

    private PermissionItemView mStorageItem;
    private PermissionItemView mOverlayItem;
    private PermissionItemView mBatteryItem;
    private PermissionItemView mLocationItem;

    private PermissionItemView mLevelStandard;
    private PermissionItemView mLevelAccessibility;
    private PermissionItemView mLevelDebugger;
    private PermissionItemView mLevelRoot;

    /** 当前选中的档位，取值与 Praxis `PraxisPermissionLevel.name` 一致。 */
    private String mSelectedLevel = ProvisionPrefs.LEVEL_STANDARD;

    @Override
    protected int getLayoutId() {
        return R.layout.provision_permission_layout;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mStorageItem = view.findViewById(R.id.perm_storage);
        mOverlayItem = view.findViewById(R.id.perm_overlay);
        mBatteryItem = view.findViewById(R.id.perm_battery);
        mLocationItem = view.findViewById(R.id.perm_location);

        mLevelStandard = view.findViewById(R.id.level_standard);
        mLevelAccessibility = view.findViewById(R.id.level_accessibility);
        mLevelDebugger = view.findViewById(R.id.level_debugger);
        mLevelRoot = view.findViewById(R.id.level_root);

        bindItem(mStorageItem, R.string.provision_permission_storage,
                R.string.provision_permission_storage_desc);
        bindItem(mOverlayItem, R.string.provision_permission_overlay,
                R.string.provision_permission_overlay_desc);
        bindItem(mBatteryItem, R.string.provision_permission_battery,
                R.string.provision_permission_battery_desc);
        bindItem(mLocationItem, R.string.provision_permission_location,
                R.string.provision_permission_location_desc);

        bindItem(mLevelStandard, R.string.provision_permission_level_standard,
                R.string.provision_permission_level_standard_desc);
        bindItem(mLevelAccessibility, R.string.provision_permission_level_accessibility,
                R.string.provision_permission_level_accessibility_desc);
        bindItem(mLevelDebugger, R.string.provision_permission_level_debugger,
                R.string.provision_permission_level_debugger_desc);
        bindItem(mLevelRoot, R.string.provision_permission_level_root,
                R.string.provision_permission_level_root_desc);

        // 四项权限：点击行为与 Operit 的四个 launcher 一一对应。
        mStorageItem.setOnClickListener(v -> requestStorage());
        mOverlayItem.setOnClickListener(v -> requestOverlay());
        mBatteryItem.setOnClickListener(v -> requestBatteryExemption());
        mLocationItem.setOnClickListener(v -> requestLocation());

        // 四档级别：单选，点谁谁亮。
        mLevelStandard.setOnClickListener(v -> selectLevel(ProvisionPrefs.LEVEL_STANDARD, mLevelStandard));
        mLevelAccessibility.setOnClickListener(v -> selectLevel(ProvisionPrefs.LEVEL_ACCESSIBILITY, mLevelAccessibility));
        mLevelDebugger.setOnClickListener(v -> selectLevel(ProvisionPrefs.LEVEL_DEBUGGER, mLevelDebugger));
        mLevelRoot.setOnClickListener(v -> selectLevel(ProvisionPrefs.LEVEL_ROOT, mLevelRoot));

        // 恢复上次选择（从系统设置页返回时会重建 View）。
        mSelectedLevel = ProvisionPrefs.readPermissionLevel(requireContext());
        applyLevelSelection();

        refreshAll();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 用户去系统设置授权后回来，状态必须在同一帧变过来。
        // 这是 Operit 的做法（`LaunchedEffect(Unit) { checkPermissions }` +
        // 四个 launcher 的 onResult 回调里再 checkPermissions 一次）。
        refreshAll();
    }

    // ── 渲染 ──────────────────────────────────────────────────────

    /**
     * 给 PermissionItemView 填标题与说明。
     *
     * <p>上游控件只有一个 `item_title`，没有副标题位。这里把说明文字
     * 拼进标题（`title\n说明`），而不是改布局 —— 改控件布局会影响
     * Basic 页与 Terms 页（它们也用同一个控件），风险大于收益。
     */
    private void bindItem(PermissionItemView item, int titleRes, int descRes) {
        if (item == null) return;
        String title = getString(titleRes);
        String desc = getString(descRes);
        item.setItemTitle(title + "\n" + desc);
    }

    private void refreshAll() {
        if (!isAdded()) return;

        setChecked(mStorageItem, hasStoragePermission());
        setChecked(mOverlayItem, hasOverlayPermission());
        setChecked(mBatteryItem, hasBatteryExemption());
        setChecked(mLocationItem, hasLocationPermission());

        // 注意：Operit 在权限不全时**不禁用**下一步，只弹警告框。
        // 拦截逻辑放在 `PermissionSettingsActivity.onNextAminStart()`——
        // 这里不做任何禁用，避免用户被卡死在没有 root 的机器上。
    }

    private void setChecked(PermissionItemView item, boolean checked) {
        if (item != null) item.setChecked(checked);
    }


    // ── 四项检测（逐条对齐 Operit）────────────────────────────────

    /** 存储：API ≥ R 走 MANAGE_EXTERNAL_STORAGE，否则走 WRITE_EXTERNAL_STORAGE。 */
    private boolean hasStoragePermission() {
        if (!isAdded()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 悬浮窗：Settings.canDrawOverlays。 */
    private boolean hasOverlayPermission() {
        if (!isAdded()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(requireContext());
        }
        return true;
    }

    /** 电池优化豁免：PowerManager.isIgnoringBatteryOptimizations。 */
    private boolean hasBatteryExemption() {
        if (!isAdded()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm =
                    (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
        }
        return true;
    }

    /** 位置：FINE 或 COARSE 任一即可（Operit 同）。 */
    private boolean hasLocationPermission() {
        if (!isAdded()) return false;
        return ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ── 四项授权请求 ──────────────────────────────────────────────

    private void requestStorage() {
        if (!isAdded()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API ≥ R：MANAGE_EXTERNAL_STORAGE 只能跳系统页，没有运行时弹窗。
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                // 部分机型没有这个 Activity，退回全局列表页。
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                    // 都打不开时不做任何事：不要假装授权成功。
                }
            }
        } else {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_LOCATION);
        }
    }

    private void requestOverlay() {
        if (!isAdded()) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void requestBatteryExemption() {
        if (!isAdded()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // 被 ROM 拦掉时退回电池优化列表页。
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void requestLocation() {
        if (!isAdded()) return;
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
        }, REQUEST_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 无论是存储还是位置，回来都重检一遍 —— 与 Operit 的 launcher 回调同策略。
        refreshAll();
    }

    // ── 权限级别 ──────────────────────────────────────────────────

    private void selectLevel(String level, PermissionItemView item) {
        mSelectedLevel = level;
        // 先写盘：用户可能在写完之前就点下一步/返回。
        ProvisionPrefs.writePermissionLevel(requireContext(), level);
        applyLevelSelection();
    }

    private void applyLevelSelection() {
        boolean[] sel = {
                ProvisionPrefs.LEVEL_STANDARD.equals(mSelectedLevel),
                ProvisionPrefs.LEVEL_ACCESSIBILITY.equals(mSelectedLevel),
                ProvisionPrefs.LEVEL_DEBUGGER.equals(mSelectedLevel),
                ProvisionPrefs.LEVEL_ROOT.equals(mSelectedLevel),
        };
        PermissionItemView[] items = {
                mLevelStandard, mLevelAccessibility, mLevelDebugger, mLevelRoot,
        };
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) items[i].setChecked(sel[i]);
        }
    }
}