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

package koeln.becker.nomla.preference

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.testing.resetSnackbars
import koeln.becker.nomla.testing.snackbarText
import koeln.becker.nomla.testing.ThemedActivity
import koeln.becker.nomla.testing.host

/**
 * `BLUETOOTH_CONNECT` is asked for when the user ticks the Bluetooth switch. The box is driven
 * with `performClick()`, the path a tap takes; `callChangeListener(...)` neither ticks nor
 * persists anything.
 */
@RunWith(RobolectricTestRunner::class)
class AudioSettingsBluetoothTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = Settings.getInstance(app)
    private lateinit var activity: ThemedActivity
    private lateinit var fragment: AudioSettingsFragment

    private fun open() {
        resetSnackbars()
        activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        fragment = activity.host(AudioSettingsFragment())
    }

    /** The default is on; ticking the box is the gesture that asks for the permission. */
    private fun openSwitchedOff() {
        settings.isBluetoothScoEnabled = false
        open()
    }

    private fun checkBox(): SwitchPreferenceCompat {
        val preference = fragment.preferenceScreen
            .findPreference<Preference>(Settings.BLUETOOTH_SCO.key)
        assertWithMessage(
            "no preference with key '%s' on the audio settings screen",
            Settings.BLUETOOTH_SCO.key,
        ).that(preference).isNotNull()
        assertThat(preference).isInstanceOf(SwitchPreferenceCompat::class.java)
        return preference as SwitchPreferenceCompat
    }

    private fun lastRequestedPermissions(): List<String> =
        shadowOf(activity).lastRequestedPermission?.requestedPermissions?.toList() ?: emptyList()

    /** Hands the fragment's `ActivityResultLauncher` the answer the system would deliver. */
    private fun answerThePermissionDialog(granted: Boolean) {
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission) {
            "nothing asked for a permission, so there is no dialog to answer"
        }
        if (granted) {
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        }
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
    }

    @Test
    fun theSwitchFollowsTheAudioDeviceAndStartsFromTheCodeDefault() {
        open()
        val screen = fragment.preferenceScreen
        val order = (0 until screen.preferenceCount).map { screen.getPreference(it).key }
        assertThat(order.indexOf(Settings.BLUETOOTH_SCO.key)).isEqualTo(order.indexOf(Settings.AUDIO_DEVICE.key) + 1)

        // Two sources for one default: android:defaultValue on this screen, and
        // Settings.BLUETOOTH_SCO.default, which every other reader gets.
        assertThat(checkBox().isChecked).isEqualTo(Settings.BLUETOOTH_SCO.default)
        assertThat(settings.isBluetoothScoEnabled).isEqualTo(Settings.BLUETOOTH_SCO.default)
    }

    @Test
    fun theSummarySaysThatThePermissionIsNeeded() {
        open()
        // The box can be tapped and stay empty; only the text under it can explain that.
        val summary = checkBox().summary.toString().lowercase()

        assertWithMessage("the summary must name the permission the tick will ask for: %s", summary)
            .that(summary).containsMatch("""nearby devices""")
    }

    @Test
    fun tickingItWithoutThePermissionAsksForItAndLeavesTheBoxEmpty() {
        openSwitchedOff()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(lastRequestedPermissions()).contains(Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(checkBox().isChecked).isFalse()
        assertThat(settings.isBluetoothScoEnabled).isFalse()
    }

    @Test
    fun tickingItWithThePermissionPersistsIt() {
        openSwitchedOff()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(checkBox().isChecked).isTrue()
        assertThat(settings.isBluetoothScoEnabled).isTrue()
        assertThat(lastRequestedPermissions()).isEmpty()
    }

    @Test
    fun untickingItNeverAsksForThePermission() {
        openSwitchedOff()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        checkBox().performClick()

        assertThat(checkBox().isChecked).isFalse()
        assertThat(settings.isBluetoothScoEnabled).isFalse()
        assertThat(lastRequestedPermissions()).isEmpty()
    }

    @Test
    fun grantingThePermissionAfterwardsTurnsItOnAndTicksTheBox() {
        openSwitchedOff()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()

        answerThePermissionDialog(granted = true)

        assertThat(settings.isBluetoothScoEnabled).isTrue()
        assertThat(checkBox().isChecked).isTrue()
        assertThat(activity.snackbarText()).isNull()
    }

    /** A denial is not a "no": the preference is kept regardless. */
    @Test
    fun denyingThePermissionStillTurnsItOnAndSaysWhatItMayCost() {
        openSwitchedOff()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        checkBox().performClick()

        answerThePermissionDialog(granted = false)

        assertThat(settings.isBluetoothScoEnabled).isTrue()
        assertThat(checkBox().isChecked).isTrue()
        assertThat(activity.snackbarText()).isEqualTo(app.getString(R.string.bluetooth_perm_denied))
    }
}
