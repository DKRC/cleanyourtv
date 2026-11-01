package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * What is playing right now (Spotify, YouTube Music, any app with a media session), for the Home panel and
 * the clock screensaver. Reads the system's media sessions, which needs NowPlayingListener enabled; without
 * it, or with nothing playing, the listener gets null and the caller hides its view.
 */
final class NowPlaying {

    interface Listener {
        /** The playing track, or null when nothing plays. Called on the main thread. */
        void onTrack(Track track);
    }

    static final class Track {
        final String title;
        final String artist;
        final Bitmap art;   // may be null
        /** The package of the app playing it, e.g. com.spotify.tv.android. */
        final String app;
        /** Play, pause, next and previous for the Now playing card's menu. */
        final MediaController.TransportControls controls;
        /** False while paused: Home keeps the card (to resume from it), the screensaver drops it. */
        final boolean playing;

        Track(String title, String artist, Bitmap art, String app, MediaController.TransportControls controls, boolean playing) {
            this.title = title;
            this.artist = artist;
            this.art = art;
            this.app = app;
            this.controls = controls;
            this.playing = playing;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaSessionManager sessions;
    private final ComponentName listenerComponent;
    private final Listener listener;
    private final List<MediaController> watched = new ArrayList<>();
    private boolean running;
    /** A paused track stays listed this long after it paused, then goes as if stopped. */
    static final long PAUSED_KEEP_MS = 30 * 60_000L;
    private final Runnable republish = this::publish;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            publish();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::watch;

    NowPlaying(Context context, Listener listener) {
        this.sessions = context.getSystemService(MediaSessionManager.class);
        this.listenerComponent = new ComponentName(context, NowPlayingListener.class);
        this.listener = listener;
    }

    void start() {
        if (running || sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler);
            running = true;
            watch(sessions.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            // The listener is not enabled: no panel, and nothing to retry until the next start.
            listener.onTrack(null);
        }
    }

    void stop() {
        if (!running) return;
        running = false;
        sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        unwatch();
    }

    private void watch(List<MediaController> controllers) {
        unwatch();
        if (controllers != null) {
            for (MediaController c : controllers) {
                c.registerCallback(controllerCallback, handler);
                watched.add(c);
            }
        }
        publish();
    }

    private void unwatch() {
        for (MediaController c : watched) c.unregisterCallback(controllerCallback);
        watched.clear();
    }

    private void publish() {
        handler.removeCallbacks(republish);
        Track t = running ? current(true) : null;
        if (t == null && running) t = current(false);
        listener.onTrack(t);
    }

    /**
     * The first session that is playing (or, with {@code playing} false, paused for less than
     * {@link #PAUSED_KEEP_MS}) and names its track; sessions come most recently active first.
     */
    private Track current(boolean playing) {
        for (MediaController c : watched) {
            PlaybackState state = c.getPlaybackState();
            MediaMetadata meta = c.getMetadata();
            if (state == null || meta == null) continue;
            if (playing && state.getState() != PlaybackState.STATE_PLAYING) continue;
            if (!playing) {
                if (state.getState() != PlaybackState.STATE_PAUSED) continue;
                long left = state.getLastPositionUpdateTime() + PAUSED_KEEP_MS - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) continue;
                handler.postDelayed(republish, left);   // gone once it has been paused too long
            }
            String title = first(meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) continue;
            String artist = first(meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            return new Track(title, artist == null ? "" : artist, art, c.getPackageName(), c.getTransportControls(), playing);
        }
        return null;
    }

    private static String first(MediaMetadata meta, String... keys) {
        for (String k : keys) {
            CharSequence v = meta.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString();
        }
        return null;
    }
}
