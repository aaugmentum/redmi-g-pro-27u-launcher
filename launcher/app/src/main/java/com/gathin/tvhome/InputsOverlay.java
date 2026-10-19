package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The input picker that holding the remote's TV button opens (see HomeKeyService), over any app or input, in the
 * place of the brightness bar: Media Hub (this launcher, the monitor's own apps), then the ports. Up/Down pick, OK
 * switches, Back or a few idle seconds close it, and any key but the arrows and OK closes it and goes on to the app
 * below. Its window can't take focus, so the selected row is drawn as selected and
 * HomeKeyService hands it the keys. Only on Xiaomi firmware (XiaomiInputs).
 */
final class InputsOverlay {

    private static final String TAG = "TvHomeInputs";
    private static final long HIDE_MS = 6000;
    private static final long FADE_MS = 180;
    /** Source id of the Media Hub row, which opens the launcher instead of a port. */
    private static final int HUB = Integer.MIN_VALUE;   // not -1, which XiaomiInputs.current() uses for "none"

    private final Context context;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hide;
    private final List<XiaomiInputs.Source> sources = new ArrayList<>();
    /** One row per source, in the same order (the separator is not in it). */
    private final List<View> sourceRows = new ArrayList<>();
    private final boolean usable;
    private final Runnable openHome;
    private View root;
    private LinearLayout rows;
    private int selected;

    InputsOverlay(Context service, Runnable openHome) {
        this.openHome = openHome;
        context = new ContextThemeWrapper(service, android.R.style.Theme_Material);
        wm = service.getSystemService(WindowManager.class);
        usable = XiaomiInputs.usable(service) && service.getSystemService(Context.TV_INPUT_SERVICE) != null;
    }

    /** True on firmware where the picker can switch inputs. */
    boolean available() {
        return usable;
    }

    boolean isShowing() {
        return root != null;
    }

    /** @param onInput whether a port is on screen (Xiaomi's player in front), rather than an app */
    void show(boolean onInput) {
        if (!usable || isShowing()) return;
        sources.clear();
        sourceRows.clear();
        // State -1: no status line.
        sources.add(new XiaomiInputs.Source(context.getString(R.string.input_hub), HUB, -1, false));
        sources.addAll(XiaomiInputs.list((TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE)));
        View view = LayoutInflater.from(context).inflate(R.layout.inputs_overlay, null);
        rows = view.findViewById(R.id.rows);
        // Start on Media Hub when an app is in front; else on the input on screen, else the first with a signal.
        int current = onInput ? XiaomiInputs.current(context) : HUB;
        selected = -1;
        for (int i = 0; i < sources.size(); i++) {
            XiaomiInputs.Source s = sources.get(i);
            if (i > 0 && (s.hdmi != sources.get(i - 1).hdmi || sources.get(i - 1).id == HUB)) addSeparator();
            View row = LayoutInflater.from(context).inflate(R.layout.item_input, rows, false);
            bindRow(row, s.id == HUB ? R.drawable.ic_home : R.drawable.ic_input, s.label, s.state);
            row.setFocusable(false);
            rows.addView(row);
            sourceRows.add(row);
            if (s.id == current) selected = i;
        }
        for (int i = 1; selected < 0 && i < sources.size(); i++) {
            if (sources.get(i).state == TvInputManager.INPUT_STATE_CONNECTED) selected = i;
        }
        if (selected < 0) selected = Math.min(1, sources.size() - 1);
        select(selected);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lp.x = Math.round(30 * context.getResources().getDisplayMetrics().density);   // where the brightness bar sits
        view.setAlpha(0f);
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {   // e.g. the service is being unbound; don't take the input filter down
            Log.w(TAG, "can't show the input picker", e);
            return;
        }
        root = view;
        view.animate().alpha(1f).setDuration(FADE_MS);
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

    /** A key while the picker is showing. True if it was for the picker; false lets it through to the app. */
    boolean onKey(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int dir = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    select(Math.max(0, Math.min(sources.size() - 1, selected + dir)));
                }
                scheduleHide();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                scheduleHide();   // nothing sideways; don't move things around under the picker
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // Switch on the up, so the app or input that comes up doesn't get half a key press.
                if (down) {
                    scheduleHide();
                } else {
                    int source = sources.get(selected).id;
                    hide();
                    if (source == HUB) openHome.run();
                    else switchTo(source);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) scheduleHide();
                else hide();
                return true;
            default:
                hide();
                return false;
        }
    }

    private void switchTo(int source) {
        try {
            context.startActivity(XiaomiInputs.intent(source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "can't switch to source " + source, e);
        }
    }

    private void select(int index) {
        selected = index;
        for (int i = 0; i < sourceRows.size(); i++) sourceRows.get(i).setSelected(i == index);
    }

    private void addSeparator() {
        float density = context.getResources().getDisplayMetrics().density;
        View line = new View(context);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    /** Fills an item_input row: icon, name, and the port's state where it is a definite one. */
    static void bindRow(View row, int iconRes, String title, int state) {
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // Some TVs report "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, HIDE_MS);
    }
}
