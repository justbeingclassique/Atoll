package com.nigel.island

import android.app.Notification
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        IslandService.instance?.refreshMedia()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val extras = n.extras

        if (n.category == Notification.CATEGORY_CALL) {
            extras.getCharSequence(Notification.EXTRA_TITLE)?.let { IslandService.callerName = it.toString() }
            return
        }
        if (n.category == Notification.CATEGORY_TRANSPORT ||
            n.category == Notification.CATEGORY_PROGRESS ||
            n.category == Notification.CATEGORY_SERVICE ||
            n.category == Notification.CATEGORY_SYSTEM) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        IslandService.instance?.postNotification(sbn.packageName, title, text, n.contentIntent)
    }
}
