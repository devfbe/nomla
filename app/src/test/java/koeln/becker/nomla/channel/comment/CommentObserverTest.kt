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

import androidx.fragment.app.DialogFragment
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import koeln.becker.nomla.mumble.model.UserState
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.ActionCall
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.testing.ServiceHostActivity
import koeln.becker.nomla.testing.serverState

@RunWith(RobolectricTestRunner::class)
class CommentObserverTest {

    private val session = FakeSession(SessionState.Connected, model = withComment(7, null))
    private val model = session.model
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
        .also { it.bind(session) }

    /** Whatever else follows the model (the session's own observers) all along. */
    private val othersListening = model.subscriptionCount.value

    private val listeners: Int get() = model.subscriptionCount.value - othersListening

    private fun withComment(user: Int, comment: String?) = serverState {
        channel(0, "Root")
        user(UserState(7, "Ann", 0, comment = if (user == 7) comment else null))
        user(UserState(8, "Bob", 0, comment = if (user == 8) comment else null))
    }

    private fun show(fragment: DialogFragment) {
        fragment.show(activity.supportFragmentManager, "comment")
        idleMainLooper()
    }

    private fun close(fragment: DialogFragment) {
        fragment.dismiss()
        idleMainLooper()
    }

    @Test
    fun aCommentOrDescriptionStopsWaitingWhenTheDialogClosesWithoutAReply() {
        val requests = listOf<Pair<DialogFragment, ActionCall>>(
            UserCommentFragment.newInstance(7, comment = null, editing = false) to
                ActionCall("requestComment", listOf(7)),
            ChannelDescriptionFragment.newInstance(3, description = null) to
                ActionCall("requestChannelDescription", listOf(3)),
        )
        for ((fragment, request) in requests) {
            show(fragment)
            assertThat(listeners).isEqualTo(1)
            assertThat(session.actions.calls).contains(request)

            close(fragment)

            assertThat(listeners).isEqualTo(0)
        }
    }

    @Test
    fun theReplyStopsTheWaitingAndClosingAfterwardsIsHarmless() {
        val fragment = UserCommentFragment.newInstance(7, comment = null, editing = false)
        show(fragment)

        model.value = withComment(7, "hello")
        idleMainLooper()
        assertThat(listeners).isEqualTo(0)

        close(fragment)
        assertThat(listeners).isEqualTo(0)
    }

    @Test
    fun anotherUsersCommentKeepsTheWaiting() {
        val fragment = UserCommentFragment.newInstance(7, comment = null, editing = false)
        show(fragment)

        model.value = withComment(8, "not yours")
        idleMainLooper()

        assertThat(listeners).isEqualTo(1)
        close(fragment)
    }
}
