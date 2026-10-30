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

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.MessageLite
import koeln.becker.nomla.mumble.exception.NomlaException
import koeln.becker.nomla.mumble.protobuf.Mumble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class ProtocolSessionTransportStatsTest {
    private var nanos = 0L

    private val link = object : ProtocolSession.Link {
        override val scope = CoroutineScope(Dispatchers.Unconfined)
        override val cryptState = CryptState()
        override val endpoint = Endpoint("example.invalid", 64738)
        override fun sendTcp(message: MessageLite, type: NomlaTcpMessageType) = Unit
        override fun sendUdp(data: ByteArray, length: Int) = Unit
        override fun startUdp() = Unit
        override fun onSynchronized() = Unit
        override fun onWarning(warning: ConnectionWarning) = Unit
        override fun fail(error: NomlaException) = Unit
    }

    /** Tunnelled, so the TCP pings are not judged for UDP health. */
    private val session = ProtocolSession(link, tunnelVoice = true, nanoClock = { nanos }).apply { start() }

    private fun tcpPingAfter(roundTripMs: Long) {
        val sentMicros = nanos / NANOS_PER_MICRO
        nanos += roundTripMs * NANOS_PER_MS
        session.onMessage(
            Mumble.Ping.newBuilder()
                .setTimestamp(sentMicros)
                .setGood(11).setLate(12).setLost(13).setResync(14)
                .build(),
        )
    }

    private fun udpPingAfter(roundTripMs: Long) {
        val ping = UdpPing.encode(UdpProtocol.LEGACY, nanos / NANOS_PER_MICRO)
        nanos += roundTripMs * NANOS_PER_MS
        session.onUdpPing(ping)
    }

    @Test
    fun `ping averages and crypt counters are reported`() {
        val before = session.transportStats(null)
        assertThat(before.tcpPingMs.isNaN()).isTrue()
        assertThat(before.udpPingMs.isNaN()).isTrue()

        tcpPingAfter(30)
        tcpPingAfter(50)
        udpPingAfter(20)
        link.cryptState.good = 5
        link.cryptState.late = 6
        link.cryptState.lost = 7
        link.cryptState.resync = 8
        val socket = SocketBuffers(sendBytes = 1000, receiveBytes = 2000)

        val stats = session.transportStats(socket)

        assertThat(stats.usingUdp).isFalse()
        assertThat(stats.tcpPingMs).isWithin(0.001f).of(40f)
        assertThat(stats.tcpPingVarMs).isWithin(0.001f).of(100f)
        assertThat(stats.udpPingMs).isWithin(0.001f).of(20f)
        assertThat(stats.udpPingVarMs).isWithin(0.001f).of(0f)
        assertThat(listOf(stats.good, stats.late, stats.lost, stats.resync)).containsExactly(5, 6, 7, 8).inOrder()
        assertThat(listOf(stats.remoteGood, stats.remoteLate, stats.remoteLost, stats.remoteResync))
            .containsExactly(11, 12, 13, 14).inOrder()
        assertThat(stats.socket).isEqualTo(socket)
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
