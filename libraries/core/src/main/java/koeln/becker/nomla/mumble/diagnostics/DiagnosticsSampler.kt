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

import koeln.becker.nomla.mumble.net.TransportStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Samples the transport every [DiagnosticsLimits.NETWORK_SAMPLE_MS] while the hub is active, and
 * not at all otherwise (P9): [start] only collects [DiagnosticsHub.active], so while the hub is
 * inactive no coroutine is scheduled and nothing ticks. Each sample is published for the live page
 * ([DiagnosticsHub.network]) and, while a recording runs, written as one `network` row; a switch of
 * the voice transport between UDP and TCP is logged as a `transport_changed` event.
 *
 * Runs in [scope], whose dispatcher (the main thread in a session) is the `network` ring's only
 * writer. [start] and [stop] on that same thread.
 *
 * @param transport the connection's statistics now, null while it has none (not synchronized);
 *   must not block.
 * @param extra further values for the live page, read with every sample.
 */
internal class DiagnosticsSampler(
    private val hub: DiagnosticsHub,
    private val scope: CoroutineScope,
    private val transport: () -> TransportStats?,
    private val extra: () -> Map<String, Float> = { emptyMap() },
) {
    private var job: Job? = null

    /**
     * The transport of the last sample of this run, null before its first: a change logs
     * `transport_changed`. Each run starts afresh, so a change while inactive is no event.
     */
    private var lastUsingUdp: Boolean? = null

    /** Samples whenever the hub is active, until [stop]. Calling it again does nothing. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            hub.active.collectLatest { active ->
                if (active) sampleWhileActive()
            }
        }
    }

    /** Stops sampling for good and clears the live reading. */
    fun stop() {
        job?.cancel()
        job = null
        hub.network = null
    }

    private suspend fun sampleWhileActive() {
        lastUsingUdp = null
        try {
            while (hub.isActive) {
                sample()
                delay(DiagnosticsLimits.NETWORK_SAMPLE_MS)
            }
        } finally {
            hub.network = null
        }
    }

    private fun sample() {
        val stats = transport()
        if (stats == null) {
            hub.network = null
            return
        }
        val previous = lastUsingUdp
        lastUsingUdp = stats.usingUdp
        if (previous != null && previous != stats.usingUdp) {
            hub.event(TRANSPORT_CHANGED, if (stats.usingUdp) "udp" else "tcp")
        }
        hub.network = stats.toReading(extra())
        val recording = hub.armed ?: return
        recording.network.write(hub.now(), stats.toRow())
    }

    private fun TransportStats.toReading(extra: Map<String, Float>) = NetworkReading(
        usingUdp = usingUdp,
        tcpPingMs = tcpPingMs,
        tcpPingVarMs = tcpPingVarMs,
        udpPingMs = udpPingMs,
        udpPingVarMs = udpPingVarMs,
        good = good,
        late = late,
        lost = lost,
        resync = resync,
        remoteGood = remoteGood,
        remoteLate = remoteLate,
        remoteLost = remoteLost,
        remoteResync = remoteResync,
        sendBufferBytes = socket?.sendBytes,
        receiveBufferBytes = socket?.receiveBytes,
        extra = extra,
    )

    /** One `network` row, in [NetworkColumns.NAMES] order; on the sampler's thread, so it may allocate. */
    private fun TransportStats.toRow(): FloatArray = floatArrayOf(
        if (usingUdp) 1f else 0f, tcpPingMs, tcpPingVarMs, udpPingMs, udpPingVarMs,
        good.toFloat(), late.toFloat(), lost.toFloat(), resync.toFloat(),
        remoteGood.toFloat(), remoteLate.toFloat(), remoteLost.toFloat(), remoteResync.toFloat(),
        socket?.sendBytes?.toFloat() ?: Float.NaN, socket?.receiveBytes?.toFloat() ?: Float.NaN,
    )

    companion object {
        const val TRANSPORT_CHANGED = "transport_changed"
    }
}
