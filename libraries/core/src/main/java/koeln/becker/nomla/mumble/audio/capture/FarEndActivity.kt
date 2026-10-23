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

package koeln.becker.nomla.mumble.audio.capture

import koeln.becker.nomla.mumble.audio.PcmLevel

/**
 * Whether the far end (the remote users as played on the speaker) is talking, or was within the
 * last `holdMs` ([HOLD_MS]): long enough to cover the echo path's delay (about 300 ms from handing a
 * frame over to capturing its echo, `RoomAcousticsDeviceTest`) and the room's tail. A frame counts
 * as talking at or above `thresholdDbfs` ([THRESHOLD_DBFS]).
 *
 * The playback thread reports every far-end frame through [onFarEndFrame]; the capture thread asks
 * [isActive]. The shared state is one volatile timestamp and a volatile flag (set once, before the
 * first timestamp counts), so neither thread locks or allocates. A replay faster than real time
 * passes a [clock] that advances with its frames.
 */
internal class FarEndActivity(
    private val clock: NanoClock = NanoClock(System::nanoTime),
    thresholdDbfs: Float = THRESHOLD_DBFS,
    holdMs: Long = HOLD_MS,
) {
    private val holdNanos = holdMs * NANOS_PER_MS

    /** Mean square of a 16-bit frame at the threshold. */
    private val thresholdPower = PcmLevel.meanSquareAt(thresholdDbfs)

    /** When a far-end frame last reached the threshold; meaningless until [heard]. */
    @Volatile
    private var lastVoicedNanos: Long = 0L

    /** Written after [lastVoicedNanos], so a reader that sees it true sees a real timestamp. */
    @Volatile
    private var heard: Boolean = false

    /** Playback thread: one far-end frame about to be played. */
    fun onFarEndFrame(frame: ShortArray) {
        if (frame.isEmpty()) return
        if (PcmLevel.meanSquare(frame) >= thresholdPower) {
            lastVoicedNanos = clock.nanoTime()
            heard = true
        }
    }

    /** Capture thread: a far-end frame reached the threshold within the hold. */
    fun isActive(): Boolean = heard && clock.nanoTime() - lastVoicedNanos < holdNanos

    companion object {
        /**
         * Far-end frames at or above this level count as talking. The mix carries only what remote
         * users transmit (silence otherwise), and their speech is some 20-30 dB above it.
         */
        const val THRESHOLD_DBFS = -50f

        const val HOLD_MS = 800L

        private const val NANOS_PER_MS = 1_000_000L
    }
}
