package com.karjun.dmaxlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

/**
 * The head unit's FM/AM tuner. It plays through the MCU, not Android, so it
 * has no useful media session; the original iMotor launcher reads it through
 * the firmware's com.imotor.framework.radio.RadioManager (on the unit's boot
 * classpath), and so do we, by reflection.
 *
 * On devices without that class (phones) {@link #create} returns null.
 */
final class RadioSource {
    static final String TAG = "CarHomeRadio";
    private static final String MANAGER = "com.imotor.framework.radio.RadioManager";
    private static final String LISTENER = "com.imotor.framework.radio.RadioStatusListener";

    /** What the radio is tuned to. */
    static final class Station {
        final String band;   // "FM" / "AM"
        final String freq;   // "89.9"
        final String unit;   // "MHz" / "kHz"
        final String name;   // RDS station name, may be empty

        Station(String band, String freq, String unit, String name) {
            this.band = band;
            this.freq = freq;
            this.unit = unit;
            this.name = name;
        }
    }

    interface Listener {
        /** Main thread; null when nothing is tuned. */
        void onStation(Station station);
    }

    private static final Object FAILED = new Object();

    private final Object mManager;
    private final Object mProxy;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private Listener mListener;
    private boolean mRegistered;
    private String mPs = "";

    static RadioSource create(Context context) {
        try {
            Class<?> cls = Class.forName(MANAGER);
            Object manager = cls.getMethod("getInstance", Context.class).invoke(null, context.getApplicationContext());
            return manager == null ? null : new RadioSource(manager);
        } catch (Throwable t) {
            return null; // not an iMotor unit
        }
    }

    private RadioSource(Object manager) throws ClassNotFoundException {
        mManager = manager;
        Class<?> listener = Class.forName(LISTENER);
        mProxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "onStationChanged":
                    Log.d(TAG, "onStationChanged " + describe(args[0]));
                    publish(args[0]);
                    return null;
                case "onRadioStatusChanged":
                    Object ps = call(args[0], "getPsName");
                    Log.d(TAG, "onRadioStatusChanged ps=" + ps);
                    if (ps instanceof String) {
                        mPs = ((String) ps).trim();
                        publish(call(mManager, "getCurrentStation"));
                    }
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "CarHomeRadioListener";
                default:
                    return null;
            }
        });
    }

    void start(Listener listener) {
        mListener = listener;
        if (!mRegistered) mRegistered = call(mManager, "registerListener", mProxy) != FAILED;
        Object st = call(mManager, "getCurrentStation");
        Log.d(TAG, "start: playingFreq=" + call(mManager, "getPlayingFreq") + " band=" + call(mManager, "getBand")
                + " station=" + describe(st));
        publish(st);
    }

    void stop() {
        mListener = null;
        if (mRegistered) {
            call(mManager, "unregisterListener", mProxy);
            mRegistered = false;
        }
    }

    /** Next / previous preset, like the radio app's |< >| buttons. */
    void step(boolean next) {
        call(mManager, next ? "seekToNext" : "seekToPrev");
    }

    private void publish(Object stationInfo) {
        Station station = toStation(stationInfo);
        mMain.post(() -> {
            if (mListener != null) mListener.onStation(station);
        });
    }

    private Station toStation(Object info) {
        if (info == null || info == FAILED) return null;
        Object freqObj = call(info, "getFreq");
        if (!(freqObj instanceof Integer)) return null;
        int freq = (Integer) freqObj;
        if (freq <= 0) return null;
        Object ps = field(info, "mPS");
        String name = ps instanceof String && !((String) ps).trim().isEmpty() ? ((String) ps).trim() : mPs;
        Object mode = call(info, "getBandMode");
        Object amConst = staticField("com.imotor.framework.radio.StationInfo", "BAND_MODE_AM");
        boolean am = mode instanceof Integer && amConst instanceof Integer
                ? mode.equals(amConst) : freq < 2000; // AM 531–1710 kHz vs FM 8750–10800
        if (am) return new Station("AM", String.valueOf(freq), "kHz", name);
        // FM in 10 kHz steps (8990 = 89.90 MHz); some firmwares use kHz.
        double mhz = freq > 50_000 ? freq / 1000.0 : freq / 100.0;
        String s = String.format(Locale.ROOT, "%.2f", mhz);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return new Station("FM", s, "MHz", name);
    }

    // ---- reflection helpers -------------------------------------------------------------------

    private static Object call(Object target, String method, Object... args) {
        if (target == null || target == FAILED) return FAILED;
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(method) && m.getParameterTypes().length == args.length) {
                    return m.invoke(target, args);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, method + " failed", t);
        }
        return FAILED;
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object staticField(String cls, String name) {
        try {
            return Class.forName(cls).getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String describe(Object st) {
        if (st == null) return "null";
        if (st == FAILED) return "FAILED";
        return "freq=" + call(st, "getFreq") + " mode=" + call(st, "getBandMode") + " band=" + call(st, "getBand")
                + " ps=" + field(st, "mPS");
    }
}
