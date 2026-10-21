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
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves that Robolectric runs in the app module and that
 * `testOptions.unitTests.includeAndroidResources = true` is in effect: without merged
 * resources, resolving a string resource fails instead of returning its value.
 */
@RunWith(RobolectricTestRunner::class)
class AppResourcesSmokeTest {

    @Test
    fun `the app module's merged resources are available to unit tests`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // applicationId and app_name vary per flavor.
        assertThat(context.packageName).isEqualTo(BuildConfig.APPLICATION_ID)
        assertThat(context.getString(R.string.app_name)).startsWith("Nomla")
    }
}
