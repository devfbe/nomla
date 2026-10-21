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

package koeln.becker.nomla.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.mockk
import koeln.becker.nomla.mumble.AudioControls
import koeln.becker.nomla.mumble.INomlaSession
import koeln.becker.nomla.mumble.SessionActions
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.session.SessionManager
import java.util.WeakHashMap

private val stateFlows = WeakHashMap<INomlaSession, MutableStateFlow<SessionState>>()
private val eventFlows = WeakHashMap<INomlaSession, MutableSharedFlow<NomlaEvent>>()
private val audioMocks = WeakHashMap<INomlaSession, AudioControls>()
private val actionMocks = WeakHashMap<INomlaSession, SessionActions>()

/** The mocked session's audio controls, as a mock of their own so their calls can be counted. */
fun INomlaSession.stubAudio(): AudioControls =
    audioMocks.getOrPut(this) {
        mockk<AudioControls>(relaxed = true).also { every { audio } returns it }
            .also { every { it.captureSilenced } returns MutableStateFlow(false) }
    }

/** The mocked session's actions, as a mock of their own so their calls can be counted. */
fun INomlaSession.stubActions(): SessionActions =
    actionMocks.getOrPut(this) { mockk<SessionActions>(relaxed = true).also { every { actions } returns it } }

/** The mocked session's state, stubbed as a flow the test moves; [state] now. */
fun INomlaSession.stubState(state: SessionState): MutableStateFlow<SessionState> =
    stateFlows.getOrPut(this) { MutableStateFlow(state).also { every { this@stubState.state } returns it } }
        .also { it.value = state }

/** The mocked session's events, stubbed as a flow the test emits into; the same flow on every call. */
fun INomlaSession.stubEvents(): MutableSharedFlow<NomlaEvent> = eventFlows.getOrPut(this) {
    MutableSharedFlow<NomlaEvent>(extraBufferCapacity = 64).also { every { events } returns it }
}

fun <T : INomlaSession> T.stubConnected(): T = apply {
    stubEvents()
    stubSnapshotsIfAbsent()
    stubState(SessionState.Connected)
}

fun <T : INomlaSession> T.stubDisconnected(): T = apply {
    stubEvents()
    stubSnapshotsIfAbsent()
    stubState(SessionState.Disconnected())
}

/** Makes [session] the app's current session, as a connect would. */
fun installSession(session: INomlaSession) {
    session.stubEvents()
    session.stubSnapshotsIfAbsent()
    if (stateFlows[session] == null) session.stubState(SessionState.Connected)
    SessionManager.get(ApplicationProvider.getApplicationContext<Context>()).adopt(session)
}
