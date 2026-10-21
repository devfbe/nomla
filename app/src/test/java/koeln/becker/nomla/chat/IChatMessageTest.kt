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
import org.junit.Test
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.Message
import se.lublin.humla.model.UserState
import koeln.becker.nomla.testing.textMessage

class IChatMessageTest {

    @Test
    fun textMessageExposesUnderlyingMessage() {
        val msg = textMessage("<b>hi</b>", actor = 7, actorName = "alice")
        val chat = IChatMessage.TextMessage(msg)
        assertThat(chat.body).isEqualTo("<b>hi</b>")
        assertThat(chat.receivedTime).isEqualTo(msg.receivedTime)
        assertThat(chat.message).isSameInstanceAs(msg)
    }

    @Test
    fun infoMessageCarriesTypeAndBody() {
        val before = System.currentTimeMillis()
        val chat = IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, "careful")
        assertThat(chat.type).isEqualTo(IChatMessage.InfoMessage.Type.WARNING)
        assertThat(chat.body).isEqualTo("careful")
        assertThat(chat.receivedTime).isAtLeast(before)
    }

    @Test
    fun visitorDispatchesOnConcreteType() {
        val seen = mutableListOf<String>()
        val visitor = object : IChatMessage.Visitor {
            override fun visit(message: IChatMessage.TextMessage) { seen += "text" }
            override fun visit(message: IChatMessage.InfoMessage) { seen += "info" }
        }
        IChatMessage.TextMessage(textMessage("x")).accept(visitor)
        IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, "y").accept(visitor)
        assertThat(seen).containsExactly("text", "info").inOrder()
    }

    @Test
    fun aMessageNeverHandsOutANullTargetList() {
        val full = Message(
            7, "alice", listOf(ChannelState(0, "Root")), listOf(ChannelState(0, "Sub")),
            listOf(UserState(3, "bob", 0)), "hi",
        )
        for (message in listOf(full, textMessage("just a body"))) {
            assertThat(message.targetChannels).isNotNull()
            assertThat(message.targetTrees).isNotNull()
            assertThat(message.targetUsers).isNotNull()
        }
    }
}
