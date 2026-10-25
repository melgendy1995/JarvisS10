package com.jarvis.app.system

import android.service.notification.NotificationListenerService

/**
 * Notification listener so MediaSessionManager.getActiveSessions(ComponentName(this)) is
 * callable inside Jarvis itself. Listening notifications is never used.
 */
class JarvisMediaListener : NotificationListenerService()
