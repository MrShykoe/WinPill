package com.island.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// Система сама запускает эту службу после включения доступа к уведомлениям и после перезагрузки.
// Она же даёт доступ к плееру (MediaSession) и к уведомлениям.
class IslandListener : NotificationListenerService() {
    private var ctl: IslandController? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        ctl?.stop()
        val c = IslandController(this)
        ctl = c
        IslandController.current = c
        c.start()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        ctl?.stop()
        ctl = null
        IslandController.current = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        ctl?.onNotification(s)
    }

    override fun onDestroy() {
        ctl?.stop()
        ctl = null
        IslandController.current = null
        super.onDestroy()
    }
}

// Плитка в шторке: скрыть / показать остров.
class IslandTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        IslandController.current?.toggleHidden()
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val c = IslandController.current
        t.state = if (c != null && !c.hidden) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
