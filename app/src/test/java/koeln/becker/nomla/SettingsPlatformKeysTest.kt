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

package koeln.becker.nomla

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsPlatformKeysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = Settings.getInstance(context)

    /** A connected Bluetooth headset is used without being asked for, as in the phone app. */
    @Test
    fun bluetoothScoIsOnByDefault() {
        assertThat(settings.isBluetoothScoEnabled).isTrue()
    }

    @Test
    fun bluetoothScoIsPersistedUnderTheSpecKey() {
        settings.isBluetoothScoEnabled = false

        val raw = PreferenceManager.getDefaultSharedPreferences(context)
        assertThat(raw.getBoolean("pref_bluetooth_sco", true)).isFalse()
        assertThat(Settings.getInstance(context).isBluetoothScoEnabled).isFalse()
    }

    /** Unset and unknown values mean AUTO. */
    @Test
    fun mediaButtonActionReadsTheStoredValue() {
        assertThat(settings.mediaButtonAction).isEqualTo(MediaButtonAction.AUTO)
        for ((stored, action) in listOf("mute" to MediaButtonAction.MUTE, "bogus" to MediaButtonAction.AUTO)) {
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString("media_button_action", stored).commit()
            assertWithMessage(stored).that(settings.mediaButtonAction).isEqualTo(action)
        }
    }

    @Test
    fun batteryOptimizationAskedDefaultsToFalseAndPersists() {
        assertThat(settings.isBatteryOptimizationAsked).isFalse()

        settings.isBatteryOptimizationAsked = true

        assertThat(Settings.getInstance(context).isBatteryOptimizationAsked).isTrue()
    }
}
