package com.sevtinge.hyperceiler.provision.state;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.common.log.AndroidLog;
import com.sevtinge.hyperceiler.provision.R;
import com.sevtinge.hyperceiler.provision.activity.CongratulationActivity;
import com.sevtinge.hyperceiler.provision.activity.DefaultActivity;
import com.sevtinge.hyperceiler.provision.activity.PermissionSettingsActivity;
import com.sevtinge.hyperceiler.provision.activity.TermsAndStatementActivity;
import com.sevtinge.hyperceiler.provision.utils.PageIntercepHelper;

import java.util.ArrayList;

import fan.provision.OobeUtils;

public class StateMachine {

    private static final String TAG = "StateMachine";

    private static final String PROVISION_STATE = "com.android.provision.STATE_";

    private final Context mContext;

    private State mCurrentState;
    private State mPermissionState;
    private State mTermsAndStatementState;
    private CongratulationState mCompleteState;


    private ArrayList<State> mStateStack;
    private SparseArray<StateInfo> mStates;

    public StateMachine(Context context) {
        mContext = context;
        init();
        //PreLoadManager.get().setCompleteDefaultActivityLoad();
    }

    public Context getContext() {
        return mContext;
    }

    private void init() {
        mStates = new SparseArray<>();
        mStateStack = new ArrayList<>();

        mCurrentState = new StartupState();
        mPermissionState = new PermissionState().setTargetClass(PermissionSettingsActivity.class);
        mTermsAndStatementState = new TermsAndStatementState().setTargetClass(TermsAndStatementActivity.class);
        mCompleteState = new CongratulationState();
        mCompleteState.setTargetClass(CongratulationActivity.class);

        addState(mCurrentState);
        addState(mPermissionState);
        addState(mTermsAndStatementState);
        addState(mCompleteState);

        // [Praxis] 流程：Startup -> Terms -> Permission -> Completion。
        //
        // ## 为什么协议在权限之前
        //
        // HyperCeiler 原顺序是 Startup -> Permission -> Terms -> Basic -> Completion，
        // 即「先授权限，再看协议」。Operit 是「先看协议，再授权限」
        // （见 Operit `MainActivity.setAppContent`：`if (!isAgreementAccepted())`
        // 排在 `else if (showPermissionGuide)` 之前）。
        //
        // 两者差别不只是顺序：Operit 的语义是「不签协议，程序连权限检测都不做」。
        // 本项目按用户要求还原 Operit 流程，所以 Terms 提到 Permission 之前。
        //
        // ## 为什么没有 Basic 页（2026-10-29 摘除）
        //
        // HyperCeiler 的 Basic 页承载的是它自己的 Xposed 模块配置
        // （隐藏桌面图标、MIUI 图标样式、LSPosed 作用域、应用语言），
        // 前四项对 Praxis 没有对应概念。而且该页 `PrefsConfigurator.setup()`
        // 会读 `PrefsBridge`，而 `PrefsBridge` 在本进程从未初始化 ——
        // 点进去必崩（IllegalStateException）。
        //
        // 与其补一个只为了让崩溃消失的初始化、再留一页五项里四项无意义的设置，
        // 直接摘掉这一环：Permission 直接连 Completion。
        //
        // ## 为什么声明顺序不变
        //
        // `mTermsAndStatementState` 与 `mPermissionState` 的变量声明顺序仍是
        // 「权限在前、协议在后」，只有 `setNextState` 的链接顺序是协议在前。
        // 老设备上 `pref_oobe_state` 里可能残留名为 `BasicState` 的存档，
        // `restoreState()` 对 `getStateInfo(state) == null` 已有跳过分支
        // （见 :319 起的 `if (getStateInfo(state) != null)`），所以不会崩。
        setNextState(mCurrentState, mTermsAndStatementState);
        setNextState(mTermsAndStatementState, mPermissionState);
        setNextState(mPermissionState, mCompleteState);
    }

    private void addState(State state) {
        state.setStateMachine(this);
        mStates.put(state.getClass().hashCode(), new StateInfo(state));
        //PreLoadManager.get().addDefaultActivityClass(state);
    }

    public State getState(Class<? extends State> state) {
        return mStates.get(state.hashCode()).getCurrent();
    }

    public StateInfo getStateInfo(State state) {
        return mStates.get(state.getClass().hashCode());
    }

