package com.crpakala.commutewidget.health

import android.service.notification.NotificationListenerService

/**
 * Access token for [android.media.session.MediaSessionManager.getActiveSessions].
 *
 * A bound `NotificationListenerService` is required by the platform before
 * `getActiveSessions` will return anything instead of throwing [SecurityException]. This class
 * intentionally has no overrides: it exists purely so the system grants notification-listener
 * access to this package, not to read or act on notifications.
 */
class HealthNotificationListener : NotificationListenerService()
