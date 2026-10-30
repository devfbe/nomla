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

package koeln.becker.nomla.mumble.diagnostics

import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.audio.capture.NanoClock
import koeln.becker.nomla.mumble.net.SocketBuffers
import koeln.becker.nomla.mumble.net.TransportStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // The virtual-time scheduler.
class DiagnosticsSamplerTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler) + Job())
    private val hub = DiagnosticsHub(clock = NanoClock { scheduler.currentTime * NANOS_PER_MS })

    private var usingUdp = true
    private var calls = 0

    private fun stats() = TransportStats(
        usingUdp = usingUdp,
        tcpPingMs = 30f, tcpPingVarMs = 4f, udpPingMs = 20f, udpPingVarMs = 2f,
        good = 100, late = 1, lost = 2, resync = 0,
        remoteGood = 90, remoteLate = 3, remoteLost = 4, remoteResync = 1,
        socket = SocketBuffers(sendBytes = 1000, receiveBytes = 2000),
    )

    private val sampler = DiagnosticsSampler(hub, scope, transport = {
        calls++
        stats()
    })

    @After
    fun tearDown() {
        sampler.stop()
        scope.cancel()
    }

    private fun TelemetryTable.column(name: String): List<Float> {
        val c = columns.indexOf(name)
        check(c >= 0) { "no column $name" }
        return List(rows) { values[it * columns.size + c] }
    }

    @Test(timeout = TIMEOUT_MS)
    fun `samples every 100 ms only while active, and logs a transport change`() {
        sampler.start()
        scheduler.advanceTimeBy(1000)
        assertThat(calls).isEqualTo(0)

        hub.arm(5)
        scheduler.advanceTimeBy(1000)
        assertThat(calls).isEqualTo(10)
        assertThat(hub.snapshot().network?.usingUdp).isTrue()

        usingUdp = false
        scheduler.advanceTimeBy(100)
        val recording = hub.disarm()!!
        scheduler.advanceTimeBy(1000)

        assertThat(calls).isEqualTo(11)
        assertThat(hub.snapshot().network).isNull()
        val network = recording.network.freeze(recording.t0Nanos)
        assertThat(network.rows).isEqualTo(11)
        assertThat(network.columns).containsExactly(
            "using_udp", "tcp_ping_ms", "tcp_ping_var_ms", "udp_ping_ms", "udp_ping_var_ms", "good", "late", "lost",
            "resync", "remote_good", "remote_late", "remote_lost", "remote_resync", "so_sndbuf", "so_rcvbuf",
        ).inOrder()
        assertThat(network.column("using_udp")).isEqualTo(List(10) { 1f } + 0f)
        assertThat(network.column("so_rcvbuf").toSet()).containsExactly(2000f)
        val times = network.timesMs.toList()
        assertThat(times.zipWithNext { a, b -> b - a }.toSet()).containsExactly(100.0)
        val changes = recording.events.freeze(recording.t0Nanos).filter { it.name == "transport_changed" }
        assertThat(changes.map { it.value }).containsExactly("tcp")
    }

    @Test(timeout = TIMEOUT_MS)
    fun `an inactive hub schedules nothing`() {
        sampler.start()
        // A ticking loop would never let advanceUntilIdle return, or would move virtual time on.
        scheduler.advanceUntilIdle()
        assertThat(scheduler.currentTime).isEqualTo(0L)
        assertThat(calls).isEqualTo(0)

        val handle = hub.meter()
        scheduler.advanceTimeBy(350)
        assertThat(calls).isEqualTo(4)

        handle.close()
        val time = scheduler.currentTime
        scheduler.advanceUntilIdle()
        assertThat(scheduler.currentTime).isEqualTo(time)
        assertThat(calls).isEqualTo(4)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val TIMEOUT_MS = 10_000L
    }
}
