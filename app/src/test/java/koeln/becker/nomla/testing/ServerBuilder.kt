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

import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import koeln.becker.nomla.mumble.INomlaSession
import koeln.becker.nomla.mumble.model.ChannelState
import koeln.becker.nomla.mumble.model.ServerState
import koeln.becker.nomla.mumble.model.TalkState
import koeln.becker.nomla.mumble.model.UserState
import java.util.WeakHashMap

/** Describes a server's channels and users for [serverState]. */
class ServerBuilder {
    val channels = mutableListOf<ChannelState>()
    val users = mutableListOf<UserState>()

    /** A channel below [parent]; the root (id 0) has none. */
    fun channel(id: Int, name: String? = "channel-$id", parent: Int? = if (id == 0) null else 0) =
        ChannelState(id, name, parent).also { channels += it }

    fun channel(state: ChannelState) = state.also { channels += it }

    fun user(session: Int, name: String? = "user-$session", channel: Int = 0) =
        UserState(session, name, channel).also { users += it }

    fun user(state: UserState) = state.also { users += it }
}

/** A snapshot of the channels and users [build] describes, with [self] as the own session. */
fun serverState(self: Int? = null, permissions: Int = 0, build: ServerBuilder.() -> Unit): ServerState =
    ServerBuilder().apply(build).let { ServerState.of(it.channels, it.users, self, permissions) }

/** A server with only [self], as the own session, in its root channel. */
fun selfServerState(self: UserState): ServerState = serverState(self = self.session) {
    channel(0, "Root")
    user(self)
}

private val modelFlows = WeakHashMap<INomlaSession, MutableStateFlow<ServerState?>>()
private val talkFlows = WeakHashMap<INomlaSession, MutableStateFlow<Map<Int, TalkState>>>()

/** The mocked session's model, stubbed as a flow the test moves; [state] now. */
fun INomlaSession.stubModel(state: ServerState?): MutableStateFlow<ServerState?> =
    modelFlows.getOrPut(this) { MutableStateFlow(state).also { every { this@stubModel.model } returns it } }
        .also { it.value = state }

/** The mocked session's talk states, stubbed as a flow the test moves. */
fun INomlaSession.stubTalkStates(states: Map<Int, TalkState> = emptyMap()): MutableStateFlow<Map<Int, TalkState>> =
    talkFlows.getOrPut(this) { MutableStateFlow(states).also { every { talkStates } returns it } }
        .also { it.value = states }

/** Stubs the session's model and talk states as empty flows, unless a test stubbed them already. */
internal fun INomlaSession.stubSnapshotsIfAbsent() {
    if (modelFlows[this] == null) stubModel(null)
    if (talkFlows[this] == null) stubTalkStates()
}
