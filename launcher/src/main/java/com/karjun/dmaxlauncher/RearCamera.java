package com.karjun.dmaxlauncher;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The head unit's analog rear (reverse) camera, shown on demand.
 *
 * The iMotor reverse app (com.imotor.backcar) does this: request "camera focus"
 * from com.imotor.framework.camera.IMCameraManager (type 2), then hand a Surface
 * to the system service "com.imotor.avm.IMCameraService"
 * (interface com.imotor.camera.IMCameraService, transaction 1 = setSurface).
 * We do the same by reflection / raw Binder calls. Head unit only.
 */
final class RearCamera {
    static final String TAG = "CarHomeRear";
    private static final String SERVICE = "com.imotor.avm.IMCameraService";
    private static final String DESCRIPTOR = "com.imotor.camera.IMCameraService";
    private static final int TRANSACTION_SET_SURFACE = 1;
    private static final int FOCUS_TYPE_RVC = 2;   // what com.imotor.backcar requests
    private static final int CHANNEL_RVC = 1;      // IMCameraManager.CHANNEL_RVC

    private final Object mManager;
    private final Object mFocusListener;

    static RearCamera create(Context context) {
        try {
            Class<?> cls = Class.forName("com.imotor.framework.camera.IMCameraManager");
            Object manager = cls.getMethod("getInstance", Context.class).invoke(null, context.getApplicationContext());
            return manager == null || service() == null ? null : new RearCamera(manager);
        } catch (Throwable t) {
            return null;
        }
    }

    private RearCamera(Object manager) throws ClassNotFoundException {
        mManager = manager;
        Class<?> listener = Class.forName("com.imotor.framework.camera.IMCameraManager$OnCameraStateListener");
        mFocusListener = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (p, m, a) -> {
            if ("onCameraFocusStateChanged".equals(m.getName())) Log.d(TAG, "focus state " + a[0]);
            if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
            if ("equals".equals(m.getName())) return p == a[0];
            if ("toString".equals(m.getName())) return "CarHomeRearFocus";
            return null;
        });
    }

    /** True if the rear camera currently has a video signal. */
    boolean hasSignal() {
        Object r = call("getSignalState", CHANNEL_RVC);
        return r instanceof Boolean && (Boolean) r;
    }

    /** Takes camera focus and starts drawing the rear camera into {@code surface}. */
    boolean show(Surface surface) {
        Object focus = call("requestCameraFocus", FOCUS_TYPE_RVC, mFocusListener);
        Log.d(TAG, "requestCameraFocus -> " + focus + ", signal=" + hasSignal());
        int r = setSurface(surface);
        Log.d(TAG, "setSurface -> " + r);
        return r >= 0;
    }

    /** Stops drawing and gives the camera back (the reverse app takes it again when needed). */
    void hide() {
        setSurface(null);
        Log.d(TAG, "releaseCameraFocus -> " + call("releaseCameraFocus", FOCUS_TYPE_RVC, mFocusListener));
    }

    private static IBinder service() {
        try {
            Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
            return (IBinder) get.invoke(null, SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "no " + SERVICE, t);
            return null;
        }
    }

    private static int setSurface(Surface surface) {
        IBinder b = service();
        if (b == null) return -1;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (surface != null) {
                data.writeInt(1);
                surface.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            b.transact(TRANSACTION_SET_SURFACE, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "setSurface failed", t);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private Object call(String method, Object... args) {
        try {
            for (Method m : mManager.getClass().getMethods()) {
                if (m.getName().equals(method) && m.getParameterTypes().length == args.length) {
                    return m.invoke(mManager, args);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, method + " failed", t);
        }
        return null;
    }
}
