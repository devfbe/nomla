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

package koeln.becker.nomla.diagnostics

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.R as MaterialR
import com.google.android.material.tabs.TabLayout
import koeln.becker.nomla.R

/**
 * The debug pages the channel view shows while the debug features are on: as two more pager tabs on
 * phones, and as side tabs over the chat pane on wide screens.
 */
object DebugPages {
    /** The debug pages, in tab order after the channel list and the chat. */
    enum class Page(@field:StringRes val title: Int) {
        LIVE(R.string.debug_tab_live),
        RECORD(R.string.debug_tab_record),
    }

    /** A new fragment for [page]. */
    fun newPage(page: Page): Fragment = when (page) {
        Page.LIVE -> DiagnosticsLiveFragment()
        Page.RECORD -> DiagnosticsRecordFragment()
    }

    /** What a debug page shows while it has nothing to show. */
    fun emptyState(context: Context): View = TextView(context).apply {
        setText(R.string.debug_empty)
        gravity = Gravity.CENTER
        setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge)
        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, EMPTY_PADDING_DP, resources.displayMetrics,
        ).toInt()
        setPadding(padding, padding, padding, padding)
    }

    /**
     * Fills [tabs] with the chat and the debug pages; picking one shows it in [containerId] of
     * [fragments]. [chatTitle] and [newChat] are the chat tab's; [selected] the tab picked first.
     */
    fun attachSideTabs(
        tabs: TabLayout,
        fragments: FragmentManager,
        @IdRes containerId: Int,
        chatTitle: (Int) -> String,
        newChat: () -> Fragment,
        selected: Int = SideTabs.CHAT,
    ): SideTabs = SideTabs(tabs, fragments, containerId, newChat).also { it.fill(chatTitle, selected) }

    /** The wide screen's side tabs; see [attachSideTabs]. */
    class SideTabs internal constructor(
        private val tabs: TabLayout,
        private val fragments: FragmentManager,
        @param:IdRes private val containerId: Int,
        private val newChat: () -> Fragment,
    ) {
        /** The position of the picked tab: [CHAT] or 1 + a [Page]'s ordinal. */
        var selected: Int = CHAT
            private set

        internal fun fill(title: (Int) -> String, initial: Int) {
            tabs.addTab(tabs.newTab().setText(title(R.string.chat)), false)
            for (page in Page.entries) tabs.addTab(tabs.newTab().setText(title(page.title)), false)
            selected = initial.coerceIn(CHAT, Page.entries.size)
            tabs.getTabAt(selected)?.select()
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    selected = tab.position
                    fragments.beginTransaction().replace(containerId, newSelectedPage()).commit()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }

        /** A new fragment for the picked tab, for the container. */
        fun newSelectedPage(): Fragment = if (selected == CHAT) newChat() else newPage(Page.entries[selected - 1])

        /** Shows the tabs with [debug] on; off, hides them and brings the chat back. */
        fun show(debug: Boolean) {
            tabs.isVisible = debug
            if (!debug && selected != CHAT) tabs.getTabAt(CHAT)?.select()
        }

        companion object {
            const val CHAT = 0
        }
    }

    private const val EMPTY_PADDING_DP = 16f
}
