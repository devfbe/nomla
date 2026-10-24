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

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.app.AppContainer
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_CANCEL_RECONNECT
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_DISCONNECT
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_PROMPT_DISMISSED
import koeln.becker.nomla.service.NotificationActionReceiver.Companion.ACTION_RECONNECT
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.pressNotificationAction
import koeln.becker.nomla.testing.stubState

/**
 * Each notification button reaches the session through the receiver, whether or not the service
 * runs. The mute and deafen toggles are NomlaServiceTest's, next to the self state they flip.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationActionReceiverTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sessions get() = SessionManager.get(app)
    private val session = mockk<Session>(relaxed = true)

    private fun press(action: String) = pressNotificationAction(NotificationActionReceiver.pendingIntent(app, action))

    @Test
    fun disconnectEndsTheSession() {
        installSession(session)

        press(ACTION_DISCONNECT)

        verify { session.disconnect() }
    }

    @Test
    fun cancelReconnectGivesUpTheReconnectAndCountsTheReasonAsShown() {
        session.stubState(SessionState.Reconnecting(null))
        installSession(session)

        press(ACTION_CANCEL_RECONNECT)

        verify { session.cancelReconnect() }
        assertThat(sessions.errorShown.value).isTrue()
    }

    @Test
    fun reconnectConnectsAgainWithTheLastConfiguration() {
        session.stubState(SessionState.Disconnected())
        installSession(session)
        val next = mockk<Session>(relaxed = true)
        AppContainer.of(app).replaceParts { it.copy(newSession = { _, _ -> next }) }

        press(ACTION_RECONNECT)

        assertThat(sessions.session.value).isSameInstanceAs(next)
        verify { next.connect() }
    }

    @Test
    fun dismissingThePromptCountsTheReasonAsShown() {
        session.stubState(SessionState.Disconnected())
        installSession(session)

        press(ACTION_PROMPT_DISMISSED)

        assertThat(sessions.errorShown.value).isTrue()
    }

    /** In a process the service has not started in, a press finds no session and does nothing. */
    @Test
    fun withoutASessionEveryActionIsANoOp() {
        for (action in ALL_ACTIONS) press(action)

        assertThat(sessions.session.value).isNull()
    }

    /** Declared, so a press reaches it in a process without the service, and closed to other apps. */
    @Test
    fun theReceiverIsDeclaredAndNotExported() {
        val info = app.packageManager.getReceiverInfo(
            ComponentName(app, NotificationActionReceiver::class.java), PackageManager.GET_META_DATA,
        )

        assertThat(info.exported).isFalse()
    }

    private companion object {
        val ALL_ACTIONS = listOf(
            NotificationActionReceiver.ACTION_MUTE,
            NotificationActionReceiver.ACTION_DEAFEN,
            ACTION_DISCONNECT,
            ACTION_CANCEL_RECONNECT,
            ACTION_RECONNECT,
            ACTION_PROMPT_DISMISSED,
        )
    }
}
