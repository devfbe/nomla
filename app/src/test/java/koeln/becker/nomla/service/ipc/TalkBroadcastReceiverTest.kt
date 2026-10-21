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

package koeln.becker.nomla.service.ipc

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.INomlaSession
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.stubAudio
import koeln.becker.nomla.testing.stubConnected
import koeln.becker.nomla.testing.stubState

@RunWith(RobolectricTestRunner::class)
class TalkBroadcastReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session = mockk<INomlaSession>(relaxed = true).also { installSession(it.stubConnected()) }
    private val audio = session.stubAudio()
    private val sessions = SessionManager.get(context)

    private fun talk(status: String) =
        Intent(TalkBroadcastReceiver.BROADCAST_TALK).putExtra(TalkBroadcastReceiver.EXTRA_TALK_STATUS, status)

    @Test
    fun broadcastsAreIgnoredWhileOtherAppsMayNotControlPushToTalk() {
        TalkBroadcastReceiver(sessions) { false }.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_ON))

        verify(exactly = 0) { audio.setTalking(any()) }
    }

    @Test
    fun broadcastsAreIgnoredWithoutAConnectedSession() {
        session.stubState(SessionState.Reconnecting(null))

        TalkBroadcastReceiver(sessions) { true }.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_ON))

        verify(exactly = 0) { audio.setTalking(any()) }
    }

    @Test
    fun broadcastsControlPushToTalkWhenAllowed() {
        val receiver = TalkBroadcastReceiver(sessions) { true }

        receiver.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_ON))
        verify { audio.setTalking(true) }

        receiver.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_OFF))
        verify { audio.setTalking(false) }
    }
}
