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

package koeln.becker.nomla.channel

import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.testing.ThemedActivity

@RunWith(RobolectricTestRunner::class)
class PermissionsPopupMenuTest {

    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val anchor = View(activity).also { activity.setContentView(it) }
    private val permissions = MutableStateFlow(0)
    private var requests = 0
    private val prepared = mutableListOf<Int>()

    private val listener = object : PermissionsPopupMenu.IOnMenuPrepareListener {
        override fun onMenuPrepare(menu: Menu, permissions: Int) {
            prepared += permissions
        }

        override fun onMenuItemClick(item: MenuItem): Boolean = false
    }

    private fun popup() = PermissionsPopupMenu(activity, anchor, R.menu.context_channel, listener, permissions) {
        requests++
    }

    @Test
    fun unknownPermissionsAreRequestedAndTheMenuIsPreparedWhenTheyArrive() {
        val popup = popup()
        popup.show()
        assertThat(prepared).isEmpty()
        assertThat(requests).isEqualTo(1)

        permissions.value = 0x40
        idleMainLooper()

        assertThat(prepared).containsExactly(0x40)
        popup.dismiss()
    }

    @Test
    fun knownPermissionsPrepareTheMenuAtOnceAndAgainWhenTheyChange() {
        permissions.value = 0x10
        val popup = popup()
        popup.show()

        permissions.value = 0x30
        idleMainLooper()

        assertThat(prepared).containsExactly(0x10, 0x30).inOrder()
        assertThat(requests).isEqualTo(0)
        popup.dismiss()
    }

    @Test
    fun theMenuListensOnlyWhileShown() {
        val popup = popup()
        popup.show()
        assertThat(permissions.subscriptionCount.value).isEqualTo(1)

        popup.dismiss()
        idleMainLooper()

        assertThat(permissions.subscriptionCount.value).isEqualTo(0)
    }
}
