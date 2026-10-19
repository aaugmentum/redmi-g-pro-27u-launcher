package com.gathin.tvhome;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;

/**
 * The brightness bar the remote's TV button opens (see HomeKeyService), a mirror of the firmware's volume bar on
 * the left edge, so the two never cover each other. Up/Down change the panel backlight; it hides after a few
 * seconds, on OK or Back, or when any other key goes on to the app below. Volume +/- can't be used: the firmware
 * handles them before accessibility services see them. An accessibility overlay, so it shows over every app and
 * input, DisplayPort included. The light sensor is left on, as Xiaomi's own slider does, so it may move the level
 * again when the room light changes.
 */
final class BrightnessOverlay {

    private static final String TAG = "TvHomeBacklight";
    private static final int STEP = 2;   // of 0-100; holding Up/Down repeats
    private static final long HIDE_MS = 3000;
    private static final long FADE_MS = 180;
    /** Xiaomi's UI font, which the volume bar's number uses; not a system font family, so loaded from its file. */
    private static final String FONT = "/system/fonts/MiSans-Semibold.otf";
    private static Typeface font;
    private static boolean fontTried;

    private final Context context;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hide;
    private View root;
    private LevelBar bar;
    private TextView value;
    private int level;

    BrightnessOverlay(Context service) {
        context = new ContextThemeWrapper(service, android.R.style.Theme_Material);
        wm = service.getSystemService(WindowManager.class);
    }

    /** Null where the firmware doesn't have it (the layout's bold sans-serif stays). Call it once off the main thread. */
    static synchronized Typeface font() {
        if (!fontTried) {
            fontTried = true;
            File file = new File(FONT);
            if (file.canRead()) {
                try {
                    font = Typeface.createFromFile(file);
                } catch (RuntimeException e) {
                    Log.w(TAG, "can't load " + FONT, e);
                }
            }
        }
        return font;
    }

    boolean isShowing() {
        return root != null;
    }

    void toggle() {
        if (isShowing()) hide();
        else show();
    }

    private void show() {
        int now = Backlight.get();   // read every time: the light sensor may have moved it
        if (now < 0) {
            Log.w(TAG, "can't read the backlight");
            return;
        }
        level = now;
        View view = LayoutInflater.from(context).inflate(R.layout.brightness_bar, null);
        bar = view.findViewById(R.id.bar);
        value = view.findViewById(R.id.value);
        Typeface number = font();
        if (number != null) {
            value.setTypeface(number);
            // The layout placed the baseline with the default font's metrics; place it again with this one's.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                value.setFirstBaselineToTopHeight(context.getResources().getDimensionPixelSize(R.dimen.brightness_baseline));
            }
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lp.x = Math.round(30 * context.getResources().getDisplayMetrics().density);   // the volume bar's, from the right
        // Faded here rather than with a window animation, so dismiss() can take it away at once.
        view.setAlpha(0f);
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {   // e.g. the service is being unbound; don't take the input filter down
            Log.w(TAG, "can't show the brightness bar", e);
            return;
        }
        root = view;
        view.animate().alpha(1f).setDuration(FADE_MS);
        update(false);
        scheduleHide();
    }

    /** Fades out. */
    void hide() {
        View gone = detach();
        if (gone != null) gone.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> remove(gone));
    }

    /** Goes at once, when the service unbinds. */
    void dismiss() {
        View gone = detach();
        if (gone != null) remove(gone);
    }

    private View detach() {
        handler.removeCallbacks(hideRunnable);
        View gone = root;
        root = null;
        if (gone != null) gone.animate().cancel();
        return gone;
    }

    private void remove(View view) {
        try {
            wm.removeViewImmediate(view);
        } catch (IllegalArgumentException e) {
            // already gone with the service's window token
        }
    }

    /** A key while the bar is showing. True if it was for the bar; false lets it through to the app. */
    boolean onKey(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int dir = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1;
                    int next = Math.max(0, Math.min(100, level + dir * STEP));
                    if (next != level && Backlight.set(next)) {
                        level = next;
                        update(true);
                    }
                }
                scheduleHide();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                // Keep the bar up for the matching up, so the app doesn't get half a key press.
                if (down) scheduleHide();
                else hide();
                return true;
            default:
                hide();
                return false;
        }
    }

    private void update(boolean animate) {
        bar.setLevel(level / 100f, animate);
        value.setText(String.valueOf(level));
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, HIDE_MS);
    }
}
