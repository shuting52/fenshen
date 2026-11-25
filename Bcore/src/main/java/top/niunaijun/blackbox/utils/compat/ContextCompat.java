package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;

import black.android.app.BRContextImpl;
import black.android.app.BRContextImplKitkat;
import black.android.content.AttributionSourceStateContext;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;
import black.android.content.BRContentResolver;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ContextCompat {
    public static final String TAG = "ContextCompat";

    public static void fixAttributionSourceState(Object obj, int uid) {
        if (obj == null) {
            return;
        }
        try {
            Object state = null;
            try {
                if (BRAttributionSource.get(obj)._check_mAttributionSourceState() != null) {
                    state = BRAttributionSource.get(obj).mAttributionSourceState();
                }
            } catch (Throwable ignored) {
            }
            if (state != null) {
                // Android 14+：字段收在 AttributionSourceState 里
                AttributionSourceStateContext attributionSourceStateContext = BRAttributionSourceState.get(state);
                attributionSourceStateContext._set_packageName(BlackBoxCore.getHostPkg());
                attributionSourceStateContext._set_uid(uid);
            } else {
                // Android 14 以下：AttributionSource 是扁平字段，没有 mAttributionSourceState，
                // 直接在对象上改 uid / packageName（各版本字段名有 mUid/uid 两种，挨个试，改不到也不抛错）。
                setFlatField(obj, "mUid", uid);
                setFlatField(obj, "uid", uid);
                setFlatField(obj, "mPackageName", BlackBoxCore.getHostPkg());
                setFlatField(obj, "packageName", BlackBoxCore.getHostPkg());
            }
        } catch (Throwable ignored) {
        }
        try {
            fixAttributionSourceState(BRAttributionSource.get(obj).getNext(), uid);
        } catch (Throwable ignored) {
        }
    }

    private static void setFlatField(Object obj, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.set(obj, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    public static void fix(Context context) {
        try {
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }
            BRContextImpl.get(context)._set_mPackageManager(null);
            try {
                context.getPackageManager();
            } catch (Throwable e) {
                e.printStackTrace();
            }

            BRContextImpl.get(context)._set_mBasePackageName(BlackBoxCore.getHostPkg());
            BRContextImplKitkat.get(context)._set_mOpPackageName(BlackBoxCore.getHostPkg());
            BRContentResolver.get(context.getContentResolver())._set_mPackageName(BlackBoxCore.getHostPkg());

            if (BuildCompat.isS()) {
                // Android 14+ 系统会校验 AttributionSource.uid == 真实 calling uid，
                // 虚拟 uid 直接触发 SecurityException，因此 14+ 统一使用宿主 uid
                int uid = BuildCompat.isUpsideDownCake() ? BlackBoxCore.getHostUid() : BActivityThread.getBUid();
                fixAttributionSourceState(BRContextImpl.get(context).getAttributionSource(), uid);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
