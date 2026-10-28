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
package com.sevtinge.hyperceiler.provision.widget;

import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.sevtinge.hyperceiler.provision.R;

import fan.bottomsheet.BottomSheetBehavior;
import fan.bottomsheet.BottomSheetModal;

/**
 * 权限不全时的警告弹窗（miuix BottomSheet）。
 *
 * ## 这个类为什么存在
 *
 * 原来这里是一个 `android.app.AlertDialog.Builder`（[PermissionSettingsActivity]
 * 的 `onNextAminStart()`）。在 miuix 引导页里，系统 AlertDialog 是块白色
 * 方角弹窗，与前后页面（圆角、大边距、同字体）完全不搭。用户看到的第一
 * 个自定义弹窗就是它，所以必须与协议页统一。
 *
 * ## 为什么用 BottomSheetModal 而不是 Dialog
 *
 * 协议页的 `TermsAndStatementBottomSheet` 已经是 BottomSheetModal，
 * 且这个警告的语义是「从底部升起的临时决定」，不是阻塞式的对话。
 * 同一引导流程里两套弹窗形态更乱。
 *
 * ## 为什么不强制全高
 *
 * 协议页要 `setForceFullHeight(true)`，因为 Markdown 要占满屏；
 * 这里只有三行字与两个按钮，全高会在中间留一大片空白。
 * 所以不调 `setForceFullHeight`，也不 `STATE_EXPANDED`，
 * 用默认的 WRAP_CONTENT 高度（`setFixedHeightRatioEnabled(false)` 保持默认）。
 *
 * ## 为什么不用静态字段缓存 View
 *
 * 上游 `TermsAndStatementBottomSheet` 把 `mProgressBar` / `mMarkdownView`
 * 存成 static（它的生命周期是整个 OOBE 流程，只 new 一次）。这里每次
 * 弹都可能 new 一次，static 会泄漏 Activity。全部用实例字段。
 */
public class PermissionWarningSheet {

    private final FragmentActivity mActivity;
    private final BottomSheetModal mBottomSheet;
    private Runnable mOnContinue;
    private Runnable mOnBack;

    public PermissionWarningSheet(FragmentActivity activity) {
        mActivity = activity;
        mBottomSheet = new BottomSheetModal(activity);
        mBottomSheet.setContentView(R.layout.provision_permission_warning_sheet);
        // 拖拽把手跟手关闭：miuix 默认就是这个手感，显式打开是为了
        // 不跟随上游主题变动（有些主题把把手关掉）。
        mBottomSheet.setDragHandleViewEnabled(true);
        // 点外部区域关闭（等价于“去授予”—— 不继续）。
        mBottomSheet.setCanceledOnTouchOutside(true);

        BottomSheetBehavior<FrameLayout> behavior = mBottomSheet.getBehavior();
        // 只展开一次就能看到全部内容，不需要中间停靠点。
        behavior.setSkipHalfExpanded(true);
        behavior.setSkipCollapsed(true);
        behavior.setDraggable(true);
        // 固定高度比：关掉，让它按内容 wrap。
        behavior.setFixedHeightRatioEnabled(false);

        View root = mBottomSheet.getRootView();

        TextView title = root.findViewById(R.id.permission_warning_title);
        TextView desc = root.findViewById(R.id.permission_warning_desc);
        Button back = root.findViewById(R.id.permission_warning_back);
        Button cont = root.findViewById(R.id.permission_warning_continue);

        title.setText(R.string.provision_permission_warning_title);
        desc.setText(R.string.provision_permission_warning_desc);
        back.setText(R.string.provision_permission_warning_back);
        cont.setText(R.string.provision_permission_warning_continue);

        back.setOnClickListener(v -> {
            dismiss();
            if (mOnBack != null) mOnBack.run();
        });
        cont.setOnClickListener(v -> {
            dismiss();
            if (mOnContinue != null) mOnContinue.run();
        });
        // 点外部关闭也是「不继续」：不能默默当继续，否则用户以为取消了却进了下一步。
        mBottomSheet.setOnDismissListener(() -> {
            if (mOnBack != null) {
                Runnable cb = mOnBack;
                mOnBack = null;
                cb.run();
            }
        });
    }

    /** 点「仍然继续」时执行。 */
    public PermissionWarningSheet onContinue(Runnable action) {
        mOnContinue = action;
        return this;
    }

    /** 点「去授予」或点外部关闭时执行。 */
    public PermissionWarningSheet onBack(Runnable action) {
        mOnBack = action;
        return this;
    }

    public void show() {
        mBottomSheet.show();
    }

    public void dismiss() {
        if (mBottomSheet.isShowing()) {
            mBottomSheet.dismiss();
        }
    }
}
