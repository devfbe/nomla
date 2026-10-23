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
package koeln.becker.nomla.chat

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import koeln.becker.nomla.mumble.Session
import koeln.becker.nomla.mumble.model.Message
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.testing.textMessage
import koeln.becker.nomla.testing.stubEvents
import koeln.becker.nomla.testing.stubState

/** What of a session's events lands in its chat log, and how. */
@RunWith(RobolectricTestRunner::class)
class SessionChatTest {
    private val app = RuntimeEnvironment.getApplication()
    private val chat = SessionChat(NoticeFormatter(app), MainScope(), capacity = 3)
    private val session = mockk<Session>(relaxed = true)
    private val state = session.stubState(SessionState.Connected)
    private val events: MutableSharedFlow<NomlaEvent> = session.stubEvents()

    init {
        chat.follow(session)
    }

    private fun emit(vararg emitted: NomlaEvent) {
        emitted.forEach { events.tryEmit(it) }
        idleMainLooper()
    }

    private fun message(body: String): Message = textMessage(body, actor = 1, actorName = "alice")

    @Test
    fun itStartsEmpty() {
        assertThat(chat.messages.value).isEmpty()
    }

    @Test
    fun noticesLandPhrasedAndWithTheirLevel() {
        val kick = NomlaEvent.UserKicked("Ann", "Mod", "spam", ban = false)

        emit(
            NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "careful"),
            NomlaEvent.LogMessage(NomlaEvent.Level.ERROR, "broken"),
            kick,
        )

        assertThat(chat.messages.value.map { (it as IChatMessage.InfoMessage).type to it.body }).containsExactly(
            IChatMessage.InfoMessage.Type.WARNING to "careful",
            IChatMessage.InfoMessage.Type.ERROR to "broken",
            IChatMessage.InfoMessage.Type.WARNING to NoticeFormatter(app).format(kick),
        ).inOrder()
    }

    @Test
    fun receivedAndSentMessagesLandAsTheSameMessages() {
        val received = message("hi there")
        val sent = message("hello")

        emit(NomlaEvent.TextMessage(received), NomlaEvent.MessageSent(sent))

        assertThat(chat.messages.value.map { (it as IChatMessage.TextMessage).message })
            .containsExactly(received, sent).inOrder()
    }

    @Test
    fun theOtherEventsLeaveNoLine() {
        emit(NomlaEvent.PermissionDenied(NomlaEvent.DenyType.OTHER, "no"))

        assertThat(chat.messages.value).isEmpty()
    }

    @Test
    fun theLogIsBoundedAndDropsTheOldestFirst() {
        emit(*Array(4) { NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "m$it") })

        assertThat(chat.messages.value.map { it.body }).containsExactly("m1", "m2", "m3").inOrder()
    }

    @Test
    fun theReturnedLogCannotBeWrittenThrough() {
        emit(NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "one"))

        @Suppress("UNCHECKED_CAST")
        val log = chat.messages.value as MutableList<IChatMessage>
        assertThrows(UnsupportedOperationException::class.java) {
            log.add(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, "sneaked in"))
        }
    }

    @Test
    fun clearEmptiesIt() {
        emit(NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "one"))

        chat.clearWithUndo()

        assertThat(chat.messages.value).isEmpty()
    }

    /** The log survives an automatic reconnect; the end of the session takes it along. */
    @Test
    fun onlyTheEndOfTheSessionClearsIt() {
        emit(NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "kept"))

        state.value = SessionState.ConnectionLost(1L, 1, null)
        state.value = SessionState.Reconnecting(null)
        idleMainLooper()
        assertThat(chat.messages.value).hasSize(1)

        state.value = SessionState.Disconnected()
        idleMainLooper()
        assertThat(chat.messages.value).isEmpty()
    }

    @Test
    fun receivedMessagesCountAsUnreadWhileTheChatIsNotShown() {
        emit(
            NomlaEvent.TextMessage(message("one")),
            NomlaEvent.MessageSent(message("mine")),
            NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "a notice"),
            NomlaEvent.TextMessage(message("two")),
        )

        assertThat(chat.unread.value).isEqualTo(2)
    }

    @Test
    fun showingTheChatReadsEverythingAndNothingCountsWhileShown() {
        emit(NomlaEvent.TextMessage(message("one")))

        chat.setShown(true)
        assertThat(chat.unread.value).isEqualTo(0)
        emit(NomlaEvent.TextMessage(message("two")))
        assertThat(chat.unread.value).isEqualTo(0)

        chat.setShown(false)
        emit(NomlaEvent.TextMessage(message("three")))
        assertThat(chat.unread.value).isEqualTo(1)
    }

    @Test
    fun theUnreadCountStartsOverWithTheLog() {
        emit(NomlaEvent.TextMessage(message("one")))
        chat.clearWithUndo()
        assertThat(chat.unread.value).isEqualTo(0)

        emit(NomlaEvent.TextMessage(message("two")))
        chat.follow(mockk<Session>(relaxed = true).also { it.stubState(SessionState.Connected); it.stubEvents() })
        assertThat(chat.unread.value).isEqualTo(0)

        chat.follow(session)
        emit(NomlaEvent.TextMessage(message("three")))
        state.value = SessionState.Disconnected()
        idleMainLooper()
        assertThat(chat.unread.value).isEqualTo(0)
    }

    private fun warning(body: String) = NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, body)

    @Test
    fun undoingAClearPutsTheMessagesBackBeforeAnyNewerOnes() {
        emit(warning("one"), warning("two"))

        val undo = chat.clearWithUndo()
        assertThat(chat.messages.value).isEmpty()
        emit(NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "three"))
        undo()

        assertThat(chat.messages.value.map { it.body }).containsExactly("one", "two", "three").inOrder()
    }

    @Test
    fun anUndoKeepsTheLogWithinItsCapacity() {
        emit(warning("one"), warning("two"))
        val undo = chat.clearWithUndo()
        emit(warning("three"), warning("four"))

        undo()

        assertThat(chat.messages.value.map { it.body }).containsExactly("two", "three", "four").inOrder()
    }

    /** The cleared messages belong to a session that is over; they do not come back into the next. */
    @Test
    fun anUndoAfterTheLogStartedOverDoesNothing() {
        emit(NomlaEvent.LogMessage(NomlaEvent.Level.WARNING, "one"))
        val undo = chat.clearWithUndo()

        state.value = SessionState.Disconnected()
        idleMainLooper()
        undo()

        assertThat(chat.messages.value).isEmpty()
    }
}
