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
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.model.UserState
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.selfServerState

@RunWith(RobolectricTestRunner::class)
class MuteTileServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val session = FakeSession(SessionState.Connected, model = model(muted = false))
    private val model = session.model

    private fun model(muted: Boolean, deafened: Boolean = false) =
        selfServerState(UserState(1, "me", 0, isSelfMuted = muted, isSelfDeafened = deafened))

    /** The tile service, listening, with [session] as the current session if [withSession]. */
    private fun listeningTile(withSession: Boolean = true): MuteTileService {
        if (withSession) installSession(session)
        val tile = Robolectric.buildService(MuteTileService::class.java).create().get()
        tile.onStartListening()
        idleMainLooper()
        return tile
    }

    @Test
    fun withoutASessionTheTileIsUnavailable() {
        val tile = listeningTile(withSession = false)

        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_UNAVAILABLE)
        assertThat(tile.qsTile.subtitle).isEqualTo(app.getString(R.string.drawer_not_connected))
    }

    @Test
    fun theTileIsActiveWhileWeAreMutedAndFollowsOurState() {
        val tile = listeningTile()
        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_INACTIVE)

        model.value = model(muted = true)
        idleMainLooper()

        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_ACTIVE)
        assertThat(tile.qsTile.subtitle).isEqualTo(app.getString(R.string.a11y_state_muted))
    }

    @Test
    fun aDisconnectMakesTheTileUnavailable() {
        val tile = listeningTile()

        session.state.value = SessionState.Disconnected()
        idleMainLooper()

        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun aNewSessionIsFollowedToo() {
        val tile = listeningTile()

        installSession(FakeSession(SessionState.Connecting))
        idleMainLooper()

        assertThat(tile.qsTile.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun aClickTogglesOurMute() {
        val tile = listeningTile()

        tile.onClick()

        assertThat(session.actions.selfMuteDeafStates).containsExactly(true to false)
    }

    @Test
    fun aClickWhileDisconnectedDoesNothing() {
        session.state.value = SessionState.Disconnected()
        val tile = listeningTile()

        tile.onClick()

        assertThat(session.actions.selfMuteDeafStates).isEmpty()
    }

    @Test
    fun theModelShowsUnavailableActiveAndInactive() {
        assertThat(MuteTileModel.of(null))
            .isEqualTo(MuteTileModel(Tile.STATE_UNAVAILABLE, R.string.drawer_not_connected))
        assertThat(MuteTileModel.of(true)).isEqualTo(MuteTileModel(Tile.STATE_ACTIVE, R.string.a11y_state_muted))
        assertThat(MuteTileModel.of(false)).isEqualTo(MuteTileModel(Tile.STATE_INACTIVE, null))
    }

    @Test
    fun unmutingUndeafensAndMutingKeepsDeafness() {
        model.value = model(muted = true, deafened = true)
        toggleSelfMute(session)
        assertThat(session.actions.selfMuteDeafStates).containsExactly(false to false)

        model.value = model(muted = false)
        toggleSelfMute(session)
        assertThat(session.actions.selfMuteDeafStates).containsExactly(false to false, true to false).inOrder()
    }
}
