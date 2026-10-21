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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.testing.ThemedActivity

/**
 * The foss flavor's drawer has no donation row: the one inherited from Mumla went to Mumla's
 * author, and Nomla has no donation page of its own. [MainDrawer] shows the row only when the
 * flavor defines both resources it looks up by name.
 */
@RunWith(RobolectricTestRunner::class)
class MainDrawerDonationTest {
    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()

    @Test
    fun theDrawerHasNoDonationLink() {
        val resources = activity.resources
        assertThat(resources.getIdentifier("donate_link_foss", "string", activity.packageName)).isEqualTo(0)
        assertThat(resources.getIdentifier("list_drawer_headerdonate_foss", "xml", activity.packageName)).isEqualTo(0)
    }
}
