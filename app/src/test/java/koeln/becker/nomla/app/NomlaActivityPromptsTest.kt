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

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import koeln.becker.nomla.mumble.INomlaSession
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.MainScreen
import koeln.becker.nomla.R
import koeln.becker.nomla.db.PublicServer
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.launchNomlaActivity
import koeln.becker.nomla.testing.stubEvents
import koeln.becker.nomla.testing.stubState
import koeln.becker.nomla.ui.ConnectRequests
import koeln.becker.nomla.ui.ServerRequest

/** Prompts of the main screen can each be left without doing anything. */
@RunWith(RobolectricTestRunner::class)
class NomlaActivityPromptsTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun latestDialog(): AlertDialog {
        idleMainLooper()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    @Test
    fun theUsernamePromptForAPublicServerCanBeCancelled() {
        val activity = launchNomlaActivity()
        val server = PublicServer("Public", null, "Sweden", null, "public.example", 64738, null, null)

        ViewModelProvider(activity)[ConnectRequests::class.java].request(ServerRequest.Public(server))
        val prompt = latestDialog()

        assertThat(prompt.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
            .isEqualTo(app.getString(android.R.string.cancel))
        prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idleMainLooper()
        assertThat(prompt.isShowing).isFalse()
    }

    @Test
    fun aPermissionDenialIsAcknowledgedWithOk() {
        val session = mockk<INomlaSession>(relaxed = true) {
            every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
        }
        session.stubState(SessionState.Connected)
        val events = session.stubEvents()
        val intent = Intent(app, NomlaActivity::class.java).putExtra(MainScreen.EXTRA_SCREEN, DrawerAdapter.ITEM_SERVER)
        launchNomlaActivity(intent)
        installSession(session)
        idleMainLooper()

        events.tryEmit(NomlaEvent.PermissionDenied(NomlaEvent.DenyType.OTHER, "no"))
        val denial = latestDialog()

        assertThat(denial.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            .isEqualTo(app.getString(android.R.string.ok))
        assertThat(denial.isShowing).isTrue()
    }
}
