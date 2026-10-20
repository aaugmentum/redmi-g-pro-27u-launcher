package com.gathin.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Puts the keyboard you chose back after the firmware force-stops it. HyperOS force-stops every third-party app at
 * standby (see {@link HomeKeyRestorer}), and Android answers a force-stop of the current keyboard's app by switching
 * to a system one: on China HyperOS that is Sogou, which can't type English or Russian on a TV and often won't even
 * start. Called when this app comes back after standby, with WRITE_SECURE_SETTINGS (granted by the install script).
 * A third-party keyboard that is the default is remembered; while a system keyboard is the default, the remembered
 * one (or else the first enabled third-party keyboard) is made the default again.
 */
final class Keyboards {

    private static final String TAG = "TvHomeKeyboard";
    private static final String PREF_KEYBOARD = "keyboard";

    private Keyboards() {
    }

    static void restore(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        ContentResolver cr = context.getContentResolver();
        SharedPreferences prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
        String current = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
        if (current != null && !current.isEmpty() && !isSystem(context, current)) {
            if (!current.equals(prefs.getString(PREF_KEYBOARD, null))) {
                prefs.edit().putString(PREF_KEYBOARD, current).apply();
            }
            return;
        }
        String enabled = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
        if (enabled == null) return;
        String saved = prefs.getString(PREF_KEYBOARD, null), choice = null;
        for (String entry : enabled.split(":")) {
            String id = entry.split(";")[0];   // an entry can carry subtypes: id;subtype;subtype
            if (id.isEmpty() || isSystem(context, id)) continue;
            if (id.equals(saved)) {
                choice = id;
                break;
            }
            if (choice == null) choice = id;
        }
        if (choice == null) return;
        try {
            Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, choice);
            Log.i(TAG, "keyboard back to " + choice + " (was " + current + ")");
        } catch (SecurityException e) {
            Log.w(TAG, "can't set the keyboard", e);
        }
    }

    /** True for a system keyboard, and for one that is gone (so it is never picked). */
    private static boolean isSystem(Context context, String imeId) {
        ComponentName c = ComponentName.unflattenFromString(imeId);
        if (c == null) return true;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(c.getPackageName(), 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }
}
