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

package se.lublin.mumla.app

import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.R
import se.lublin.mumla.databinding.ActivityMainBinding
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.laidOutRows

@RunWith(RobolectricTestRunner::class)
class MainDrawerTest {
    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val binding = ActivityMainBinding.inflate(activity.layoutInflater).also { activity.setContentView(it.root) }
    private var serverName: String? = null
    private val selected = mutableListOf<Int>()
    private val drawer = MainDrawer(
        activity, binding.drawerLayout, binding.leftDrawer, binding.toolbar, { serverName }, { selected += it },
    )

    private fun rowTitled(title: String) = binding.leftDrawer.laidOutRows().single { row ->
        row.findViewById<TextView>(R.id.drawer_item_title)?.text == title
    }

    @Test
    fun `the server screens are disabled until connected, and the header names the server`() {
        assertThat(rowTitled(activity.getString(R.string.drawer_server)).isEnabled).isFalse()
        rowTitled(activity.getString(R.string.drawer_server)).performClick()
        assertThat(selected).isEmpty()

        serverName = "Example"
        drawer.refresh()

        assertThat(rowTitled(activity.getString(R.string.drawer_server)).isEnabled).isTrue()
        rowTitled(activity.getString(R.string.drawer_server)).performClick()
        assertThat(selected).containsExactly(DrawerAdapter.ITEM_SERVER)
        val headers = binding.leftDrawer.laidOutRows().mapNotNull {
            it.findViewById<TextView>(R.id.drawer_header_title)?.text?.toString()
        }
        assertThat(headers).contains("Example")
    }

    @Test
    fun `an item selects its screen, which it names`() {
        rowTitled(activity.getString(R.string.drawer_favorites)).performClick()

        assertThat(selected).containsExactly(DrawerAdapter.ITEM_FAVOURITES)
        assertThat(drawer.title(DrawerAdapter.ITEM_FAVOURITES)).isEqualTo(activity.getString(R.string.drawer_favorites))
    }
}
