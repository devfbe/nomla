/*
 * Copyright (C) 2015-2017 Andrew Comminos
 * Copyright (C) 2020 Daniel Lublin
 * Copyright (C) 2022 Bartkk0
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

package koeln.becker.nomla.mumble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import koeln.becker.nomla.mumble.model.Latency
import koeln.becker.nomla.mumble.model.Server
import koeln.becker.nomla.mumble.model.ServerInfo
import koeln.becker.nomla.mumble.model.ServerState
import koeln.becker.nomla.mumble.model.TalkState
import koeln.becker.nomla.mumble.session.NomlaEvent
import koeln.becker.nomla.mumble.session.SessionConfig
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.session.inMainThreadSlices

/**
 * One session with a server: a connection and its automatic reconnects. What the server holds is
 * [model], what happens [events]; [actions] and [audio] act on it. Nothing here throws while
 * disconnected. Main thread, except for collecting the flows.
 */
public interface INomlaSession : AutoCloseable {
    /** Whether the session is connected, and why it is not. */
    public val state: StateFlow<SessionState>

    /**
     * What happens in the session, emitted from any thread. There is no replay: collect before
     * acting on a result. Collect on the main thread through [inMainThreadSlices] to keep the UI
     * responsive during bursts.
     */
    public val events: SharedFlow<NomlaEvent>

    /**
     * The server's channels and users, one immutable snapshot per burst of changes; null without a
     * connection. Collect anywhere.
     */
    public val model: StateFlow<ServerState?>

    /** The talk state of every user who is not silent, by session; updated on the main thread. */
    public val talkStates: StateFlow<Map<Int, TalkState>>

    /** What the synchronized connection knows about its server; null outside of one. */
    public val serverInfo: ServerInfo?

    /** The round trips of the synchronized connection's latest pings; null outside of one. */
    public val latency: Latency?

    /** Requests to the server, voice targets and local choices about other users. */
    public val actions: SessionActions

    /** Transmitting and routing. */
    public val audio: AudioControls

    /** The configuration last passed to [configure]. */
    public val config: SessionConfig

    /** The server of this session. */
    public val targetServer: Server?

    /**
     * Replaces the configuration. Audio settings apply live, connection settings on the next
     * connection.
     * @return true if a reconnect is required for the changes to take effect.
     */
    public fun configure(config: SessionConfig): Boolean

    /** Connects to the configured server. Ignored while connecting or connected. */
    public fun connect()

    /** Ends the session; a no-op once it has ended. */
    public fun disconnect()

    /** Gives up an automatic reconnect that is waiting or in flight; the reason stays in [state]. */
    public fun cancelReconnect()

    /** Disconnects and releases the session's threads and platform callbacks. The session is unusable afterwards. */
    override fun close()
}
