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
package com.sevtinge.hyperceiler.provision.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 引导链需要跨模块传的两件事：**协议是否已接受**、**选了哪一档权限级别**。
 *
 * <h2>为什么不用 `SettingsRepository`（DataStore）</h2>
 *
 * `ui:provision` 是纯 Android 库，`SettingsRepository` 在 `:core:data`，
 * 而 `:core:data` 依赖 Room / DataStore / Koin。让 `:ui:provision` 依赖它，
 * 等于把一个「1:1 搬运的上游模块」绑死在本项目的持久化实现上：
 * 一旦上游更新、或这边换存储方案，provision 就得跟着改。
 *
 * 更关键的是**依赖方向**：`ui:provision` 是被 `:app` 消费的下层，
 * 让它反过来依赖 `:core:data` 会在架构图上形成 `ui -> core:data`
 * 的越级依赖（`ui` 层不该知道数据层怎么存）。
 *
 * <h2>所以这里只做「暂存」</h2>
 *
 * - 引导期间：由本类写 SharedPreferences（同一进程内立即可读）
 * - 引导结束：`MainActivity` 在 `${@code onResume}` 时读走这两个值，
 *   转存进 `SettingsRepository`（DataStore），然后由 Repository 作为
 *   唯一真相来源。此后本类不再被读取。
 *
 * 这样 provision 保持「不知道宿主怎么存」的状态，而宿主拿到值后有
 * 完整控制权（做迁移、做校验、做派生）。
 *
 * <h2>为什么不复用 `OobeUtils` 的 sp</h2>
 *
 * `OobeUtils` 用的是 `pref_oobe_state`，那个文件被 `StateMachine.saveState()`
 * 整块 `clear()` 后再重写（见 `StateMachine.saveState`），只在末尾才写
 * `is_provisioned`。若把权限级别也塞进去，会在中途的 `clear()` 里被抹掉。
 * 所以单独开一个文件，互不干扰。
 */
public final class ProvisionPrefs {

    /** 与 Praxis `PraxisPermissionLevel.name` 完全一致的四档（ADMIN 不在引导页给）。 */
    public static final String LEVEL_STANDARD = "STANDARD";
    public static final String LEVEL_ACCESSIBILITY = "ACCESSIBILITY";
    public static final String LEVEL_DEBUGGER = "DEBUGGER";
    public static final String LEVEL_ROOT = "ROOT";

    private static final String PREFS_NAME = "praxis_provision";
    private static final String KEY_PERMISSION_LEVEL = "permission_level";
    private static final String KEY_AGREEMENT_VERSION = "agreement_version";

    /**
     * 协议版本号，语义与 Operit 的 `AgreementPreferences.CURRENT_AGREEMENT_VERSION` 相同：
     * **版本号精确比对**而不是布尔值 —— 改协议只要 bump 这里，老用户自动重走。
     */
    public static final String CURRENT_AGREEMENT_VERSION = "2026-10-28";

    private ProvisionPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── 权限级别 ──────────────────────────────────────────────────

    /** 读用户选的档位；没选过返回 {@link #LEVEL_STANDARD}（与 Operit 的默认一致）。 */
    public static String readPermissionLevel(Context context) {
        String v = prefs(context).getString(KEY_PERMISSION_LEVEL, null);
        if (v == null) return LEVEL_STANDARD;
        // 只接受四档，其余（含脏数据）回退 STANDARD —— 保守而非报错。
        if (LEVEL_ACCESSIBILITY.equals(v) || LEVEL_DEBUGGER.equals(v) || LEVEL_ROOT.equals(v)) {
            return v;
        }
        return LEVEL_STANDARD;
    }

    /** 是否**选过**（区别于「选了 STANDARD」）。宿主用它决定要不要覆盖已有设置。 */
    public static boolean hasPermissionLevel(Context context) {
        return prefs(context).contains(KEY_PERMISSION_LEVEL);
    }

    public static void writePermissionLevel(Context context, String level) {
        prefs(context).edit().putString(KEY_PERMISSION_LEVEL, level).apply();
    }

    // ── 协议 ──────────────────────────────────────────────────────

    /** 与 Operit 同语义：存的版本号必须**精确等于**当前版本才算已接受。 */
    public static boolean isAgreementAccepted(Context context) {
        return CURRENT_AGREEMENT_VERSION.equals(
                prefs(context).getString(KEY_AGREEMENT_VERSION, null));
    }

    public static void acceptAgreement(Context context) {
        prefs(context).edit().putString(KEY_AGREEMENT_VERSION, CURRENT_AGREEMENT_VERSION).apply();
    }
}