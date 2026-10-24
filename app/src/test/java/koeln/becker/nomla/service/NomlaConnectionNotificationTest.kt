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

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import koeln.becker.nomla.MainScreen
import koeln.becker.nomla.R
import koeln.becker.nomla.app.DrawerAdapter
import koeln.becker.nomla.app.NomlaActivity
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_CANCEL_RECONNECT
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_DEAFEN
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_DISCONNECT
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_MUTE
import koeln.becker.nomla.testing.assertNotificationAction
import koeln.becker.nomla.testing.refuseForegroundStarts

/**
 * The foreground notification: what it shows, what its buttons reach, and when it holds the
 * service in the foreground. Every assertion reads back from an object the notification does not
 * own (notification manager, foreground state, the buttons' own PendingIntents). What the buttons
 * then do is NotificationActionReceiverTest's.
 */
@RunWith(RobolectricTestRunner::class)
class NomlaConnectionNotificationTest {
    /** A service with no behavior of its own; only its foreground state is under test. */
    class HostService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    private val controller: ServiceController<HostService> = Robolectric.buildService(HostService::class.java).create()
    private val service = controller.get()
    private val notificationManager: NotificationManager
        get() = service.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun NomlaConnectionNotification.configure(text: String, actions: Boolean) {
        customContentText = text
        actionsShown = actions
    }

    private fun posted(): Notification = shadowOf(notificationManager).getNotification(NOTIFICATION_ID)

    private fun create(text: String) = NomlaConnectionNotification.create(service, text)

