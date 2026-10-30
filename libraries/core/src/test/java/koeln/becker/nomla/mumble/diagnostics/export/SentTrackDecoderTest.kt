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
import koeln.becker.nomla.mumble.audio.native.OpusDecoderApi
import koeln.becker.nomla.mumble.diagnostics.FrozenPackets
import org.junit.Test

class SentTrackDecoderTest {
    private val opus = RampOpus()
    private val decoder = SentTrackDecoder(opus)

    /**
     * Frames are stamped at their end, and a packet leaves with its last frame, so a packet of n
     * frames sent at s has its first frame stamped s − (n − 1) × 10 ms. That stamp is placed on
     * the grid like every other track's frames: a 20 ms packet sent at t0 + 60 ms starts at 50 ms
     * and fills 50–70 ms.
     */
    @Test
    fun `decoded packets land at their first captured frame, silence elsewhere`() {
        val track = decoder.decode(packets(T0 + 60 * NANOS_PER_MS to 2), T0, DURATION_NANOS)

        assertThat(track.id).isEqualTo("6_sent")
        assertThat(track.sampleRate).isEqualTo(RATE)
        assertThat(track.sendSide).isTrue()
        assertThat(track.samples.size).isEqualTo(DURATION_MS * SAMPLES_PER_MS)
        val start = 50 * SAMPLES_PER_MS
        val end = 70 * SAMPLES_PER_MS
        assertThat(track.samples.copyOfRange(0, start).all { it == 0.toShort() }).isTrue()
        assertThat(track.samples.copyOfRange(start, end).toList())
            .isEqualTo(List(end - start) { (it + 1).toShort() })
        assertThat(track.samples.copyOfRange(end, track.samples.size).all { it == 0.toShort() }).isTrue()
        assertThat(opus.destroyed).isEqualTo(opus.created)
    }

    @Test
    fun `packets before the start or past the end are cut, not shifted`() {
        val track = decoder.decode(
            packets(T0 to 2, T0 + DURATION_NANOS to 1, T0 + 100 * NANOS_PER_MS to 1),
            T0,
            DURATION_NANOS,
        )

        // The first packet's first frame lies 10 ms before the start: only its second frame stays.
        assertThat(track.samples.copyOfRange(0, FRAME).toList())
            .isEqualTo(List(FRAME) { (FRAME + it + 1).toShort() })
        // A one-frame packet sent at 100 ms is stamped 100 ms.
        val at = 100 * SAMPLES_PER_MS
        assertThat(track.samples.copyOfRange(at, at + FRAME).toList())
            .isEqualTo(List(FRAME) { (it + 1).toShort() })
        assertThat(track.samples.copyOfRange(FRAME, at).all { it == 0.toShort() }).isTrue()
        assertThat(track.samples.copyOfRange(at + FRAME, track.samples.size).all { it == 0.toShort() }).isTrue()
    }

    @Test
    fun `no packets give a silent track without opening a decoder`() {
        val track = decoder.decode(packets(), T0, DURATION_NANOS)

        assertThat(track.samples.size).isEqualTo(DURATION_MS * SAMPLES_PER_MS)
        assertThat(track.samples.all { it == 0.toShort() }).isTrue()
        assertThat(opus.created).isEqualTo(0)
    }

    /** One packet per (send time, frames); each payload's single byte is its frame count. */
    private fun packets(vararg sent: Pair<Long, Int>): FrozenPackets {
        val n = sent.size
        return FrozenPackets(
            nanos = LongArray(n) { sent[it].first },
            frameNumbers = LongArray(n) { it.toLong() },
            terminators = BooleanArray(n),
            offsets = IntArray(n) { it },
            lengths = IntArray(n) { 1 },
            bytes = ByteArray(n) { sent[it].second.toByte() },
            count = n,
            dropped = 0,
        )
    }

    /** Decodes a packet into as many 10 ms frames as its byte says, ramping 1, 2, 3, … (in 16-bit units). */
    private class RampOpus : OpusDecoderApi {
        var created = 0
        var destroyed = 0

        override fun create(sampleRate: Int, channels: Int, error: IntArray): Long {
            created++
            return 1L
        }

        @Suppress("LongParameterList") // The JNI signature.
        override fun decodeFloat(
            state: Long,
            data: ByteArray?,
            offset: Int,
            len: Int,
            out: FloatArray,
            frameSize: Int,
            decodeFec: Int,
        ): Int {
            val samples = requireNotNull(data)[offset] * FRAME
            for (i in 0 until samples) out[i] = (i + 1) / FULL_SCALE
            return samples
        }

        override fun destroy(state: Long) {
            destroyed++
        }

        override fun packetGetNbFrames(packet: ByteArray, len: Int): Int = error("unused")

        override fun packetGetSamplesPerFrame(packet: ByteArray, sampleRate: Int): Int = error("unused")
    }

    private companion object {
        const val RATE = 48_000
        const val SAMPLES_PER_MS = 48
        const val FRAME = 480
        const val FULL_SCALE = 32_767f
        const val NANOS_PER_MS = 1_000_000L
        const val DURATION_MS = 200
        const val DURATION_NANOS = DURATION_MS * NANOS_PER_MS
        const val T0 = 7_000_000_000L
    }
}
