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

/** The fixed sizes and intervals of the debug diagnostics. */
internal object DiagnosticsLimits {
    const val SHORT_SECONDS = 5

    const val DEFAULT_SECONDS = 10

    const val LONG_SECONDS = 20

    const val MAX_SECONDS = 30

    /** The recording lengths offered, in seconds; the last is the maximum. */
    val DURATIONS_SECONDS: List<Int> = listOf(SHORT_SECONDS, DEFAULT_SECONDS, LONG_SECONDS, MAX_SECONDS)

    /** Talkers that get their own track, in order of first packet; later ones are only counted. */
    const val SPEAKER_SLOTS = 4

    /** One capture or playback frame: the grid every track is placed on. */
    const val FRAME_NANOS = 10_000_000L

    /** Spare ring frames beyond the recording length, for jitter and a late disarm. */
    const val RING_MARGIN_FRAMES = 50

    /** The highest sample rate a raw microphone frame can have, which sizes its ring slots. */
    const val MAX_RAW_RATE = 48_000

    /** How often the network sampler reads transport statistics. */
    const val NETWORK_SAMPLE_MS = 100L

    /** Capture frames between two APM statistics polls (1 s, webrtc's own aggregation window). */
    const val STATS_EVERY_FRAMES = 100

    /** Frames a meter's peak hold keeps a peak (1.5 s) before it follows the signal again. */
    const val PEAK_HOLD_FRAMES = 150

    /** What digital silence reads, and the lowest level any meter reports. */
    const val SILENCE_DBFS = -100f

    /** Capture frames of gate state the live page draws (3 s). */
    const val GATE_HISTORY_FRAMES = 300

    /**
     * Opus payload bytes a recording keeps per 10 ms of capture (about 200 kbps, far above any
     * bitrate Nomla sends); a packet beyond that budget is dropped and counted.
     */
    const val MAX_PACKET_BYTES_PER_FRAME = 256
}
