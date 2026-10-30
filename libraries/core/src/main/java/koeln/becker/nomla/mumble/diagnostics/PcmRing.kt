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

/**
 * Preallocated, time-stamped 16-bit PCM of one tap for one recording: up to [capacityFrames]
 * frames of at most [maxFrameSamples] samples each, stored back to back. It fills once and never
 * wraps: a frame that does not fit is dropped and counted in [overflowFrames], and a write never
 * blocks, throws or allocates.
 *
 * Exactly one thread writes. [freeze] is called only after the producer has stopped (disarm);
 * the `@Volatile` frame count publishes everything written before it.
 */
internal class PcmRing(private val capacityFrames: Int, private val maxFrameSamples: Int) {
    private val nanos = LongArray(capacityFrames)
    private val rates = IntArray(capacityFrames)
    private val lengths = IntArray(capacityFrames)
    private val samples = ShortArray(capacityFrames * maxFrameSamples)

    /** Samples written so far; only the producer touches it. */
    private var sampleCursor = 0

    @Volatile
    private var frames = 0

    /** Frames dropped because the ring was full or the frame too long. */
    @Volatile
    var overflowFrames: Int = 0
        private set

    /** Copies the first [length] samples of [source], taken at [sampleRate] at time [nanos]. */
    fun write(source: ShortArray, length: Int, sampleRate: Int, nanos: Long): Boolean {
        if (!hasRoom(length)) return false
        source.copyInto(samples, sampleCursor, 0, length)
        return commit(length, sampleRate, nanos)
    }

    /** A frame of [length] zero samples, such as a send-side frame while sending is not allowed. */
    fun writeSilence(length: Int, sampleRate: Int, nanos: Long): Boolean {
        if (!hasRoom(length)) return false
        samples.fill(0, sampleCursor, sampleCursor + length)
        return commit(length, sampleRate, nanos)
    }

    /** A 48 kHz float frame at full scale 1.0, scaled by 32767 and clamped to 16 bits; NaN reads 0. */
    fun writeFloat(source: FloatArray, length: Int, nanos: Long): Boolean {
        if (!hasRoom(length)) return false
        for (i in 0 until length) {
            // Math.round saturates and maps NaN to 0, where roundToInt would throw.
            val scaled = Math.round(source[i] * SHORT_MAX)
            samples[sampleCursor + i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), SHORT_MAX.toInt()).toShort()
        }
        return commit(length, FLOAT_RATE, nanos)
    }

    /** A copy of everything written, in order. Only after the producer has stopped. */
    fun freeze(): FrozenPcm {
        val n = frames
        return FrozenPcm(
            nanos = nanos.copyOf(n),
            rates = rates.copyOf(n),
            lengths = lengths.copyOf(n),
            samples = samples.copyOf(lengths.sumFirst(n)),
            frames = n,
        )
    }

    private fun hasRoom(length: Int): Boolean {
        if (frames < capacityFrames && length in 0..maxFrameSamples) return true
        overflowFrames++
        return false
    }

    private fun commit(length: Int, sampleRate: Int, time: Long): Boolean {
        val i = frames
        nanos[i] = time
        rates[i] = sampleRate
        lengths[i] = length
        sampleCursor += length
        frames = i + 1
        return true
    }

    private companion object {
        const val SHORT_MAX = 32767f
        const val FLOAT_RATE = 48_000

        fun IntArray.sumFirst(count: Int): Int {
            var sum = 0
            for (i in 0 until count) sum += this[i]
            return sum
        }
    }
}

/**
 * The frozen content of a [PcmRing]: [frames] frames. Per frame, [nanos] holds when it was taken
 * (`System.nanoTime()`), [rates] its sample rate in Hz and [lengths] its sample count. The samples
 * of all frames are stored back to back in [samples], in frame order.
 */
internal class FrozenPcm(
    val nanos: LongArray,
    val rates: IntArray,
    val lengths: IntArray,
    val samples: ShortArray,
    val frames: Int,
)
