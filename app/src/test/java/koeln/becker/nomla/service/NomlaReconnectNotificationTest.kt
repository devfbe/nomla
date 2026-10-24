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
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import koeln.becker.nomla.R
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_PROMPT_DISMISSED
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_RECONNECT
import koeln.becker.nomla.testing.assertNotificationAction

/**
 * The prompt shown when a session ended with an error: what it says and where its two intents
 * go; what they then do is NotificationActionReceiverTest's. Posting across the
 * POST_NOTIFICATIONS boundary is in NotificationPostingTest.
 */
@RunWith(RobolectricTestRunner::class)
class NomlaReconnectNotificationTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun show(error: String) = NomlaReconnectNotification(context).also { it.show(error) }

    /** A second prompt replaces the first. */
    @Test
    fun showingAgainReplacesThePrompt() {
        val notification = show("first")

        notification.show("second")

        assertThat(posted()!!.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("second")
        assertThat(shadowOf(notificationManager).allNotifications).hasSize(1)
    }

    private fun posted(): Notification? = shadowOf(notificationManager).getNotification(NOTIFICATION_ID)


    @Test
    fun theErrorIsTheTextUnderTheDisconnectedTitle() {
        show("socket reset")

        val n = posted()!!
        assertThat(n.extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo(context.getString(R.string.nomlaDisconnected))
        assertThat(n.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset")
        assertThat(n.smallIcon.resId).isEqualTo(R.drawable.ic_stat_notify)
        @Suppress("DEPRECATION")
        assertThat(n.priority).isEqualTo(NotificationCompat.PRIORITY_MAX)
        assertThat(n.channelId).isEqualTo(CHANNEL_ID)
    }

    @Test
    fun theChannelIsNamedFromAResourceAtDefaultImportance() {
        show("socket reset")

        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        assertThat(channel).isNotNull()
        // Shown in the system's notification settings, so translatable like every other label.
        assertThat(channel.name.toString()).isEqualTo(context.getString(R.string.connection_lost_reconnecting))
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun itOffersReconnectAndCanBeSwipedAway() {
        show("socket reset")

        val n = posted()!!
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
        assertThat(n.actions.map { it.title.toString() }).containsExactly(context.getString(R.string.reconnect))
        @Suppress("DEPRECATION")
        assertThat(n.actions.single().icon).isEqualTo(R.drawable.ic_action_move)
        assertNotificationAction(n.actions.single().actionIntent, ACTION_RECONNECT)
    }

    @Test
    fun swipingItAwayIsReported() {
        show("socket reset")

        assertNotificationAction(posted()!!.deleteIntent, ACTION_PROMPT_DISMISSED)
    }

    @Test
    fun hideCancelsTheNotification() {
        val notification = show("socket reset")

        notification.hide()

        assertThat(posted()).isNull()
    }

    @Test
    fun hidingTwiceIsHarmless() {
        val notification = show("socket reset")
        notification.hide()

        notification.hide()

        assertThat(posted()).isNull()
    }

    private companion object {
        const val NOTIFICATION_ID = 3
        const val CHANNEL_ID = "reconnecting_channel"
    }
}
