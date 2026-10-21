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

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.R

/** Claims about `fragment_chat.xml` that the fragment's `findViewById` calls rely on. */
@RunWith(RobolectricTestRunner::class)
class ChatLayoutTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val view: View = LayoutInflater.from(activity).inflate(R.layout.fragment_chat, null)

    @Test
    fun theChatListIsARecyclerView() {
        assertThat(view.findViewById<View>(R.id.chat_list)).isInstanceOf(RecyclerView::class.java)
    }

    /** The spinner belongs to the two long operations only; it must not greet the user. */
    @Test
    fun theImageProgressIndicatorStartsHidden() {
        assertThat(view.findViewById<View>(R.id.chat_image_progress).visibility).isEqualTo(View.GONE)
    }

    /** Both compose-row buttons are icon-only, so they need a content description. */
    @Test
    fun bothComposeButtonsAreLabelledForAccessibility() {
        assertThat(view.findViewById<View>(R.id.chatImageSend).contentDescription.toString())
            .isEqualTo(activity.getString(R.string.image_confirm_send))
        assertThat(view.findViewById<View>(R.id.chatTextSend).contentDescription.toString())
            .isEqualTo(activity.getString(R.string.send_message))
    }

    /**
     * `android:enabled` is a `TextView` attribute, not a `View` one, so it has no effect on an
     * `ImageButton`. If a platform release starts honouring it, this fails.
     */
    @Test
    fun androidEnabledIsATextViewAttributeAndAnImageButtonIgnoresIt() {
        val attrs = Robolectric.buildAttributeSet().addAttribute(android.R.attr.enabled, "false").build()
        assertThat(ImageButton(activity, attrs).isEnabled).isTrue()
        assertThat(TextView(activity, attrs).isEnabled).isFalse()
    }
}
