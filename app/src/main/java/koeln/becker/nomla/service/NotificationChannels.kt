/*
 * Copyright (C) 2026 The Nomla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package koeln.becker.nomla.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import koeln.becker.nomla.MainScreen
import koeln.becker.nomla.R

/**
 * Nomla's notification channels, in one group. The application creates them on start and again
 * when the configuration changes: creating them again is cheap and refreshes their names and
 * descriptions in the new language; importance, once created, is the user's.
 */
object NotificationChannels {
    const val GROUP = "server"
    const val CONNECTION = "connection_status"
    const val MESSAGES = "message_channel"
    const val RECONNECT = "reconnecting_channel"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP, context.getString(R.string.notification_group_server)),
        )
        fun channel(id: String, name: Int, description: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
                group = GROUP
            }
        manager.createNotificationChannels(
            listOf(
                channel(CONNECTION, R.string.connected, R.string.notification_channel_connection_description,
                    NotificationManager.IMPORTANCE_LOW),
                channel(MESSAGES, R.string.messageReceived, R.string.notification_channel_messages_description,
                    NotificationManager.IMPORTANCE_DEFAULT),
                channel(RECONNECT, R.string.connection_lost_reconnecting,
                    R.string.notification_channel_reconnect_description, NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }
}

/**
 * Posts [notification] under [id] if the user lets the app post notifications. Below API 33,
 * where the permission does not exist, that is whether notifications are on.
 */
fun Context.postNotification(id: Int, notification: Notification) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED
    ) {
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}

/**
 * What a tap on a notification opens: the main screen's channel view. FLAG_CANCEL_CURRENT, so the
 * screen extra is always the one sent: extras are not part of a PendingIntent's identity, and all
 * notifications share this request code.
 */
fun Context.openChannelsIntent(): PendingIntent =
    PendingIntent.getActivity(
        this, 0, MainScreen.intent(this, MainScreen.CHANNELS), FLAG_CANCEL_CURRENT or FLAG_IMMUTABLE,
    )
