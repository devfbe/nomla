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

package koeln.becker.nomla.testing

import koeln.becker.nomla.mumble.model.Message
import koeln.becker.nomla.chat.ChatMessage

/** A text message without targets. */
fun textMessage(body: String, actor: Int = -1, actorName: String? = null, receivedTime: Long = 0L) =
    Message(actor, actorName, emptyList(), emptyList(), emptyList(), body, receivedTime)

fun info(body: String) = ChatMessage.InfoMessage(ChatMessage.InfoMessage.Type.INFO, body)
