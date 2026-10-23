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

package koeln.becker.nomla.mumble.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import koeln.becker.nomla.mumble.audio.PlaybackParams
import koeln.becker.nomla.mumble.model.LocalUserSettings
import koeln.becker.nomla.mumble.model.ServerState
import koeln.becker.nomla.mumble.net.NomlaConnection
import koeln.becker.nomla.mumble.protocol.ModelHandler

/**
 * The server model of a session: the [ModelHandler] of the live connection and the snapshots it
 * publishes, with the playback parameters derived from them. What this device remembers about
 * users ([localUsers]) is carried from one connection to the next.
 *
 * [attach] and [detach] run on the session's main thread; snapshots are published on the protocol
 * context; [state] may be collected and [playbackParams] read anywhere.
 */
internal class SessionModel(private var localUsers: LocalUserSettings) {

    /** The model of the live connection; dropped when it ends, so its tree can be collected. */
    @Volatile
    var handler: ModelHandler? = null
        private set

    /** Guards the check that a snapshot comes from the live [handler] together with its publication. */
    private val lock = Any()

    private val mutableState = MutableStateFlow<ServerState?>(null)

    val state: StateFlow<ServerState?> = mutableState.asStateFlow()

    val value: ServerState? get() = mutableState.value

    /** Written on the protocol context with every snapshot, read by the network and playback threads. */
    @Volatile
    var playbackParams = PlaybackParams.DEFAULT
        private set

    /** Builds the model of [connection] and makes it the live one; the caller registers it. */
    fun attach(
        connection: NomlaConnection,
        events: (NomlaEvent) -> Unit,
        requestAvatar: (Int) -> Unit,
    ): ModelHandler {
        val publisher = SnapshotPublisher(connection)
        val handler = ModelHandler(ServerState.empty(localUsers), events, publisher, requestAvatar)
        publisher.handler = handler
        this.handler = handler
        return handler
    }

    /** The connection ended: keeps what it learned about users and drops its model. */
    fun detach() {
        synchronized(lock) {
            mutableState.value?.let { localUsers = it.local }
            handler = null
            mutableState.value = null
            playbackParams = PlaybackParams.DEFAULT
        }
    }

    /** Publishes the snapshots of one connection's model, until another model replaces it. */
    private inner class SnapshotPublisher(private val connection: NomlaConnection) : ModelHandler.Publisher {
        lateinit var handler: ModelHandler

        override fun post(block: () -> Unit) = connection.post(block)

        override fun publish(state: ServerState) {
            synchronized(lock) {
                if (this@SessionModel.handler !== handler) return
                val previous = mutableState.value
                if (previous == null || previous.users !== state.users) {
                    playbackParams = PlaybackParams.of(state.users.values)
                }
                mutableState.value = state
            }
        }
    }
}
