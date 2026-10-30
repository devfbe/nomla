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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class WavWriterTest {
    @Test
    fun `header fields and little-endian samples`() {
        val samples = shortArrayOf(0x0102, -2, 32767, -32768)
        val out = ByteArrayOutputStream()

        WavWriter.write(out, samples, 16_000)

        val bytes = out.toByteArray()
        val n = samples.size
        assertThat(bytes.size).isEqualTo(44 + 2 * n)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(ascii(bytes, 0)).isEqualTo("RIFF")
        assertThat(b.getInt(4)).isEqualTo(36 + 2 * n)
        assertThat(ascii(bytes, 8)).isEqualTo("WAVE")
        assertThat(ascii(bytes, 12)).isEqualTo("fmt ")
        assertThat(b.getInt(16)).isEqualTo(16)
        assertThat(b.getShort(20).toInt()).isEqualTo(1)
        assertThat(b.getShort(22).toInt()).isEqualTo(1)
        assertThat(b.getInt(24)).isEqualTo(16_000)
        assertThat(b.getInt(28)).isEqualTo(2 * 16_000)
        assertThat(b.getShort(32).toInt()).isEqualTo(2)
        assertThat(b.getShort(34).toInt()).isEqualTo(16)
        assertThat(ascii(bytes, 36)).isEqualTo("data")
        assertThat(b.getInt(40)).isEqualTo(2 * n)
        assertThat(bytes[44]).isEqualTo(0x02.toByte())
        assertThat(bytes[45]).isEqualTo(0x01.toByte())
        for (i in 0 until n) assertThat(b.getShort(44 + 2 * i)).isEqualTo(samples[i])
    }

    @Test
    fun `a long track is written whole`() {
        val samples = ShortArray(100_000) { (it % 1000).toShort() }
        val out = ByteArrayOutputStream()

        WavWriter.write(out, samples, 48_000)

        val b = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(out.size()).isEqualTo(44 + 200_000)
        assertThat(b.getShort(44 + 2 * 99_999)).isEqualTo(samples[99_999])
    }

    private fun ascii(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
}
