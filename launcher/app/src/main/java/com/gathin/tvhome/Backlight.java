package com.gathin.tvhome;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * The panel backlight on Xiaomi/Redmi TVs and monitors, 0-100 as their Settings shows it. Xiaomi's Settings sets it
 * through mitv.display.PictureSettingsManager in the TV middleware the firmware ships as a shared library
 * (AndroidManifest uses-library mitvmiddlewareimpl); writing the backLightForNormal setting alone changes nothing.
 * Reflection, so the launcher still runs on TVs without it: there {@link #ready()} stays false.
 */
final class Backlight {

    private static final String TAG = "TvHomeBacklight";
    private static Object manager;
    private static Method get, set;
    private static volatile boolean missing;
    /** Set once a read worked; the first one connects the middleware to the TV service and can take seconds. */
    private static volatile boolean ready;

    private Backlight() {
    }

    private static synchronized boolean init() {
        if (manager == null && !missing) {
            try {
                Class<?> c = Class.forName("mitv.display.PictureSettingsManager");
                get = c.getMethod("getBacklight");
                set = c.getMethod("setBacklight", int.class);
                manager = c.getMethod("getInstance").invoke(null);   // null while the middleware starts: retried
                if (manager != null) Log.i(TAG, "Xiaomi picture settings ready");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                Log.i(TAG, "no Xiaomi picture settings: " + e);
                missing = true;
            } catch (Throwable t) {
                Log.w(TAG, "Xiaomi picture settings not ready", t);
            }
        }
        return manager != null;
    }

    /**
     * Loads the middleware and reads the level once. The first read connects to MediaTek's TV service, which takes
     * seconds when it is slow to start or denied to this app, so call it off the main thread. True when it worked.
     */
    static boolean warmUp() {
        return get() >= 0;
    }

    /** True when this firmware has no Xiaomi picture settings at all (another TV): no point trying again. */
    static boolean missing() {   // not synchronized: the main thread asks while a first read can take seconds
        return missing;
    }

    /**
     * True while reads work: from the first one on the calls are quick (a few ms), so the main thread may use
     * them. A failed read clears it, so a TV service that went away doesn't stall the key handling.
     */
    static boolean ready() {
        return ready;
    }

    /** Current backlight 0-100, or -1 when it can't be read. */
    static int get() {
        if (!init()) return -1;
        try {
            Object r = get.invoke(manager);
            int level = r instanceof Integer ? (Integer) r : -1;
            ready = level >= 0;
            return level;
        } catch (Throwable t) {
            Log.w(TAG, "getBacklight", t);
            ready = false;
            return -1;
        }
    }

    static boolean set(int value) {
        if (!init()) return false;
        try {
            Object r = set.invoke(manager, Math.max(0, Math.min(100, value)));
            return !(r instanceof Boolean) || (Boolean) r;   // boolean on this firmware; trust others that don't say
        } catch (Throwable t) {
            Log.w(TAG, "setBacklight", t);
            return false;
        }
    }
}
