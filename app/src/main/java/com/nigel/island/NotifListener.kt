package com.nigel.island

import android.app.Notification
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        IslandService.instance?.refreshMedia()
        pushUnread()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        pushUnread()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        pushUnread()

        val n = sbn.notification
        val extras = n.extras

        if (n.category == Notification.CATEGORY_CALL) {
            extras.getCharSequence(Notification.EXTRA_TITLE)?.let { IslandService.callerName = it.toString() }
            return
        }
        if (isNoise(n)) return

        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        IslandService.instance?.postNotification(sbn.packageName, title, text, n.contentIntent)
    }

    private fun isNoise(n: Notification): Boolean {
        val f = n.flags
        if (f and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (f and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (f and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        return when (n.category) {
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM -> true
            else -> false
        }
    }

    private fun pushUnread() {
        val list = ArrayList<String>()
        try {
            for (s in activeNotifications) {
                if (s.packageName == packageName) continue
                if (isNoise(s.notification)) continue
                if (!list.contains(s.packageName)) list.add(s.packageName)
            }
        } catch (_: Exception) {}
        IslandService.instance?.setUnread(list)
    }
}
