package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Vertical level meter for {@link BrightnessOverlay}: a rounded track filled from the bottom. */
public class LevelBar extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private float level;   // 0-1, as drawn
    private ValueAnimator anim;

    public LevelBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setColor(0x33FFFFFF);
        fill.setColor(0xFFFFFFFF);
    }

    void setLevel(float target, boolean animate) {
        target = Math.max(0f, Math.min(1f, target));
        if (anim != null) anim.cancel();
        if (!animate) {
            level = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(level, target).setDuration(120);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            level = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        clip.reset();
        clip.addRoundRect(0, 0, w, h, w / 2f, w / 2f, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.save();
        canvas.clipPath(clip);   // so a low level still gets the track's rounded bottom
        canvas.drawRect(0, 0, w, h, track);
        canvas.drawRect(0, h * (1f - level), w, h, fill);
        canvas.restore();
    }
}