    private StateInfo getStateInfo(String state) {
        if (StartupState.class.getSimpleName().equals(state)) {
            return mStates.get(StartupState.class.hashCode());
        }
        if (PermissionState.class.getSimpleName().equals(state)) {
            return mStates.get(PermissionState.class.hashCode());
        }
        if (TermsAndStatementState.class.getSimpleName().equals(state)) {
            return mStates.get(TermsAndStatementState.class.hashCode());
        }
        if (CongratulationState.class.getSimpleName().equals(state)) {
            return mStates.get(CongratulationState.class.hashCode());
        }
        return null;
    }

    private void setNextState(State state, State nextState) {
        StateInfo stateInfo = mStates.get(state.getClass().hashCode());
        stateInfo.setNext(nextState);
    }

    public void start(boolean z) {
        if (OobeUtils.shouldPersistOobeState(mContext)) {
            restoreState();
        }
        int size = mStateStack.size() - 1;
        if (z) {
            mCurrentState.onEnter(size >= 0 && mStateStack.get(size).canBackTo(), true);
            saveState();
        }
    }

    public void onResult(int i, Intent intent) {
            /*if (mCurrentState == mAccountState) {
                mCloudServiceState.onLoginResult(i, intent);
                mFindDeviceState.onLoginResult(i, intent);
            }*/
    }

    public void enterCurrentState(@Nullable Bundle activityOptions) {
        if (mCurrentState == null) {
            return;
        }
        int size = mStateStack.size() - 1;
        boolean canBack = size >= 0 && mStateStack.get(size).canBackTo();
        mCurrentState.onEnter(canBack, true, activityOptions);
    }

    public void run(int code) {
        AndroidLog.i("Provision_DefaultActivity", "run code: " + code);
        if (!PageIntercepHelper.getInstance().isIngoreCode(code)) {
            switch (code) {
                case -1 -> transitToNext();
                case 0 -> transitToPrevious();
                default -> transitToOthers();
            }
        }
    }

    public State getCurrentState() {
        return mCurrentState;
    }

    public ArrayList<State> getStateStack() {
        return mStateStack;
    }

    private State getNextAvailableState(State state) {
        do {
            State nextState = state.getNextState();
            state = nextState == null ? getStateInfo(state).getNext() : nextState;
            if (state == null) {
                break;
            }
        } while (!state.isAvailable(true));
        AndroidLog.d(TAG, "getNextAvailableState is " + state);
        return state;
    }

    private State getPreviousAvailableState(ArrayList<State> arrayList) {
        while (true) {
            int size = arrayList.size() - 1;
            State state2 = arrayList.get(size);
            if (!state2.canBackTo()) {
                break;
            }
            arrayList.remove(size);
            if (state2.isAvailable(false)) {
                mCurrentState = state2;
                break;
            }
            mCurrentState = state2;
        }
        if (!mCurrentState.isAvailable(false)) {
            AndroidLog.w("Provision_DefaultActivity", mCurrentState + " can not go back, stop here");
        }
        return mCurrentState;
    }

    public void transitToNext() {
        AndroidLog.d("Provision_DefaultActivity", "transitToNext mCurrentState is " + this.mCurrentState);
        State nextState = getNextAvailableState(mCurrentState);
        // [Praxis] 原变量名 deferStartupLeaveForPermission 假定“Startup 后面是
        // Permission”。顺序调整后 Startup 后面变成 Terms，条件本身仍然正确
        // （判断的是「当前是 Startup 且下一步要离开它」这个语义），
        // 但变量名会误导，改名并保留同一逻辑。
        //
        // 为什么保留这个延迟：StartupFragment 的 next 按钮在点击后还要跑
        // 共享元素放大动画，此时若立刻 onLeave()，GlowController 会被 stop，
        // 光晕当场消失，动画背景就变成黑屏。
        boolean deferStartupLeave = mCurrentState instanceof StartupState;
        if (!deferStartupLeave) {
            mCurrentState.onLeave();
        }
        if (nextState == null) {
            DefaultActivity activity = (DefaultActivity) mContext;
            activity.finishSetup();
            clearState();
            return;
        }
        mStateStack.add(mCurrentState);
        mCurrentState = nextState;

        // [Praxis] 这个特判原本是 PermissionState，用于跳过转场动画。
        //
        // 原顺序里 PermissionState 紧接 StartupState，而 Startup 自己有一套
        // 「按下 next 按钮后从按钮位置放大」的共享元素动画（见
        // StartupFragment.launchPermissionPickPage），所以进权限页时不能再叠一层
        // overridePendingTransition，否则两套动画打架。
        //
        // 顺序改成 Terms 在 Permission 之前后，“紧接 Startup 的那一步”变成了
        // TermsAndStatementState，所以特判对象必须跟着换，否则第一个真实转场
        // 会丢失动画、而权限页会多出一层冲突动画。
        if (mCurrentState instanceof TermsAndStatementState) {
            AndroidLog.d(TAG, "transitToNext: TermsAndStatementState (from Startup)");
        } else {
            mCurrentState.onEnter(mCurrentState.canBackTo(), true);
            ((DefaultActivity) mContext).overridePendingTransition(R.anim.provision_slide_in_right, R.anim.provision_slide_out_left);
        }
        saveState();
    }

