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
import android.view.Window
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSettings
import koeln.becker.nomla.mumble.INomlaSession
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.MainScreen
import koeln.becker.nomla.R
import koeln.becker.nomla.service.NomlaService
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.launchNomlaActivity
import koeln.becker.nomla.testing.stubState

/** The overlay moved from the notification into the app's overflow menu. */
@RunWith(RobolectricTestRunner::class)
class NomlaActivityOverlayMenuTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val session: INomlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }
    private val state = session.stubState(SessionState.Connected)

    private fun launch(): NomlaActivity {
        val intent = Intent(app, NomlaActivity::class.java).putExtra(MainScreen.EXTRA_SCREEN, DrawerAdapter.ITEM_SERVER)
        val activity = launchNomlaActivity(intent)
        installSession(session)
        idleMainLooper()
        return activity
    }

    private fun NomlaActivity.selectOverlay() {
        val menu = MenuBuilder(this)
        onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        val item = menu.findItem(R.id.action_overlay)
        assertThat(item.isVisible).isTrue()
        onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, item)
        idleMainLooper()
    }

    private fun startedServiceActions(): List<String?> =
        generateSequence { shadowOf(app).nextStartedService }.map { it.action }.toList()

    @Test
    fun withThePermissionTheServiceTogglesTheOverlay() {
        ShadowSettings.setCanDrawOverlays(true)
        val activity = launch()
        startedServiceActions()

        activity.selectOverlay()

        val started = shadowOf(app).nextStartedService
        assertThat(started.component?.className).isEqualTo(NomlaService::class.java.name)
        assertThat(started.action).isNotNull()
    }

    @Test
    fun withoutThePermissionTheUserIsAskedToGrantItFirst() {
        ShadowSettings.setCanDrawOverlays(false)
        val activity = launch()
        startedServiceActions()

        activity.selectOverlay()
        assertThat(startedServiceActions()).isEmpty()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(dialog.isShowing).isTrue()
        assertThat(dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
            .isEqualTo(app.getString(R.string.grant_perm_draw_over_apps))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        assertThat(shadowOf(app).nextStartedActivity.action)
            .isEqualTo(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    }

    @Test
    fun theOverlayIsOfferedOnlyWhileConnected() {
        state.value = SessionState.Disconnected()
        val activity = launch()
        val menu = MenuBuilder(activity)
        activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)

        assertThat(menu.findItem(R.id.action_overlay).isVisible).isFalse()
    }
}
