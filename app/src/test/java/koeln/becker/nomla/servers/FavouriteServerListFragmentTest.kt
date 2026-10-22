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

package koeln.becker.nomla.servers

import android.content.DialogInterface
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.app.QuickStartServer
import koeln.becker.nomla.testing.ServiceHostActivity
import koeln.becker.nomla.testing.drainMainUntil
import koeln.becker.nomla.testing.host

@RunWith(RobolectricTestRunner::class)
class FavouriteServerListFragmentTest {
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
    private val server = Server(3, "Home", "home.example", 0, "me", null)

    private fun show(servers: List<Server>): FavouriteServerListFragment {
        every { activity.database.getServers() } returns servers
        val fragment = activity.host(FavouriteServerListFragment())
        idleMainLooper()
        return fragment
    }

    private fun FavouriteServerListFragment.find(id: Int): View = requireView().findViewById(id)

    private val FavouriteServerListFragment.grid: RecyclerView get() = find(R.id.server_list_grid) as RecyclerView
    private val FavouriteServerListFragment.empty: View get() = find(R.id.server_list_grid_empty)
    private val FavouriteServerListFragment.fab: View get() = find(R.id.server_list_add)
    private val FavouriteServerListFragment.quickStart: TextView
        get() = find(R.id.server_list_empty_quick_start) as TextView

    private fun FavouriteServerListFragment.click(id: Int) {
        find(id).performClick()
        idleMainLooper()
    }

    /** The server dialog that is up, by its title. */
    private fun shownEditorTitle(): String? {
        val editor = activity.supportFragmentManager.fragments.filterIsInstance<ServerEditFragment>().singleOrNull()
        val dialog = editor?.dialog as? AlertDialog ?: return null
        return dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()
    }

    @Test
    fun `without servers the empty state offers to add or browse, instead of the button`() {
        val fragment = show(emptyList())

        assertThat(fragment.grid.adapter!!.itemCount).isEqualTo(0)
        assertThat(fragment.empty.visibility).isEqualTo(View.VISIBLE)
        assertThat(fragment.find(R.id.server_list_empty_add).visibility).isEqualTo(View.VISIBLE)
        assertThat(fragment.find(R.id.server_list_empty_browse).visibility).isEqualTo(View.VISIBLE)
        assertThat(fragment.fab.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `the empty state's add button opens the add dialog`() {
        val fragment = show(emptyList())

        fragment.click(R.id.server_list_empty_add)

        assertThat(shownEditorTitle()).isEqualTo(activity.getString(R.string.server_add))
    }

    @Test
    fun `the empty state's browse button asks for the public servers`() {
        var asked = 0
        activity.supportFragmentManager.setFragmentResultListener(
            FavouriteServerListFragment.REQUEST_BROWSE_PUBLIC,
            activity,
        ) { _, _ -> asked++ }
        val fragment = show(emptyList())

        fragment.click(R.id.server_list_empty_browse)

        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun `the empty state offers to just talk, labelled and explained`() {
        val fragment = show(emptyList())

        assertThat(fragment.quickStart.isShown).isTrue()
        assertThat(fragment.quickStart.text.toString()).isEqualTo(activity.getString(R.string.quick_start))
        // The label TalkBack reads is the button's text, not a description that hides it.
        assertThat(fragment.quickStart.contentDescription).isNull()
        val hint = fragment.find(R.id.server_list_empty_quick_start_hint) as TextView
        assertThat(hint.isShown).isTrue()
        assertThat(hint.text.toString()).isEqualTo(QuickStartServer.hint(activity))
        assertThat(hint.text.toString()).contains("mumble.coding4.coffee")
    }

    @Test
    fun `the just talk button asks for the quick start`() {
        var asked = 0
        activity.supportFragmentManager.setFragmentResultListener(
            FavouriteServerListFragment.REQUEST_QUICK_START,
            activity,
        ) { _, _ -> asked++ }
        val fragment = show(emptyList())

        fragment.click(R.id.server_list_empty_quick_start)

        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun `with servers there is no just talk button`() {
        val fragment = show(listOf(server))

        assertThat(fragment.quickStart.isShown).isFalse()
    }

    @Test
    fun `with servers the add button shows, and opens the add dialog`() {
        val fragment = show(listOf(server))
        assertThat(fragment.empty.visibility).isEqualTo(View.GONE)
        assertThat(fragment.fab.visibility).isEqualTo(View.VISIBLE)
        assertThat(fragment.fab.contentDescription ?: (fragment.fab as TextView).text)
            .isEqualTo(activity.getString(R.string.server_add))

        fragment.click(R.id.server_list_add)

        assertThat(shownEditorTitle()).isEqualTo(activity.getString(R.string.server_add))
    }

    @Test
    fun `the last card can scroll clear of the add button`() {
        val fragment = show(listOf(server))

        assertThat(fragment.grid.clipToPadding).isFalse()
        assertThat(fragment.grid.paddingBottom)
            .isAtLeast(activity.resources.getDimensionPixelSize(R.dimen.server_list_fab_clearance))
    }

    @Test
    fun `editing a server opens the edit dialog`() {
        val fragment = show(listOf(server))

        fragment.editServer(server)
        idleMainLooper()

        assertThat(shownEditorTitle()).isEqualTo(activity.getString(R.string.edit_server))
    }

    @Test
    fun `a stored server gets a card, and deleting it removes card and row`() {
        val fragment = show(listOf(server))
        assertThat(fragment.grid.adapter!!.itemCount).isEqualTo(1)
        assertThat(fragment.empty.visibility).isEqualTo(View.GONE)

        fragment.deleteServer(server)
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        verify { activity.database.removeServer(server) }
        // The list is diffed off the main thread.
        drainMainUntil(description = "the empty notice") { fragment.empty.visibility == View.VISIBLE }
        assertThat(fragment.fab.visibility).isEqualTo(View.GONE)
    }
}
