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

package koeln.becker.nomla.mumble.diagnostics.export

import koeln.becker.nomla.mumble.audio.native.OpusDecoderApi
import koeln.becker.nomla.mumble.audio.native.OpusDecoderNative
import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsLimits.FRAME_NANOS
import koeln.becker.nomla.mumble.diagnostics.FrozenPackets
import koeln.becker.nomla.mumble.diagnostics.SendStage

/**
 * The `6_sent` track (B7): the Opus packets that actually left the device, decoded at 48 kHz with
 * one decoder in send order, as the peer hears them (bitrate, frames per packet and FEC included).
 *
 * Capture frames are stamped at their end, and a packet leaves right after its last frame, so a
 * packet of n frames sent at `sendNanos` has its first frame stamped `sendNanos − (n − 1) × 10 ms`
 * (G6). That stamp is placed on the recording's 10 ms grid exactly as [TrackAssembler] places every
 * other track's frames, so `6_sent` lines up with `5_gate`. Everything no packet covers is silence;
 * samples before the start or past the end are cut.
 *
 * Off the main thread; allocates.
 */
internal class SentTrackDecoder private constructor(private val opus: Lazy<OpusDecoderApi>) {
    constructor(api: OpusDecoderApi) : this(lazyOf(api))

    /** With libopus, loaded at the first export rather than with the session. */
    constructor() : this(lazy { OpusDecoderNative })

    fun decode(packets: FrozenPackets, t0Nanos: Long, durationNanos: Long): CapturedTrack {
        val samples = ShortArray((durationNanos * RATE / NANOS_PER_SECOND).toInt())
        if (packets.count > 0) decodeInto(samples, packets, t0Nanos)
        return CapturedTrack(SendStage.SENT.trackId, RATE, samples, sendSide = true)
    }

    private fun decodeInto(track: ShortArray, packets: FrozenPackets, t0Nanos: Long) {
        val api = opus.value
        val error = IntArray(1)
        val state = api.create(RATE, 1, error)
        if (state == 0L || error[0] < 0) return
        try {
            val out = FloatArray(MAX_PACKET_SAMPLES)
            for (i in 0 until packets.count) {
                val length = packets.lengths[i]
                if (length <= 0) continue
                val decoded = api.decodeFloat(state, packets.bytes, packets.offsets[i], length, out, out.size, 0)
                if (decoded > 0) place(track, out, decoded, packets.nanos[i] - t0Nanos)
            }
        } finally {
            api.destroy(state)
        }
    }

    /** Writes [decoded] samples of [out] for a packet sent [sinceNanos] after the start. */
    private fun place(track: ShortArray, out: FloatArray, decoded: Int, sinceNanos: Long) {
        val frames = maxOf(1, decoded / FRAME_SAMPLES)
        val firstFrame = sinceNanos - (frames - 1) * FRAME_NANOS
        val slot = Math.floorDiv(firstFrame + FRAME_NANOS / 2, FRAME_NANOS)
        val at = slot * FRAME_SAMPLES
        // Only the samples inside the track: before the start and past the end are cut.
        val first = maxOf(0L, -at).toInt()
        val end = minOf(decoded.toLong(), track.size - at).toInt()
        for (i in first until end) {
            track[(at + i).toInt()] = Math.round(out[i] * FULL_SCALE)
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private companion object {
        const val RATE = 48_000
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val FRAMES_PER_SECOND = 100
        const val FRAME_SAMPLES = RATE / FRAMES_PER_SECOND

        /** The longest Opus packet, 120 ms. */
        const val MAX_PACKET_SAMPLES = RATE * 120 / 1_000

        const val FULL_SCALE = 32_767f
    }
}
