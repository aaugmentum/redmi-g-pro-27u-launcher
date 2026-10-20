package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The card voice input shows at the bottom of the screen, over any app or input: a pill in the volume bar's colours
 * with a round mic badge. Listening, the badge is white and a halo behind it follows the mic level; recognizing,
 * the badge dims and the halo breathes; both show the language's code at the end. Then the words heard. Its window
 * can't take focus or touches.
 */
final class VoiceOverlay {

    private static final String TAG = "TvHomeVoice";
    private static final long FADE_MS = 180;
    private static final int DARK = 0xFF323234, HINT = 0xB3FFFFFF, WHITE = 0xFFFFFFFF;
    /** The halo at rest is the 40dp badge's size in its 56dp frame; at its loudest it fills the frame. */
    private static final float HALO_REST = 40f / 56, HALO_GROWTH = 0.4f;

    private final Context context;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hide;
    private View root;
    private TextView text, code;
    private View halo, badge;
    private ImageView icon;
    private ValueAnimator breathing;
    /** The halo's level, falling off slowly so it doesn't flicker. */
    private float shown;
    private String language = "";

    VoiceOverlay(Context service) {
        context = new ContextThemeWrapper(service, android.R.style.Theme_Material);
        wm = service.getSystemService(WindowManager.class);
    }

    /** The code of the language listened for, as "EN". */
    void language(String code) {
        language = code;
        if (this.code != null) this.code.setText(code);
    }

    /** Listening, with the mic level from 0 to 1. */
    void listening(float value) {
        if (!ensureShown()) return;
        handler.removeCallbacks(hideRunnable);
        state(R.string.voice_listening, HINT, true, true);
        shown = Math.max(value, shown * 0.8f);
        float scale = HALO_REST * (1 + HALO_GROWTH * shown);
        halo.setScaleX(scale);
        halo.setScaleY(scale);
    }

    void working() {
        if (!ensureShown()) return;
        handler.removeCallbacks(hideRunnable);
        state(R.string.voice_working, HINT, false, true);
        if (breathing == null) {
            breathing = ObjectAnimator.ofPropertyValuesHolder(halo,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, HALO_REST, HALO_REST * 1.35f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, HALO_REST, HALO_REST * 1.35f));
            breathing.setDuration(650);
            breathing.setRepeatMode(ValueAnimator.REVERSE);
            breathing.setRepeatCount(ValueAnimator.INFINITE);
            breathing.start();
        }
    }

    /** Shows {@code message} for {@code ms}, then fades out. */
    void result(String message, long ms) {
        if (!ensureShown()) return;
        state(0, WHITE, true, false);
        text.setText(message);
        halo.setScaleX(HALO_REST);
        halo.setScaleY(HALO_REST);
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, ms);
    }

    /** The badge lit (white, dark mic) or dimmed (faint, white mic), the text and its colour, and the code or not. */
    private void state(int textRes, int textColor, boolean lit, boolean withCode) {
        if (breathing != null) {
            breathing.cancel();
            breathing = null;
        }
        if (textRes != 0) text.setText(textRes);
        text.setTextColor(textColor);
        badge.setAlpha(lit ? 1f : 0.16f);
        icon.setImageTintList(ColorStateList.valueOf(lit ? DARK : WHITE));
        code.setVisibility(withCode ? View.VISIBLE : View.GONE);
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

    private boolean ensureShown() {
        if (root != null) return true;
        View view = LayoutInflater.from(context).inflate(R.layout.voice_card, null);
        text = view.findViewById(R.id.voice_text);
        code = view.findViewById(R.id.voice_code);
        code.setText(language);
        halo = view.findViewById(R.id.voice_halo);
        badge = view.findViewById(R.id.voice_badge);
        icon = view.findViewById(R.id.voice_icon);
        Typeface font = BrightnessOverlay.font();   // MiSans, loaded when the service connected
        if (font != null) {
            text.setTypeface(font);
            code.setTypeface(font);
        }
        shown = 0;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(72 * context.getResources().getDisplayMetrics().density);
        view.setAlpha(0f);
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {   // e.g. the service is being unbound
            Log.w(TAG, "can't show the voice card", e);
            return false;
        }
        root = view;
        view.animate().alpha(1f).setDuration(FADE_MS);
        return true;
    }

    private View detach() {
        handler.removeCallbacks(hideRunnable);
        if (breathing != null) {
            breathing.cancel();
            breathing = null;
        }
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
}
