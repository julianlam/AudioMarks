package com.julianlam.audiomarks

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty.
 *
 * On Android 16, third-party apps may only observe other apps' media sessions
 * (getMediaKeyEventSession / session enumeration) if they are an *enabled*
 * notification listener. Declaring and having the user enable this service is
 * what unlocks that access. No notifications are read or processed.
 */
class MarksNotificationListener : NotificationListenerService()
