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

package se.lublin.humla.session

/**
 * Lifecycle of one server session: the only view of whether it is connected.
 *
 * Disconnected -> Connecting -> Connected -> ConnectionLost -> Reconnecting -> Connected ...
 *
 * Every state except [Disconnected] keeps the foreground notification, the partial wake lock,
 * the route the user chose and the user's mute/deafen state.
 */
public sealed interface SessionState {
    /** No connection. [reason] is why the last one ended, or null if it never started or ended on request. */
    public data class Disconnected(val reason: DisconnectReason? = null) : SessionState

    /** A user-initiated connection attempt is in progress. */
    public data object Connecting : SessionState

    /** ServerSync has been received; the session is usable. */
    public data object Connected : SessionState

    /** The connection dropped; an automatic reconnect fires in [reconnectInMillis]. */
    public data class ConnectionLost(
        val reconnectInMillis: Long,
        val attempt: Int,
        val reason: DisconnectReason?,
    ) : SessionState

    /**
     * An automatic reconnect attempt is in progress. [reason] is carried forward from the
     * [ConnectionLost] state that preceded it, so cancelling here surfaces the same reason as
     * cancelling one state earlier would.
     */
    public data class Reconnecting(val reason: DisconnectReason?) : SessionState
}
