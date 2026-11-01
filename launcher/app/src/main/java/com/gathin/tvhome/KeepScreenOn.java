package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

/**
 * Keeps a window's screen on while a track plays, and for a grace period after it stops, so a Home or
 * clock left playing music does not go to sleep at Android's timeouts (music is not activity to it, only the
 * remote is). A screen wake lock would not do: during a screensaver it only keeps the CPU, never the screen.
 */
final class KeepScreenOn {

    private static final long GRACE_MS = 10 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Window window;
    private final Runnable letGo = this::release;

    KeepScreenOn(Window window) {
        this.window = window;
    }

    void setPlaying(boolean playing) {
        handler.removeCallbacks(letGo);
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else handler.postDelayed(letGo, GRACE_MS);
    }

    void release() {
        handler.removeCallbacks(letGo);
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
