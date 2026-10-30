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

import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsLimits.FRAME_NANOS
import koeln.becker.nomla.mumble.diagnostics.FrozenPcm

/**
 * Turns a [FrozenPcm] into tracks on the recording's 10 ms grid (B6): a frame taken at `nanos`
 * starts at grid slot `round((nanos - t0) / FRAME_NANOS)`, i.e. at sample `slot * rate / 100`, and
 * everything no frame covers is silence. So capture jitter does not stretch a track, and tracks
 * of different taps line up.
 *
 * Each run of frames at one rate becomes its own track (`id`, `id_2`, `id_3`, …), since a WAV has
 * a single rate. Every track spans the whole recording, `durationNanos` at its own rate, and is
 * silent outside its run, so split tracks align without an offset too (P5).
 */
internal object TrackAssembler {
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val SLOTS_PER_SECOND = NANOS_PER_SECOND / FRAME_NANOS

    /**
     * The tracks of [frozen] for a recording that started at [t0Nanos] and lasted [durationNanos].
     * Frames before the start or at/after the end are dropped, and a frame running past the end
     * is cut. No frame in the recording gives no track.
     */
    fun assemble(
        id: String,
        frozen: FrozenPcm,
        t0Nanos: Long,
        durationNanos: Long,
        sendSide: Boolean,
    ): List<CapturedTrack> {
        val tracks = ArrayList<CapturedTrack>()
        var current: CapturedTrack? = null
        var sampleStart = 0
        for (i in 0 until frozen.frames) {
            val start = sampleStart
            val length = frozen.lengths[i]
            sampleStart += length
            val since = frozen.nanos[i] - t0Nanos
            if (since < 0 || since >= durationNanos) continue
            val rate = frozen.rates[i]
            val track = current?.takeIf { it.sampleRate == rate }
                ?: CapturedTrack(
                    id = if (tracks.isEmpty()) id else "${id}_${tracks.size + 1}",
                    sampleRate = rate,
                    samples = ShortArray((durationNanos * rate / NANOS_PER_SECOND).toInt()),
                    sendSide = sendSide,
                ).also {
                    tracks += it
                    current = it
                }
            val slot = (since + FRAME_NANOS / 2) / FRAME_NANOS
            val at = (slot * rate / SLOTS_PER_SECOND).toInt()
            val copied = minOf(length, track.samples.size - at)
            if (copied > 0) frozen.samples.copyInto(track.samples, at, start, start + copied)
        }
        return tracks
    }
}
