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
import com.google.common.truth.Truth.assertWithMessage
import koeln.becker.nomla.mumble.testutil.AllocationMeter
import org.junit.Test

class PcmRingTest {
    @Test
    fun `write past capacity drops and counts, never throws`() {
        val ring = PcmRing(capacityFrames = 3, maxFrameSamples = FRAME)
        val frame = ShortArray(FRAME) { 7 }

        val results = (0 until 5).map { ring.write(frame, FRAME, 48_000, it * FRAME_NANOS) }

        assertThat(results).containsExactly(true, true, true, false, false).inOrder()
        assertThat(ring.overflowFrames).isEqualTo(2)
        assertThat(ring.freeze().frames).isEqualTo(3)
    }

    @Test
    fun `a frame longer than the slot is dropped and counted`() {
        val ring = PcmRing(capacityFrames = 3, maxFrameSamples = 4)

        assertThat(ring.write(ShortArray(5), 5, 48_000, 0L)).isFalse()
        assertThat(ring.overflowFrames).isEqualTo(1)
        assertThat(ring.freeze().frames).isEqualTo(0)
    }

    @Test
    fun `freeze returns frames in order with their rates and times`() {
        val ring = PcmRing(capacityFrames = 4, maxFrameSamples = FRAME)
        ring.write(shortArrayOf(1, 2, 3, 99), 3, 48_000, 100L)
        ring.writeSilence(2, 16_000, 200L)
        ring.write(shortArrayOf(-4, 5), 2, 16_000, 300L)

        val frozen = ring.freeze()

        assertThat(frozen.frames).isEqualTo(3)
        assertThat(frozen.nanos.toList()).containsExactly(100L, 200L, 300L).inOrder()
        assertThat(frozen.rates.toList()).containsExactly(48_000, 16_000, 16_000).inOrder()
        assertThat(frozen.lengths.toList()).containsExactly(3, 2, 2).inOrder()
        assertThat(frozen.samples.toList()).isEqualTo(listOf<Short>(1, 2, 3, 0, 0, -4, 5))
    }

    @Test
    fun `writeFloat clamps and scales`() {
        val ring = PcmRing(capacityFrames = 1, maxFrameSamples = 4)
        ring.writeFloat(floatArrayOf(1.5f, -0.5f, 0f, -2f), 4, 10L)

        val frozen = ring.freeze()

        assertThat(frozen.rates.single()).isEqualTo(48_000)
        assertThat(frozen.samples[0].toInt()).isEqualTo(32767)
        assertThat(frozen.samples[1].toInt()).isWithin(1).of(-16384)
        assertThat(frozen.samples[2].toInt()).isEqualTo(0)
        assertThat(frozen.samples[3].toInt()).isAtMost(-32767)
    }

    @Test
    fun `writes allocate nothing`() {
        AllocationMeter.checkInstrument(FRAME)
        val ring = PcmRing(capacityFrames = WRITES * 3, maxFrameSamples = FRAME)
        val shorts = ShortArray(FRAME) { 3 }
        val floats = FloatArray(FRAME) { 0.25f }
        // Warm the three paths so class loading and JIT do not count.
        ring.write(shorts, FRAME, 48_000, 0L)
        ring.writeSilence(FRAME, 48_000, 0L)
        ring.writeFloat(floats, FRAME, 0L)

        val before = AllocationMeter.allocatedBytes()
        for (i in 0 until WRITES) {
            when (i % 3) {
                0 -> ring.write(shorts, FRAME, 48_000, i.toLong())
                1 -> ring.writeSilence(FRAME, 48_000, i.toLong())
                else -> ring.writeFloat(floats, FRAME, i.toLong())
            }
        }
        val delta = AllocationMeter.allocatedBytes() - before

        assertWithMessage("bytes allocated by $WRITES ring writes").that(delta).isLessThan(1024L)
    }

    private companion object {
        const val FRAME = 480
        const val FRAME_NANOS = 10_000_000L
        const val WRITES = 1000
    }
}
