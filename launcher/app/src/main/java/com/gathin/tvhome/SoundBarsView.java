package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Three small bars bobbing like a level meter: the "sound is coming out" mark beside the now-playing
 * kicker. Draws from a single ValueAnimator while attached and visible, nothing otherwise; setAnimated(false)
 * leaves the bars still at mid height (reduced motion).
 */
public class SoundBarsView extends View {

    private static final int BARS = 3;
    private static final float[] PHASE = {0f, 2.1f, 4.2f};
    private static final float[] SPEED = {1.0f, 1.35f, 0.8f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator clock = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
    private boolean animated = true;
    private float t;

    public SoundBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xCCFFFFFF);
        clock.setDuration(1400);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(null);   // linear: the sines below shape the motion
        clock.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    /** The bars' colour, e.g. the playing app's brand colour. */
    void setColor(int color) {
        if (paint.getColor() == color) return;
        paint.setColor(color);
        invalidate();
    }

    void setAnimated(boolean on) {
        animated = on;
        sync();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sync();
    }

    @Override
    protected void onDetachedFromWindow() {
        clock.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        sync();
    }

    private void sync() {
        boolean run = animated && isAttachedToWindow() && isShown();
        if (run && !clock.isRunning()) clock.start();
        else if (!run && clock.isRunning()) clock.cancel();
        if (!run) invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float gap = w / (BARS * 2f - 1);   // bar, gap, bar, gap, bar: equal widths
        float r = gap / 2f;
        for (int i = 0; i < BARS; i++) {
            // Mid height when still; otherwise between a third and the full height, each bar on its own beat.
            float level = animated ? 0.66f + 0.34f * (float) Math.sin(t * SPEED[i] + PHASE[i]) : 0.6f;
            float x = i * gap * 2;
            c.drawRoundRect(x, h - h * level, x + gap, h, r, r, paint);
        }
    }
}