    private boolean needFinish() {
        return getNextAvailableState(mCurrentState) == null;
    }

    private void transitToPrevious() {
        if (mStateStack.size() <= 0) return;
        mCurrentState = getPreviousAvailableState(mStateStack);
        if (mCurrentState instanceof StartupState) {
            ((StartupState) mCurrentState).setBooted(true);
        } else {
            mCurrentState.onLeave();
        }
        int size = mStateStack.size() - 1;
        mCurrentState.onEnter(size >= 0 && mStateStack.get(size).canBackTo(), false);
        // [Praxis] 与 transitToNext 同一处特判，随顺序调整同步改成 Terms。
        if (!(mCurrentState instanceof TermsAndStatementState)) {
            ((DefaultActivity) this.mContext).overridePendingTransition(R.anim.provision_slide_in_left, R.anim.provision_slide_out_right);
        }
        saveState();
    }

    private void transitToOthers() {
        State nextState = mCurrentState.getNextState();
        if (nextState != null && nextState != mCompleteState) {
            mStateStack.add(mCurrentState);
            mCurrentState = nextState;
            int size = mStateStack.size() - 1;
            mCurrentState.onEnter(size >= 0 && mStateStack.get(size).canBackTo(), true);
            saveState();
        }
    }

    private void clearState() {
        if (!OobeUtils.shouldPersistOobeState(mContext)) {
            return;
        }
        mContext.getSharedPreferences("pref_oobe_state", 0).edit().clear().apply();
    }

    public void resumeState() {
        ((Activity) mContext).overridePendingTransition(17432578, 17432579);
        if (mCurrentState != null && mCurrentState.isAvailable(true)) {
            mCurrentState.onEnter(true, true);
            saveState();
        } else {
            transitToPrevious();
        }
    }

    private void saveState() {
        if (!OobeUtils.shouldPersistOobeState(mContext)) {
            return;
        }
        SharedPreferences.Editor edit = mContext.getSharedPreferences("pref_oobe_state", 0).edit();
        edit.clear();
        for (int i = 0; i < mStateStack.size(); i++) {
            AndroidLog.w(TAG, " saveState is " + mStateStack.get(i).getClass().getSimpleName());
            edit.putString(PROVISION_STATE + i, mStateStack.get(i).getClass().getSimpleName());
        }
        edit.putString(PROVISION_STATE + mStateStack.size(), mCurrentState.getClass().getSimpleName());
        edit.apply();
    }

    private void restoreState() {
        int i = 0;
        SharedPreferences prefState = mContext.getSharedPreferences("pref_oobe_state", 0);
        String state = StartupState.class.getSimpleName();
        mCurrentState = getStateInfo(state).getCurrent();
        while (state != null) {
            state = null;
            try {
                state = prefState.getString(PROVISION_STATE + i, null);
            } catch (Exception ignored) {}
            if (state != null) {
                if (i != 0) {
                    mStateStack.add(mCurrentState);
                }
                AndroidLog.w(TAG, " state is " + state + " and getStateInfo(state) is " + getStateInfo(state));
                if (getStateInfo(state) != null) {
                    mCurrentState = getStateInfo(state).getCurrent();
                }
            }
            i++;
        }
        if (!mCurrentState.isAvailable(true)) {
            mCurrentState = getPreviousAvailableState(mStateStack);
        }
    }
}
