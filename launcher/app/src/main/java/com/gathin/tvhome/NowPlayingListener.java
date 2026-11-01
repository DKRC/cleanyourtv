package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the launcher may read the active media sessions: MediaSessionManager.getActiveSessions
 * asks for an enabled notification listener of the caller. build-and-install.sh enables it with
 * `cmd notification allow_listener`; no notification is ever read.
 */
public class NowPlayingListener extends NotificationListenerService {
}