    @Test
    fun showPutsTheServiceInTheForegroundWithTheContentText() {
        create("Connecting").show()

        val shadow = shadowOf(service)
        assertThat(shadow.isForegroundStopped).isFalse()
        assertThat(shadow.lastForegroundNotificationId).isEqualTo(NOTIFICATION_ID)
        assertThat(shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TEXT))
            .isEqualTo("Connecting")
    }

    @Test
    fun theTitleIsTheServerOnceKnown() {
        val notification = create("Connecting")
        notification.show()
        assertThat(posted().extras.getCharSequence(Notification.EXTRA_TITLE)).isNull()

        notification.title = "Home"
        notification.show()

        assertThat(posted().extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Home")
    }

    @Test
    fun theNotificationIsAnOngoingCallWithTheAppIconAndNoTimestamp() {
        create("Connecting").show()

        val n = shadowOf(service).lastForegroundNotification
        assertThat(n.smallIcon.resId).isEqualTo(R.drawable.ic_stat_notify)
        assertThat(n.category).isEqualTo(Notification.CATEGORY_CALL)
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)).isFalse()
        @Suppress("DEPRECATION")
        assertThat(n.priority).isEqualTo(NotificationCompat.PRIORITY_LOW)
        assertThat(n.channelId).isEqualTo(CHANNEL_ID)
    }

    @Test
    fun aTextChangeDoesNotAlertAgain() {
        val notification = create("Connecting")
        notification.show()
        notification.customContentText = "Connected"
        notification.show()

        assertThat(posted().flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(posted().extras.getString(Notification.EXTRA_TEXT)).isEqualTo("Connected")
    }

    @Test
    fun theChannelIsCreatedWithTheConnectedLabelAtLowImportance() {
        create("Connecting").show()

        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        assertThat(channel).isNotNull()
        assertThat(channel.name.toString()).isEqualTo(service.getString(R.string.connected))
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun tappingTheNotificationOpensTheServerDrawerOfTheMainActivity() {
        create("Connecting").show()

        val content = shadowOf(shadowOf(service).lastForegroundNotification.contentIntent)
        assertThat(content.isActivity).isTrue()
        assertThat(content.savedIntent.component?.className).isEqualTo(NomlaActivity::class.java.name)
        assertThat(content.savedIntent.getIntExtra(MainScreen.EXTRA_SCREEN, -1))
            .isEqualTo(DrawerAdapter.ITEM_SERVER)
        assertThat(content.isImmutable).isTrue()
    }

    /**
     * Extras are not part of a PendingIntent's identity, and NomlaMessageNotification asks for the
     * same activity under the same request code, so the flag itself is asserted.
     */
    @Test
    fun theContentIntentReplacesAnyEarlierOneSoItsExtraIsTheOneSent() {
        create("Connecting").show()

        val content = shadowOf(shadowOf(service).lastForegroundNotification.contentIntent)
        assertThat(content.flags and android.app.PendingIntent.FLAG_CANCEL_CURRENT).isNotEqualTo(0)
    }

    @Test
    fun noActionsAreShownUntilAskedFor() {
        create("Connecting").show()

        assertThat(shadowOf(service).lastForegroundNotification.actions).isNull()
    }

    @Test
    fun theThreeActionsAreMuteDeafenAndDisconnectInThatOrder() {
        val notification = create("Connecting")
        notification.configure("Connected", actions = true)
        notification.show()

        val actions = shadowOf(service).lastForegroundNotification.actions
        assertThat(actions.map { it.title.toString() }).containsExactly(
            service.getString(R.string.mute),
            service.getString(R.string.deafen),
            service.getString(R.string.disconnect),
        ).inOrder()
        assertThat(actions.map { it.icon }).containsExactly(
            R.drawable.ic_action_microphone,
            R.drawable.ic_action_audio,
            R.drawable.ic_close,
        ).inOrder()
    }

    @Test
    fun whileMutedAndDeafenedTheActionsOfferToUndoBoth() {
        val notification = create("Connected")
        notification.configure("Connected", actions = true)
        notification.muted = true
        notification.deafened = true
        notification.show()

        val actions = posted().actions
        assertThat(actions.take(2).map { it.title.toString() }).containsExactly(
            service.getString(R.string.unmute),
            service.getString(R.string.undeafen),
        ).inOrder()
        assertThat(actions.take(2).map { it.icon }).containsExactly(
            R.drawable.ic_action_microphone_muted,
            R.drawable.ic_action_audio_muted,
        ).inOrder()
    }

    @Test
    fun theActionLabelsFollowAStateChange() {
        val notification = create("Connected")
        notification.configure("Connected", actions = true)
        notification.show()
        notification.muted = true
        notification.show()

        assertThat(posted().actions.take(2).map { it.title.toString() }).containsExactly(
            service.getString(R.string.unmute),
            service.getString(R.string.deafen),
        ).inOrder()
    }

    /** Each button's own PendingIntent, so a button wired to the wrong action is visible. */
    @Test
    fun eachActionIsAnExplicitBroadcastOfItsOwnAction() {
        val notification = create("Connected")
        notification.configure("Connected", actions = true)
        notification.show()

        val actions = shadowOf(service).lastForegroundNotification.actions
        assertThat(actions).hasLength(3)
        for ((action, expected) in actions.zip(listOf(ACTION_MUTE, ACTION_DEAFEN, ACTION_DISCONNECT))) {
            assertNotificationAction(action.actionIntent, expected)
        }
    }

    // ---- ConnectionLost / Reconnecting: the only thing to offer is giving up ----------------

    @Test
    fun theCancelReconnectActionIsTheOnlyOneWhileReconnecting() {
        val notification = create("Connecting")
        notification.show()
        notification.customContentText = "Connection lost"
        notification.cancelReconnectShown = true
        notification.show()

        val action = posted().actions.single()
        assertThat(action.title.toString()).isEqualTo(service.getString(R.string.cancel_reconnect))
        assertThat(action.icon).isEqualTo(R.drawable.ic_action_delete_dark)
        assertNotificationAction(action.actionIntent, ACTION_CANCEL_RECONNECT)
    }

    @Test
    fun noCancelReconnectActionUntilAskedFor() {
        val notification = create("Connected")
        notification.configure("Connected", actions = true)
        notification.show()

        assertThat(posted().actions.map { it.title.toString() })
            .doesNotContain(service.getString(R.string.cancel_reconnect))
    }

    @Test
    fun hideLeavesTheForegroundAndRemovesTheNotification() {
        val notification = create("Connecting")
        notification.configure("Connected", actions = true)
        notification.show()

        notification.hide()

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(shadowOf(service).notificationShouldRemoved).isTrue()
        assertThat(notification.isForeground).isFalse()
    }

    // ---- the foreground start is made once, and a refusal is survivable ---------------------

    @Test
    fun showReportsThatTheServiceIsInTheForeground() {
        val notification = create("Connecting")

        assertThat(notification.show()).isTrue()
        assertThat(notification.isForeground).isTrue()
    }

    /** A refusal, or a missing FOREGROUND_SERVICE_MICROPHONE, leaves no notification to press. */
    @Test
    fun aRefusedForegroundStartIsReportedInsteadOfCrashing() {
        val refusals = listOf(
            ForegroundServiceStartNotAllowedException("not allowed from the background"),
            SecurityException("missing FOREGROUND_SERVICE_MICROPHONE"),
        )
        for (refusal in refusals) {
            shadowOf(service).setThrowInStartForeground(refusal)
            val notification = create("Connecting")

            assertWithMessage("$refusal").that(notification.show()).isFalse()
            assertWithMessage("$refusal").that(notification.isForeground).isFalse()
        }
    }

    /**
     * ForegroundServiceStartNotAllowedException is an IllegalStateException; only the refusal is
     * caught, since a manifest without a foregroundServiceType is a build defect.
     */
    @Test
    fun anyOtherFailureOfTheForegroundStartStillPropagates() {
        shadowOf(service).setThrowInStartForeground(IllegalStateException("a defect, not a refusal"))
        val notification = create("Connecting")

        assertThrows(IllegalStateException::class.java) { notification.show() }
    }

    /**
     * The platform re-checks the background-start restriction on every startForeground call, so a
     * text update while the screen is off is refused like a fresh start. From the second show on
     * every start is refused here, and the update must still arrive.
     */
    @Test
    fun aSecondShowUpdatesTheNotificationWithoutAnotherForegroundStart() {
        val notification = create("Connecting")
        notification.show()
        service.refuseForegroundStarts()

        notification.configure("Connected", actions = true)

        assertThat(notification.show()).isTrue()
        assertThat(notification.isForeground).isTrue()
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(posted().extras.getString(Notification.EXTRA_TEXT)).isEqualTo("Connected")
        assertThat(posted().actions).hasLength(3)
    }

    @Test
    fun afterHideTheNextShowAsksThePlatformAgain() {
        val notification = create("Connecting")
        notification.show()
        notification.hide()
        assertThat(notification.isForeground).isFalse()

        shadowOf(service).setThrowInStartForeground(SecurityException("refused"))
        assertThat(notification.show()).isFalse()
        shadowOf(service).setThrowInStartForeground(null)
        assertThat(notification.show()).isTrue()

        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(notification.isForeground).isTrue()
    }

    @Test
    fun onAndroid14AndLaterTheForegroundIsTypedAsMicrophone() {
        create("Connecting").show()

        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /** Below 34 the type is optional; the manifest's one, microphone, is passed all the same. */
    @Test
    @Config(sdk = [33])
    fun belowAndroid14TheForegroundIsTypedAsMicrophoneToo() {
        create("Connecting").show()

        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "connection_status"
    }
}
