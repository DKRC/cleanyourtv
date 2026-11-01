package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Back out of Spotify like any other app. On its home screen Spotify's TV app answers Back with "Close
 * Spotify?" (Quer fechar o Spotify?), and closing it stops the music. After each Back pressed in Spotify this
 * service looks for that prompt; when it is there, it closes it with Back and goes
 * Home, so the music keeps playing. If Spotify changes the prompt, nothing matches and Back works as
 * Spotify made it. Spotify's screen is a WebView that sends no accessibility event when the prompt opens,
 * hence the key, not events, as the trigger. Should a prompt still be up when Spotify comes back to the front,
 * it is closed then.
 *
 * Enable with SPOTIFY_EXIT_SERVICE=1 on build-and-install.sh.
 */
public class SpotifyExitService extends AccessibilityService {

    static final String SPOTIFY = "com.spotify.tv.android";
    /** When to look after Back is released: the prompt fades in, so look early and once more. */
    private static final long[] LOOK_AFTER_MS = {150, 400, 800};
    /** Waiting for the prompt to close after Back: checked every POLL_MS, Home anyway after CLOSE_WAIT_MS. */
    private static final long POLL_MS = 150, CLOSE_WAIT_MS = 1500;
    /** Spotify reopened: its page needs a moment before the prompt is in the tree. */
    private static final long REOPEN_LOOK_MS = 400;
    /** How long after Home the remote's Back stays held back, while Home comes to the front. */
    private static final long LEAVE_GRACE_MS = 600;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable look = this::closePrompt;
    private long lastBack;
    /** Until when the remote's Back is held back while this service takes Spotify Home. */
    private long leavingUntil;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        // On the way out: more Backs from the remote would open the prompt again behind us, so they stop here.
        if (SystemClock.uptimeMillis() < leavingUntil) return true;
        if (event.getAction() == KeyEvent.ACTION_UP && inSpotify()) {
            handler.removeCallbacks(look);
            for (long ms : LOOK_AFTER_MS) handler.postDelayed(look, ms);
        }
        return false;   // every other key, and Back otherwise, still reaches the app
    }

    private boolean inSpotify() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && SPOTIFY.contentEquals(String.valueOf(root.getPackageName()));
    }

    private void closePrompt() {
        if (promptOnScreen() == null) return;
        handler.removeCallbacks(look);
        leavingUntil = SystemClock.uptimeMillis() + CLOSE_WAIT_MS + LEAVE_GRACE_MS;
        // Close the prompt before going Home, or Spotify reopens on it. Its buttons ignore an accessibility
        // click, but Back closes it; Home only once it is gone (or after a while, at worst).
        back();
        homeWhenClosed(CLOSE_WAIT_MS);
    }

    private void homeWhenClosed(long waitLeft) {
        if (promptOnScreen() == null || waitLeft <= 0) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            leavingUntil = SystemClock.uptimeMillis() + LEAVE_GRACE_MS;   // Home takes a moment to come up
            return;
        }
        handler.postDelayed(() -> homeWhenClosed(waitLeft - POLL_MS), POLL_MS);
    }

    /**
     * Spotify coming back to the front: a prompt left open from an earlier Back is closed, staying in Spotify.
     * Spotify sends this event twice per return, and a second Back would open the prompt again: one check per
     * return, and no Back within CLOSE_WAIT_MS of the last one.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        handler.removeCallbacks(reopenLook);
        handler.postDelayed(reopenLook, REOPEN_LOOK_MS);
    }

    private final Runnable reopenLook = () -> {
        if (SystemClock.uptimeMillis() - lastBack < CLOSE_WAIT_MS || promptOnScreen() == null) return;
        back();
    };

    private void back() {
        lastBack = SystemClock.uptimeMillis();
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** The prompt's first button when the prompt is up in Spotify's window, else null. */
    private AccessibilityNodeInfo promptOnScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !SPOTIFY.contentEquals(String.valueOf(root.getPackageName()))) return null;
        return notNowButton(root);
    }

    /**
     * The prompt is a question that names Spotify ("Quer fechar o Spotify?", "Close Spotify?") beside two
     * buttons, Not now and Close; returns the first of them, or null when no such prompt is on screen. A walk
     * of the whole tree: findAccessibilityNodeInfosByText does not search the WebView's nodes.
     */
    static AccessibilityNodeInfo notNowButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text != null && text.toString().contains("Spotify") && text.toString().trim().endsWith("?")) {
            AccessibilityNodeInfo dialog = node.getParent();
            if (dialog == null) return null;
            AccessibilityNodeInfo first = null;
            int buttons = 0;
            for (int i = 0; i < dialog.getChildCount(); i++) {
                AccessibilityNodeInfo c = dialog.getChild(i);
                if (c != null && "android.widget.Button".contentEquals(String.valueOf(c.getClassName()))) {
                    if (first == null) first = c;
                    buttons++;
                }
            }
            return buttons == 2 ? first : null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = notNowButton(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    @Override
    public void onInterrupt() {
    }
}
