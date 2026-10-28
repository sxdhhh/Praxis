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
package fan.provision;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.sevtinge.hyperceiler.provision.IAnimCallback;
import com.sevtinge.hyperceiler.provision.IProvisionAnim;

public class ProvisionAnimHelper {

    private static final String TAG = "ProvisionAnimHelper";

    private int mAnimY;
    private int mSkipOrNext = 0;

    private final Context mContext;
    private final Handler mHandler;
    private AnimListener mAnimListener;

    private IProvisionAnim mProxy;
    private final IAnimCallback mCallback = new IAnimCallback.Stub() {
        @Override
        public void onNextAminStart() {
            Log.d(TAG, "onNextAminStart: " + mSkipOrNext);
            if (mHandler != null) {
                mHandler.post(() -> {
                    if (mAnimListener != null) {
                        switch (mSkipOrNext) {
                            case 0 -> mAnimListener.onNextAminStart();
                            case 1 -> mAnimListener.onSkipAminStart();
                        }
                    }
                });
            }
        }

        @Override
        public void onBackAnimStart() {
            Log.d(TAG, "onBackAnimStart");
            if (mHandler != null) {
                mHandler.postDelayed(() -> {
                    if (mAnimListener != null) {
                        mAnimListener.onBackAnimStart();
                    }
                }, 30L);
            }
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mProxy = IProvisionAnim.Stub.asInterface(service);
            try {
                mProxy.registerRemoteCallback(mCallback);
                mAnimListener.onAminServiceConnected();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.getAction().equals("fan.action.PROVISION_ANIM_END") &&
                    mAnimListener != null) {
                mAnimListener.onAminEnd();
            }
        }
    };

    public interface AnimListener {

        void onAminServiceConnected();

        void onBackAnimStart();

        void onNextAminStart();

        void onSkipAminStart();

        void onAminEnd();
    }

    public ProvisionAnimHelper(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    public void setAnimListener(AnimListener listener) {
        mAnimListener = listener;
    }

    public boolean isAnimEnded() {
        try {
            return mProxy.isAnimEnd();
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
    }

    public boolean goNextStep(int i) {
        try {
            mSkipOrNext = i;
            mProxy.playNextAnim(mAnimY);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean goBackStep() {
        try {
            mProxy.playBackAnim(mAnimY);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void setAnimY(int i) {
        mAnimY = i;
    }

    public void registerAnimService() {
        if (mContext == null) {
            Log.e(TAG, "registerAnimService context is null");
            return;
        }
        mContext.registerReceiver(mReceiver, new IntentFilter("fan.action.PROVISION_ANIM_END"), Context.RECEIVER_EXPORTED);

        // [Praxis] 这里原来写的是：
        //   Intent intent = new Intent("fan.intent.action.OOBSERVICE");
        //   intent.setPackage("com.sevtinge.hyperceiler");
        //   mContext.bindService(intent, mConnection, 1);
        //
        // 两处都要改，否则点「下一步」必崩：
        //
        // 1) **包名硬编码成 HyperCeiler 的**。本应用的包名是
        //    `dev.praxis.debug` / `dev.praxis`，系统直接抛
        //    `SecurityException: Not allowed to bind to service
        //     Intent { act=fan.intent.action.OOBSERVICE
        //     xflg=0x4 pkg=com.sevtinge.hyperceiler }`，
        //    因为它不允许跨包去 bind 一个别的应用的私有 service。
        //    改为 `mContext.getPackageName()`，随构建类型自动正确。
        //
        // 2) **service 是 exported=false**（见 AndroidManifest 的
        //    `.service.ProvisionAnimService`）。Android 5.0 起，
        //    exported=false 的组件不接受**隐式** Intent 绑定，
        //    即使包名对了也会被拒。本 service 就在当前进程内，
        //    正确做法是用显式 ComponentName 指定类名。
        //
        // 顺带：bindService 包 try-catch。这一层是「动画加速」的锦上添花，
        // 它失败不该让整个引导页崩掉 —— 上面那版没包，所以一次
        // bind 失败就把 Activity 打死了。
        try {
            Intent intent = new Intent("fan.intent.action.OOBSERVICE");
            intent.setComponent(new ComponentName(
                    mContext.getPackageName(),
                    "com.sevtinge.hyperceiler.provision.service.ProvisionAnimService"));
            mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "registerAnimService: bindService failed, animation falls back to local", t);
        }
    }

    public void unregisterAnimService() {
        // [Praxis] 三处都要判空：
        //
        // `registerAnimService` 一旦 bind 失败（或还没 bind 完就 onStop），
        // `mProxy` 会是 null。原代码直接解引用，会再抛一次 NPE ——
        // 而那次 NPE 发生在 onStop，用户看到的就是「点下一步闪退之后
        // 再点一次也闪退」。
        if (mProxy != null) {
            try {
                mProxy.unregisterRemoteCallback(mCallback);
            } catch (Exception e) {
                Log.w(TAG, "unregisterAnimService: unregister callback failed", e);
            }
        }
        if (mContext != null) {
            try {
                mContext.unbindService(mConnection);
            } catch (Exception e) {
                // 没 bind 过就 unbind 会抛 IllegalArgumentException，忽略即可。
                Log.w(TAG, "unregisterAnimService: unbindService failed", e);
            }
            try {
                mContext.unregisterReceiver(mReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregisterAnimService: unregisterReceiver failed", e);
            }
        }
    }
}
