/*
 * Copyright (C) 2015 Andrew Comminos
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

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import koeln.becker.nomla.R
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_PROMPT_DISMISSED
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_RECONNECT

/**
 * A prompt to reconnect, under why the session ended. Shows on the application context; its
 * button and its dismissal go to [NotificationActionReceiver].
 */
class NomlaReconnectNotification(private val context: Context) {

    /** Posts the prompt, or replaces the one that is up. */
    fun show(error: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // No setDefaults(VIBRATE | LIGHTS): since API 26 the channel decides both.
            .setContentTitle(context.getString(R.string.nomlaDisconnected))
            .setContentText(error)
            .setDeleteIntent(NotificationActionReceiver.pendingIntent(context, ACTION_PROMPT_DISMISSED))
            .addAction(
                R.drawable.ic_action_move,
                context.getString(R.string.reconnect),
                NotificationActionReceiver.pendingIntent(context, ACTION_RECONNECT),
            )
        context.postNotification(NOTIFICATION_ID, builder.build())
    }

    fun hide() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    companion object {
        /** Not 2: that is NomlaMessageNotification's id, whose dismissal would cancel this too. */
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = NotificationChannels.RECONNECT
    }
}
