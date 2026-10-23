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
package koeln.becker.nomla.audio

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import koeln.becker.nomla.R
import koeln.becker.nomla.testing.ThemedActivity
import koeln.becker.nomla.testing.drainMainUntil
import koeln.becker.nomla.testing.resetSnackbars
import koeln.becker.nomla.testing.snackbarAction
import koeln.becker.nomla.testing.snackbarText

/** The talk-over test asks for the microphone on Start, and says so when the user says no. */
@RunWith(RobolectricTestRunner::class)
class DoubleTalkTestSheetTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var activity: ThemedActivity

    @Before
    fun setUp() {
        resetSnackbars()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        DoubleTalkTestSheet.show(activity.supportFragmentManager)
        drainMainUntil(description = "the sheet's view") { sheet()?.view != null }
    }

    private fun sheet() =
        activity.supportFragmentManager.findFragmentByTag(DoubleTalkTestSheet.TAG) as? DoubleTalkTestSheet

    /** Hands the sheet's launcher the answer the permission dialog would give. */
    private fun answerThePermissionDialog(granted: Boolean) {
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission) { "nothing asked for a permission" }
        assertThat(request.requestedPermissions.toList()).containsExactly(Manifest.permission.RECORD_AUDIO)
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { result },
        )
    }

    @Test
    fun aDeniedMicrophoneSaysWhyTheTestDoesNotStartAndOffersTheSettings() {
        val view = sheet()!!.requireView()
        view.findViewById<View>(R.id.double_talk_toggle).performClick()

        answerThePermissionDialog(granted = false)

        assertThat(snackbarText(view)).isEqualTo(app.getString(R.string.grant_perm_microphone))
        val action = snackbarAction(view)
        assertThat(action.text.toString()).isEqualTo(app.getString(R.string.open_settings))
        action.performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.action).isEqualTo(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertThat(started.data.toString()).isEqualTo("package:${app.packageName}")
    }
}
