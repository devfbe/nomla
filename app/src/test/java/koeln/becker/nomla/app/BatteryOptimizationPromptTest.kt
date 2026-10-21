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

import android.app.Application
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import koeln.becker.nomla.mumble.INomlaSession
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.testing.installSession
import koeln.becker.nomla.testing.launchNomlaActivity
import koeln.becker.nomla.testing.message
import koeln.becker.nomla.testing.stubState
import koeln.becker.nomla.testing.stubConnected
import koeln.becker.nomla.testing.stubDisconnected

/** NomlaActivity offers the battery optimisation exemption once, after a connection succeeds. */
@RunWith(RobolectricTestRunner::class)
class BatteryOptimizationPromptTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val settings = Settings.getInstance(app)
    private lateinit var activity: NomlaActivity

    private fun launch() {
        activity = launchNomlaActivity()
    }

    /** Makes [session] current; null stands for an ended one, as after leaving the app. */
    private fun attach(session: INomlaSession?) {
        installSession(session ?: mockk<INomlaSession>(relaxed = true).stubDisconnected())
        idleMainLooper()
    }

    private fun connectedService() = mockk<INomlaSession>(relaxed = true).stubConnected()

    private fun exempt(value: Boolean) {
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, value)
    }

    private fun promptShown(): Boolean {
        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog ?: return false
        return dialog.isShowing &&
            dialog.message() ==
            app.getString(R.string.battery_optimization_prompt_message)
    }

    private fun prompt() = ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    fun itIsOfferedOnceConnectedWhileOptimized() {
        launch()

        attach(connectedService())

        assertThat(promptShown()).isTrue()
    }

    @Test
    fun itIsOfferedWhenTheSessionBecomesSynchronized() {
        launch()
        val session = mockk<INomlaSession>(relaxed = true).stubDisconnected()
        attach(session)
        assertThat(promptShown()).isFalse()

        session.stubState(SessionState.Connected)
        idleMainLooper()

        assertThat(promptShown()).isTrue()
    }

    @Test
    fun itIsNotOfferedWhileDisconnected() {
        launch()

        attach(mockk<INomlaSession>(relaxed = true).stubDisconnected())

        assertThat(promptShown()).isFalse()
    }

    @Test
    fun itIsNotOfferedWhenAlreadyExempt() {
        exempt(true)
        launch()

        attach(connectedService())

        assertThat(promptShown()).isFalse()
    }

    @Test
    fun itIsNotOfferedWhenAskedBefore() {
        settings.isBatteryOptimizationAsked = true
        launch()

        attach(connectedService())

        assertThat(promptShown()).isFalse()
    }

    @Test
    fun acceptingRequestsTheExemptionForMumla() {
        launch()
        attach(connectedService())

        prompt().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(started.data.toString()).isEqualTo("package:${app.packageName}")
        assertThat(settings.isBatteryOptimizationAsked).isTrue()
    }

    @Test
    fun decliningIsRememberedAndNothingIsStarted() {
        launch()
        attach(connectedService())

        prompt().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idleMainLooper()

        assertThat(shadowOf(activity).nextStartedActivity).isNull()
        assertThat(settings.isBatteryOptimizationAsked).isTrue()
        attach(null)
        attach(connectedService())
        assertThat(promptShown()).isFalse()
    }

    @Test
    fun anUnansweredPromptIsOfferedAgainOnReturn() {
        launch()
        attach(connectedService())
        prompt().dismiss() // e.g. the activity was paused
        idleMainLooper()

        attach(null)
        attach(connectedService())

        assertThat(promptShown()).isTrue()
        assertThat(settings.isBatteryOptimizationAsked).isFalse()
    }
}
