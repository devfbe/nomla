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

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import koeln.becker.nomla.session.SessionManager

/**
 * The buttons and the dismissal of [NomlaConnectionNotification] and [NomlaReconnectNotification].
 * Declared in the manifest, not exported and without an intent filter: only the [pendingIntent]s,
 * which name it by class, reach it. Each action goes to the [SessionManager], so a press works
 * whether or not the service still runs, and a stale one finds no session to act on.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessions = SessionManager.get(context)
        when (intent.action) {
            ACTION_MUTE -> sessions.connected?.let(::toggleSelfMute)
            ACTION_DEAFEN -> sessions.connected?.let(::toggleSelfDeafen)
            ACTION_DISCONNECT -> sessions.disconnect()
            ACTION_CANCEL_RECONNECT -> sessions.cancelReconnect()
            ACTION_RECONNECT -> sessions.reconnect()
            ACTION_PROMPT_DISMISSED -> sessions.markErrorShown()
        }
    }

    companion object {
        const val ACTION_MUTE = "koeln.becker.nomla.action.NOTIFICATION_MUTE"
        const val ACTION_DEAFEN = "koeln.becker.nomla.action.NOTIFICATION_DEAFEN"
        const val ACTION_DISCONNECT = "koeln.becker.nomla.action.NOTIFICATION_DISCONNECT"
        const val ACTION_CANCEL_RECONNECT = "koeln.becker.nomla.action.NOTIFICATION_CANCEL_RECONNECT"
        const val ACTION_RECONNECT = "koeln.becker.nomla.action.NOTIFICATION_RECONNECT"
        const val ACTION_PROMPT_DISMISSED = "koeln.becker.nomla.action.NOTIFICATION_PROMPT_DISMISSED"

        /**
         * An immutable broadcast of [action] to this receiver. The action tells the buttons'
         * PendingIntents apart; they carry no extras, so none goes stale.
         */
        fun pendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, 0, intent, FLAG_IMMUTABLE)
        }
    }
}
