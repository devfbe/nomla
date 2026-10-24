/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import koeln.becker.nomla.mumble.util.NomlaLog
import koeln.becker.nomla.R
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_CANCEL_RECONNECT
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_DEAFEN
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_DISCONNECT
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_MUTE

/**
 * The ongoing notification that keeps the service in the foreground.
 *
 * [show] enters the foreground once and afterwards only re-posts the notification: the platform
 * re-checks the background-start restriction on every `startForeground` call, even a text-only
 * update, so e.g. "Connection lost – reconnecting…" with the screen off must not go through it.
 *
 * [show] returns false for a refusal ([ForegroundServiceStartNotAllowedException],
 * [SecurityException]) instead of throwing; other exceptions (e.g. a missing foreground service
 * type) still propagate. Its buttons go to [NotificationActionReceiver].
 */
class NomlaConnectionNotification private constructor(
    private val service: Service,
    contentText: String,
) {
    /** The server's name; without one the platform shows the app's. */
    var title: String? = null
    var customContentText: String = contentText

    /** Mute, deafen and disconnect: only meaningful while a session is up. */
    var actionsShown: Boolean = false

    /** Our own state, which decides whether the actions offer to mute and deafen or to undo it. */
    var muted: Boolean = false
    var deafened: Boolean = false

    /** "Cancel reconnect", for ConnectionLost and Reconnecting. */
    var cancelReconnectShown: Boolean = false

    /** True from a successful [show] until [hide]. */
    var isForeground: Boolean = false
        private set

    /**
     * Enters the foreground, or once there replaces the posted notification.
     * @return false if the platform refused the foreground start.
     */
    fun show(): Boolean {
        val notification = buildNotification()
        if (isForeground) {
            service.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            return true
        }
        isForeground = enterForeground(notification)
        return isForeground
    }

    /** @return false if the platform refused the foreground start. */
    private fun enterForeground(notification: Notification): Boolean = try {
        // The type is the manifest's one: required from Android 14 on, the same as no type before.
        ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (e: ForegroundServiceStartNotAllowedException) {
        NomlaLog.w(TAG, "Foreground service start not allowed", e)
        false
    } catch (e: SecurityException) {
        NomlaLog.w(TAG, "Not permitted to start a microphone foreground service", e)
        false
    }

    /** Leaves the foreground and removes the notification. */
    fun hide() {
        isForeground = false
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(customContentText)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (actionsShown) {
            builder.addAction(
                if (muted) R.drawable.ic_action_microphone_muted else R.drawable.ic_action_microphone,
                service.getString(if (muted) R.string.unmute else R.string.mute),
                action(ACTION_MUTE),
            )
            builder.addAction(
                if (deafened) R.drawable.ic_action_audio_muted else R.drawable.ic_action_audio,
                service.getString(if (deafened) R.string.undeafen else R.string.deafen),
                action(ACTION_DEAFEN),
            )
            builder.addAction(
                R.drawable.ic_close,
                service.getString(R.string.disconnect),
                action(ACTION_DISCONNECT),
            )
        }
        if (cancelReconnectShown) {
            builder.addAction(
                R.drawable.ic_action_delete_dark,
                service.getString(R.string.cancel_reconnect),
                action(ACTION_CANCEL_RECONNECT),
            )
        }
        builder.setContentIntent(service.openChannelsIntent())
        return builder.build()
    }

    private fun action(action: String) = NotificationActionReceiver.pendingIntent(service, action)

    companion object {
        private val TAG = NomlaConnectionNotification::class.java.name
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = NotificationChannels.CONNECTION

        fun create(service: Service, contentText: String): NomlaConnectionNotification =
            NomlaConnectionNotification(service, contentText)
    }
}
