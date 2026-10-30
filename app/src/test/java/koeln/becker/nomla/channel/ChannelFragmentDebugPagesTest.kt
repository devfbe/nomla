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

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.diagnostics.DiagnosticsLiveFragment
import koeln.becker.nomla.testing.ServiceHostActivity
import koeln.becker.nomla.testing.addNow
import koeln.becker.nomla.testing.hostWith
import java.util.Locale

/**
 * The debug switch adds a Live and a Recording page to the channel view: two more tabs on phones,
 * a tab row over the chat pane on wide screens. Off, the view is as it always was.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelFragmentDebugPagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var fragment: ChannelFragment

    private fun setDebug(on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Settings.DEBUG_FEATURES.key, on)
            .commit()
        idleMainLooper()
    }

    private fun show(debug: Boolean, state: SessionState = SessionState.Connected) {
        setDebug(debug)
        controller = hostWith(FakeSession(state))
        fragment = controller.get().addNow(ChannelFragment(), "channel", inContent = true)
        idleMainLooper()
    }

    private fun view(id: Int): View? = fragment.requireView().findViewById(id)

    private fun tabs() = view(R.id.channel_tabs) as TabLayout

    private fun pager() = view(R.id.channel_view_pager) as ViewPager2

    private fun tabTitles(): List<String> = (0 until tabs().tabCount).map { tabs().getTabAt(it)!!.text.toString() }

    private fun upper(id: Int) = context.getString(id).uppercase(Locale.getDefault())

    private fun showPage(position: Int) {
        pager().currentItem = position
        idleMainLooper()
    }

    @Test
    fun `debug off shows two tabs`() {
        show(debug = false)

        assertThat(tabs().tabCount).isEqualTo(2)
    }

    @Test
    fun `debug on shows Live and Recording after chat`() {
        show(debug = true)

        assertThat(tabTitles()).containsExactly(
            upper(R.string.channel),
            upper(R.string.chat),
            upper(R.string.debug_tab_live),
            upper(R.string.debug_tab_record),
        ).inOrder()
    }

    @Test
    fun `switching debug on and off while shown adds and removes the pages`() {
        show(debug = false)

        setDebug(true)
        assertThat(tabs().tabCount).isEqualTo(4)

        setDebug(false)
        assertThat(tabs().tabCount).isEqualTo(2)
    }

    @Test
    fun `switching debug off while on a debug page returns to two tabs and the chat`() {
        show(debug = true)
        showPage(3)

        setDebug(false)

        assertThat(tabs().tabCount).isEqualTo(2)
        assertThat(pager().currentItem).isEqualTo(1)
        assertThat(tabs().selectedTabPosition).isEqualTo(1)
    }

    @Test
    @Config(qualifiers = "sw600dp-w900dp-h600dp")
    fun `wide screens show side tabs only with debug on`() {
        show(debug = false)
        val sideTabs = view(R.id.side_tabs) as TabLayout
        assertThat(sideTabs.visibility).isEqualTo(View.GONE)

        setDebug(true)
        assertThat(sideTabs.visibility).isEqualTo(View.VISIBLE)
        assertThat(sideTabs.tabCount).isEqualTo(3)

        setDebug(false)
        assertThat(sideTabs.visibility).isEqualTo(View.GONE)
    }

    @Test
    @Config(qualifiers = "sw600dp-w900dp-h600dp")
    fun `a wide side tab swaps the chat pane, and debug off brings the chat back`() {
        show(debug = true)
        val sideTabs = view(R.id.side_tabs) as TabLayout

        sideTabs.getTabAt(1)!!.select()
        idleMainLooper()
        assertThat(paneFragment()).isInstanceOf(DiagnosticsLiveFragment::class.java)

        setDebug(false)
        assertThat(paneFragment()).isInstanceOf(ChannelChatFragment::class.java)
    }

    private fun paneFragment() = fragment.childFragmentManager.findFragmentById(R.id.chat_fragment)

    /** Switched off in the settings screen: the channel screen is stopped, its state saved. */
    private fun setDebugWhileStopped(on: Boolean) {
        controller.pause().stop().saveInstanceState(Bundle())
        setDebug(on)
        controller.start().resume()
        idleMainLooper()
    }

    @Test
    fun `debug switched off while stopped returns to the chat on the next start`() {
        show(debug = true)
        showPage(3)

        setDebugWhileStopped(false)

        assertThat(tabs().tabCount).isEqualTo(2)
        assertThat(pager().currentItem).isEqualTo(1)
    }

    @Test
    fun `debug switched on while stopped shows the pages on the next start`() {
        show(debug = false)

        setDebugWhileStopped(true)

        assertThat(tabs().tabCount).isEqualTo(4)
    }

    @Test
    @Config(qualifiers = "sw600dp-w900dp-h600dp")
    fun `wide, debug switched off while stopped brings the chat back on the next start`() {
        show(debug = true)
        val sideTabs = view(R.id.side_tabs) as TabLayout
        sideTabs.getTabAt(2)!!.select()
        idleMainLooper()

        setDebugWhileStopped(false)

        assertThat(sideTabs.visibility).isEqualTo(View.GONE)
        assertThat(paneFragment()).isInstanceOf(ChannelChatFragment::class.java)
    }

    @Test
    fun `the Live page shows the empty state without a connected session`() {
        show(debug = true, state = SessionState.Disconnected())
        showPage(2)

        val live = fragment.childFragmentManager.fragments.filterIsInstance<DiagnosticsLiveFragment>().single()
        val texts = mutableListOf<String>()
        collectTexts(live.requireView(), texts)
        assertThat(texts).contains(context.getString(R.string.debug_empty))
    }

    private fun collectTexts(view: View, into: MutableList<String>) {
        if (view is TextView && view.isShown) into += view.text.toString()
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectTexts(view.getChildAt(i), into)
        }
    }
}
