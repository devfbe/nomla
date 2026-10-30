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

import com.google.common.truth.Truth.assertThat
import koeln.becker.nomla.mumble.diagnostics.FrozenPcm
import koeln.becker.nomla.mumble.diagnostics.PcmRing
import org.junit.Test

class TrackAssemblerTest {
    @Test
    fun `frames land on the 10 ms grid with silence in gaps`() {
        val ring = PcmRing(capacityFrames = 8, maxFrameSamples = 480)
        // 0 ms exactly, 10 ms plus 2 ms of jitter, 40 ms minus 3 ms of jitter.
        ring.write(ShortArray(480) { 1 }, 480, 48_000, T0)
        ring.write(ShortArray(480) { 2 }, 480, 48_000, T0 + ms(10) + ms(2))
        ring.write(ShortArray(480) { 3 }, 480, 48_000, T0 + ms(40) - ms(3))

        val tracks = TrackAssembler.assemble("2_mic", ring.freeze(), T0, ms(50), sendSide = true)

        val track = tracks.single()
        assertThat(track.id).isEqualTo("2_mic")
        assertThat(track.sampleRate).isEqualTo(48_000)
        assertThat(track.sendSide).isTrue()
        assertThat(track.samples.size).isEqualTo(2400)
        assertThat(track.samples.slice(0 until 480).toSet()).containsExactly(1.toShort())
        assertThat(track.samples.slice(480 until 960).toSet()).containsExactly(2.toShort())
        assertThat(track.samples.slice(960 until 1920).toSet()).containsExactly(0.toShort())
        assertThat(track.samples.slice(1920 until 2400).toSet()).containsExactly(3.toShort())
    }

    @Test
    fun `a rate change splits the track`() {
        val ring = PcmRing(capacityFrames = 8, maxFrameSamples = 480)
        ring.write(ShortArray(480) { 1 }, 480, 48_000, T0)
        ring.write(ShortArray(160) { 2 }, 160, 16_000, T0 + ms(10))

        val tracks = TrackAssembler.assemble("1_mic_raw", ring.freeze(), T0, ms(20), sendSide = true)

        assertThat(tracks.map { it.id }).containsExactly("1_mic_raw", "1_mic_raw_2").inOrder()
        assertThat(tracks.map { it.sampleRate }).containsExactly(48_000, 16_000).inOrder()
    }

    @Test
    fun `split tracks span the whole recording`() {
        val ring = PcmRing(capacityFrames = 16, maxFrameSamples = 480)
        for (i in 0 until 5) ring.write(ShortArray(480) { 1 }, 480, 48_000, T0 + ms(10 * i))
        for (i in 5 until 10) ring.write(ShortArray(160) { 2 }, 160, 16_000, T0 + ms(10 * i))

        val (first, second) = TrackAssembler.assemble("1_mic_raw", ring.freeze(), T0, ms(100), sendSide = true)

        assertThat(first.samples.size).isEqualTo(4800)
        assertThat(first.samples.slice(0 until 2400).toSet()).containsExactly(1.toShort())
        assertThat(first.samples.slice(2400 until 4800).toSet()).containsExactly(0.toShort())
        assertThat(second.samples.size).isEqualTo(1600)
        assertThat(second.samples.slice(0 until 800).toSet()).containsExactly(0.toShort())
        assertThat(second.samples.slice(800 until 1600).toSet()).containsExactly(2.toShort())
    }

    @Test
    fun `a return to an earlier rate is a new track`() {
        val ring = PcmRing(capacityFrames = 8, maxFrameSamples = 480)
        ring.write(ShortArray(480) { 1 }, 480, 48_000, T0)
        ring.write(ShortArray(160) { 2 }, 160, 16_000, T0 + ms(10))
        ring.write(ShortArray(480) { 3 }, 480, 48_000, T0 + ms(20))

        val tracks = TrackAssembler.assemble("x", ring.freeze(), T0, ms(30), sendSide = false)

        assertThat(tracks.map { it.id }).containsExactly("x", "x_2", "x_3").inOrder()
        assertThat(tracks.map { it.sendSide }.toSet()).containsExactly(false)
        assertThat(tracks[0].samples.slice(960 until 1440).toSet()).containsExactly(0.toShort())
        assertThat(tracks[2].samples.slice(960 until 1440).toSet()).containsExactly(3.toShort())
    }

    @Test
    fun `frames before t0 or after the duration are dropped`() {
        val ring = PcmRing(capacityFrames = 8, maxFrameSamples = 480)
        ring.write(ShortArray(160) { 9 }, 160, 16_000, T0 - ms(10))
        ring.write(ShortArray(480) { 1 }, 480, 48_000, T0)
        ring.write(ShortArray(480) { 2 }, 480, 48_000, T0 + ms(10))
        ring.write(ShortArray(160) { 9 }, 160, 16_000, T0 + ms(20))

        val tracks = TrackAssembler.assemble("m", ring.freeze(), T0, ms(20), sendSide = true)

        val track = tracks.single()
        assertThat(track.sampleRate).isEqualTo(48_000)
        assertThat(track.samples.size).isEqualTo(960)
        assertThat(track.samples.slice(0 until 480).toSet()).containsExactly(1.toShort())
        assertThat(track.samples.slice(480 until 960).toSet()).containsExactly(2.toShort())
    }

    @Test
    fun `a frame that runs past the end is cut`() {
        val ring = PcmRing(capacityFrames = 8, maxFrameSamples = 960)
        ring.write(ShortArray(960) { 4 }, 960, 48_000, T0 + ms(10))

        val track = TrackAssembler.assemble("m", ring.freeze(), T0, ms(15), sendSide = true).single()

        assertThat(track.samples.size).isEqualTo(720)
        assertThat(track.samples.slice(480 until 720).toSet()).containsExactly(4.toShort())
    }

    @Test
    fun `no frames give no tracks`() {
        val empty = FrozenPcm(LongArray(0), IntArray(0), IntArray(0), ShortArray(0), 0)

        assertThat(TrackAssembler.assemble("m", empty, T0, ms(100), sendSide = true)).isEmpty()
    }

    private fun ms(ms: Int): Long = ms * 1_000_000L

    private companion object {
        const val T0 = 7_000_000_000L
    }
}
