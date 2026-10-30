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

package koeln.becker.nomla.mumble.net

/** The UDP socket's `SO_SNDBUF` and `SO_RCVBUF`, in bytes, as the kernel reports them. */
internal data class SocketBuffers(val sendBytes: Int, val receiveBytes: Int)

/**
 * The transport of a connection at one moment, for the debug diagnostics: where voice goes, the
 * TCP and UDP ping round trips (mean and population variance in ms and ms², as the client's `Ping`
 * reports them; NaN before the first ping), the crypt counters of both sides (`good`, `late`,
 * `lost`, `resync`, the remote ones as the server last reported them) and the UDP socket's buffers.
 *
 * Read from outside the protocol context without a lock: the values may be from two neighbouring
 * pings or packets, which a 10 Hz statistic can afford.
 */
internal data class TransportStats(
    val usingUdp: Boolean,
    val tcpPingMs: Float,
    val tcpPingVarMs: Float,
    val udpPingMs: Float,
    val udpPingVarMs: Float,
    val good: Int,
    val late: Int,
    val lost: Int,
    val resync: Int,
    val remoteGood: Int,
    val remoteLate: Int,
    val remoteLost: Int,
    val remoteResync: Int,
    val socket: SocketBuffers?,
)
