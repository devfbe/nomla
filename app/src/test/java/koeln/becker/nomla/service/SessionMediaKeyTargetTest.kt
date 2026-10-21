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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.IHumlaSession
import koeln.becker.nomla.mumble.model.UserState
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.selfServerState
import koeln.becker.nomla.testing.stubAudio
import koeln.becker.nomla.testing.serverState
import koeln.becker.nomla.testing.stubConnected
import koeln.becker.nomla.testing.stubModel
import koeln.becker.nomla.testing.stubState

@RunWith(RobolectricTestRunner::class)
class SessionMediaKeyTargetTest {
    private val session = mockk<IHumlaSession>(relaxed = true).also { installSession(it.stubConnected()) }
    private val audio = session.stubAudio()
    private val target = SessionMediaKeyTarget(SessionManager.get(ApplicationProvider.getApplicationContext<Context>()))

    private fun self(muted: Boolean, deafened: Boolean) =
        session.stubModel(selfServerState(UserState(1, "me", 0, isSelfMuted = muted, isSelfDeafened = deafened)))

    @Test
    fun toggleMuteMutesAnUnmutedUserKeepingDeafenOff() {
        self(muted = false, deafened = false)

        target.toggleSelfMute()

        verify(exactly = 1) { session.actions.setSelfMuteDeafState(true, false) }
    }

    @Test
    fun toggleMuteUnmutesAMutedAndDeafenedUserAndUndeafens() {
        self(muted = true, deafened = true)

        target.toggleSelfMute()

        verify(exactly = 1) { session.actions.setSelfMuteDeafState(false, false) }
    }

    @Test
    fun toggleMuteDoesNothingWithoutSessionUser() {
        session.stubModel(serverState { channel(0, "Root") })

        target.toggleSelfMute()

        verify(exactly = 0) { session.actions.setSelfMuteDeafState(any(), any()) }
    }

    @Test
    fun stopTalkingTurnsTalkingOff() {
        target.stopTalking()

        verify(exactly = 1) { audio.setTalking(false) }
    }

    /**
     * Unconditional by contract: callers are lifecycle events (disconnect, released session,
     * "none" action), where the cached talking state is exactly what is distrusted.
     */
    @Test
    fun stopTalkingWritesTheOffStateEvenWhenAlreadyOff() {
        every { audio.isTalking } returns false

        target.stopTalking()

        verify(exactly = 1) { audio.setTalking(false) }
        verify(exactly = 0) { audio.isTalking }
    }

    /** Once disconnected, nothing is written: a reconnect must not inherit a reset it did not ask for. */
    @Test
    fun stopTalkingWhileDisconnectedIsANoOp() {
        session.stubState(SessionState.Disconnected())

        target.stopTalking()

        verify(exactly = 0) { audio.setTalking(any()) }
        assertThat(target.isConnected).isFalse()
    }
}
