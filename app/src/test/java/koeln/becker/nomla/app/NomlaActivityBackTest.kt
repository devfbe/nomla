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
package koeln.becker.nomla.app

import androidx.appcompat.app.AlertDialog
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import koeln.becker.nomla.mumble.INomlaSession
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.launchNomlaActivity
import koeln.becker.nomla.testing.message
import koeln.becker.nomla.testing.stubState

/** Back asks before leaving a connected server, and only then: predictive back works otherwise. */
@RunWith(RobolectricTestRunner::class)
class NomlaActivityBackTest {

    private val session: INomlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private val state = session.stubState(SessionState.Disconnected())
    private lateinit var activity: NomlaActivity

    @Before
    fun setUp() {
        activity = launchNomlaActivity()
        installSession(session)
        idleMainLooper()
    }

    private val intercepted get() = activity.onBackPressedDispatcher.hasEnabledCallbacks()

    @Test
    fun backIsNotInterceptedWhileDisconnected() {
        assertThat(intercepted).isFalse()
    }

    @Test
    fun backIsInterceptedOnlyWhileConnected() {
        state.value = SessionState.Connected
        idleMainLooper()
        assertThat(intercepted).isTrue()

        state.value = SessionState.Disconnected()
        idleMainLooper()
        assertThat(intercepted).isFalse()
    }

    @Test
    fun backWhileConnectedAsksBeforeDisconnecting() {
        state.value = SessionState.Connected
        idleMainLooper()

        activity.onBackPressedDispatcher.onBackPressed()
        idleMainLooper()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(dialog.message())
            .isEqualTo(activity.getString(R.string.disconnectSure, "Home"))
        assertThat(activity.isFinishing).isFalse()
    }

    @Test
    fun aNewSessionThatIsNotConnectedYetStopsTheInterception() {
        state.value = SessionState.Connected
        idleMainLooper()

        installSession(mockk<INomlaSession>(relaxed = true).also { it.stubState(SessionState.Connecting) })
        idleMainLooper()

        assertThat(intercepted).isFalse()
    }
}
