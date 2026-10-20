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

package se.lublin.mumla.preference

import android.app.Application
import android.content.pm.PackageInfo
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.testing.hostInThemedActivity

/** "Connect via Tor" is greyed out unless the Orbot package is installed. */
@RunWith(RobolectricTestRunner::class)
class ConnectionSettingsOrbotTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun torPreference(): Preference {
        val fragment = hostInThemedActivity(ConnectionSettingsFragment())
        return requireNotNull(fragment.preferenceScreen.findPreference("useTor")) {
            "no preference with key 'useTor' on the connection settings screen"
        }
    }

    @Test
    fun theTorPreferenceIsGreyedOutWhenOrbotIsMissing() {
        assertThat(torPreference().isEnabled).isFalse()
    }

    @Test
    fun theTorPreferenceIsUsableOnceOrbotIsInstalled() {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply { packageName = ORBOT_PACKAGE },
        )

        assertThat(torPreference().isEnabled).isTrue()
    }

    private companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
    }
}
