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

package koeln.becker.nomla.channel.comment

import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.R
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.testing.ServiceHostActivity

/** The comment dialog shows the rendered comment and its source on two tabs. */
@RunWith(RobolectricTestRunner::class)
class CommentTabsTest {
    private val session = FakeSession(SessionState.Connected)
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
        .also { it.bind(session) }

    private fun show(editing: Boolean): AlertDialog {
        val fragment = UserCommentFragment.newInstance(7, "<b>hi</b>", editing)
        fragment.show(activity.supportFragmentManager, "comment")
        idleMainLooper()
        return fragment.requireDialog() as AlertDialog
    }

    private val AlertDialog.tabs get() = findViewById<TabLayout>(R.id.comment_tabs)!!
    private val AlertDialog.source get() = findViewById<EditText>(R.id.comment_edit)!!
    private val AlertDialog.rendered get() = findViewById<WebView>(R.id.comment_view)!!

    @Test
    fun `viewing opens on the rendered comment`() {
        val dialog = show(editing = false)

        assertThat(dialog.tabs.selectedTabPosition).isEqualTo(0)
        assertThat(dialog.rendered.isShown).isTrue()
        assertThat(dialog.source.isShown).isFalse()
        assertThat(dialog.tabs.getTabAt(1)!!.text).isEqualTo(activity.getString(R.string.comment_view_source))
    }

    @Test
    fun `editing opens on the source, and the rendered tab shows the edited source`() {
        val dialog = show(editing = true)

        assertThat(dialog.tabs.selectedTabPosition).isEqualTo(1)
        assertThat(dialog.source.isShown).isTrue()
        assertThat(dialog.source.text.toString()).isEqualTo("<b>hi</b>")

        dialog.source.setText("<i>bye</i>")
        dialog.tabs.selectTab(dialog.tabs.getTabAt(0))

        assertThat(dialog.rendered.isShown).isTrue()
        assertThat(shadowOf(dialog.rendered).lastLoadData.data).isEqualTo("<i>bye</i>")
    }
}
