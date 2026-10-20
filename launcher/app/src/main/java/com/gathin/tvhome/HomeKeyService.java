package com.gathin.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends the remote's Home button to this launcher where Android won't. On remotes that emit KEYCODE_ALL_APPS for
 * Home (Xiaomi sticks and boxes), which Android TV 9 routes only to the system-image launcher. And on firmware
 * that refuses a third-party default home (Xiaomi China HyperOS: "addPreferredActivity: Don't set 3-app default
 * home"): there it eats the Home key and opens MainActivity, and bounces the stock home screen whenever the
 * system shows it anyway (boot, apps that launch it directly). Other keys pass through, except the ones the
 * brightness bar or the input picker takes while it is showing.
 * Enable over adb (this replaces the list; if `settings get secure enabled_accessibility_services` shows other
 * services you want to keep, append ours after a ':' instead):
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 * The remote's TV button opens the brightness bar ({@link BrightnessOverlay}); held, the input picker
 * ({@link InputsOverlay}).
 * HyperOS force-stops every third-party app at standby, and Android then turns this service off; to have the
 * launcher turn it back on (see {@link HomeKeyRestorer}), also run:
 *   pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS
 *   cmd notification allow_listener com.gathin.tvhome/.HomeKeyRestorer
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "TvHomeKey";
    /**
     * Set when the service is switched off (Accessibility settings, restore.sh), so {@link #ensureEnabled} leaves
     * it off. A force-stop kills the process without an unbind, so it never sets this.
     */
    private static final String PREF_OFF = "home_key_off";

    /** Stock home activities to replace; other screens of the same app (account login etc.) are left alone. */
    private static final String[] STOCK_HOMES = {
            "com.mitv.tvhome.MainActivityUserMode",
            "com.xiaomi.mitv.settings.entry.FallbackHome",
    };
    /** Its app; only checked when the service connects, when the activity class is not known. */
    private static final String STOCK_HOME_PKG = "com.mitv.tvhome";
    /**
     * The TV button of the Xiaomi Bluetooth remote: brightness bar, or held, the input picker. It sends KEY_GRAVE,
     * which the remote's key layout maps to 317, Xiaomi's MACRO_4 (the SDK's KEYCODE_MACRO_4 is 316). The
     * firmware's own use, reopening the current external input, is covered by the picker.
     */
    private static final int TV_KEY = 317;
    /** How long TV is held for the input picker instead of the brightness bar. */
    private static final long HOLD_MS = 500;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BrightnessOverlay brightness;
    private InputsOverlay inputs;
    /**
     * Whether this press of TV, or of Home, is ours. Decided on its down and kept for its repeats and up, so a
     * press is never split between this service and the firmware (the dream ends, the backlight comes up mid-press).
     */
    private boolean tvTaken, homeTaken;
    /** This TV press was held long enough to open the picker; its up then does nothing more. */
    private boolean tvHeld;
    /** The key whose down an overlay took: its up goes there too, and stays from the app if the overlay has closed. */
    private int overlayKey = -1;
    /** Package of the activity in front, from window changes (no call into that app on a key press); null at first. */
    private String frontPackage;
    private final AtomicBoolean warming = new AtomicBoolean();
    private final Runnable tvHold = () -> {
        tvHeld = true;
        brightness.dismiss();
        inputs.show(playerInFront());
    };

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        if (code == TV_KEY) {
            if (first) {
                // Not over the clock screensaver: let the key through to wake it, as Home is. Nor before the
                // backlight has answered once, unless the picker can be shown: don't wait for it here.
                tvTaken = !ClockDream.showing && (Backlight.ready() || inputs.available());
                tvHeld = false;
                if (tvTaken && inputs.available()) handler.postDelayed(tvHold, HOLD_MS);
            }
            if (!tvTaken) return false;
            if (!down) {
                tvTaken = false;
                handler.removeCallbacks(tvHold);
                if (!tvHeld && !event.isCanceled()) shortTvPress();
            }
            return true;
        }
        if (!down && code == overlayKey) {
            overlayKey = -1;
            if (brightness.isShowing()) brightness.onKey(event);
            else if (inputs.isShowing()) inputs.onKey(event);
            return true;
        }
        if (down && (brightness.isShowing() && brightness.onKey(event) || inputs.isShowing() && inputs.onKey(event))) {
            overlayKey = code;
            return true;
        }
        if (code != KeyEvent.KEYCODE_ALL_APPS && code != KeyEvent.KEYCODE_HOME) return false;
        // During the clock screensaver let Home through: the key wakes the dream, and the stock home the
        // system then shows gets bounced below. Eating it would leave the clock up.
        if (first) homeTaken = code == KeyEvent.KEYCODE_ALL_APPS || !ClockDream.showing;
        if (!homeTaken) return false;
        if (!down) {
            homeTaken = false;
            if (!event.isCanceled()) goHome();
        }
        return true;
    }

    /** TV pressed and let go: closes whichever is open, else opens the brightness bar (the picker without one). */
    private void shortTvPress() {
        if (inputs.isShowing()) inputs.hide();
        else if (brightness.isShowing()) brightness.hide();
        else if (Backlight.ready()) brightness.toggle();
        else {
            if (!Backlight.missing()) warmUpBacklight(1);   // it may be up by now; then the next press gets the bar
            inputs.show(playerInFront());
        }
    }

    /**
     * Reads the backlight off the main thread (key events time out after 500 ms, and the first read can take
     * seconds), again every 10 s until it answers, up to {@code tries} times. One at a time.
     */
    private void warmUpBacklight(int tries) {
        if (!warming.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                BrightnessOverlay.font();
                for (int i = 0; i < tries && !Backlight.warmUp() && !Backlight.missing(); i++) Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // the service is going
            } finally {
                warming.set(false);
            }
        }, "backlight-init").start();
    }

    /**
     * Events from before the bind are never delivered, so when the stock home is already in front (boot, or
     * this app was just updated) nothing would bounce it until Home is pressed. Check the screen once, again a
     * second later if no window was ready. Only the package is known here, so unlike the event path this also
     * replaces the stock app's other screens (account login, agreements), which nothing on this setup opens.
     */
    @Override
    protected void onServiceConnected() {
        prefs(this).edit().putBoolean(PREF_OFF, false).apply();
        Keyboards.restore(this);
        brightness = new BrightnessOverlay(this);
        inputs = new InputsOverlay(this, this::goHome);
        warmUpBacklight(6);   // at boot the TV service may not be up yet: about a minute
        if (!bounceStockHomeInFront()) handler.postDelayed(this::bounceStockHomeInFront, 1000);
    }

    /** Whether Xiaomi's source player is in front, i.e. a port is on screen rather than an app. */
    private boolean playerInFront() {
        return frontPackage == null || XiaomiInputs.PLAYER.equals(frontPackage);
    }

    /** False when there was no window to look at. */
    private boolean bounceStockHomeInFront() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        if (frontPackage == null) frontPackage = String.valueOf(root.getPackageName());
        if (STOCK_HOME_PKG.equals(String.valueOf(root.getPackageName()))) goHome();
        return true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        if (brightness != null) brightness.dismiss();
        if (inputs != null) inputs.dismiss();
        prefs(this).edit().putBoolean(PREF_OFF, true).commit();   // before the process can go
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getClassName() == null) return;
        String cls = event.getClassName().toString();
        CharSequence pkg = event.getPackageName();
        if (pkg != null && isActivity(pkg.toString(), cls)) frontPackage = pkg.toString();   // not dialogs, popups
        for (String home : STOCK_HOMES) {
            if (home.equals(cls)) {
                goHome();
                return;
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    private boolean isActivity(String pkg, String cls) {
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void goHome() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /**
     * Turns this service back on after Android dropped it from the enabled services, which it does when the app
     * is force-stopped. Other enabled services are kept. Does nothing if it was switched off on purpose, or
     * without WRITE_SECURE_SETTINGS (adb grant).
     */
    static void ensureEnabled(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED || prefs(context).getBoolean(PREF_OFF, false)) return;
        ContentResolver cr = context.getContentResolver();
        ComponentName me = new ComponentName(context, HomeKeyService.class);
        String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean listed = false;
        if (list != null) {
            for (String s : list.split(":")) {
                if (me.equals(ComponentName.unflattenFromString(s))) {
                    listed = true;
                    break;
                }
            }
        }
        try {
            if (!listed) {
                String mine = me.flattenToShortString();
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        list == null || list.isEmpty() ? mine : list + ":" + mine);
                Log.i(TAG, "re-enabled " + mine);
            }
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "can't re-enable the Home redirect", e);
        }
    }
}
