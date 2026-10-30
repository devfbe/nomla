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

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes a mono 16-bit PCM WAV file: the canonical 44-byte RIFF/WAVE header, then the samples little-endian. */
internal object WavWriter {
    private const val HEADER_BYTES = 44
    private const val RIFF_SIZE_BEYOND_DATA = 36
    private const val FMT_CHUNK_BYTES = 16
    private const val FORMAT_PCM: Short = 1
    private const val CHANNELS: Short = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_SAMPLE: Short = 16
    private const val CHUNK_SAMPLES = 4096

    /** Writes [samples] at [sampleRate] Hz to [out], which stays open. */
    fun write(out: OutputStream, samples: ShortArray, sampleRate: Int) {
        val dataBytes = samples.size * BYTES_PER_SAMPLE
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .put(ascii("RIFF")).putInt(RIFF_SIZE_BEYOND_DATA + dataBytes).put(ascii("WAVE"))
            .put(ascii("fmt ")).putInt(FMT_CHUNK_BYTES)
            .putShort(FORMAT_PCM).putShort(CHANNELS).putInt(sampleRate)
            .putInt(sampleRate * BYTES_PER_SAMPLE).putShort(BYTES_PER_SAMPLE.toShort()).putShort(BITS_PER_SAMPLE)
            .put(ascii("data")).putInt(dataBytes)
        out.write(header.array())

        val chunk = ByteBuffer.allocate(CHUNK_SAMPLES * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        var from = 0
        while (from < samples.size) {
            val count = minOf(CHUNK_SAMPLES, samples.size - from)
            chunk.clear()
            chunk.asShortBuffer().put(samples, from, count)
            out.write(chunk.array(), 0, count * BYTES_PER_SAMPLE)
            from += count
        }
    }

    private fun ascii(tag: String): ByteArray = tag.toByteArray(Charsets.US_ASCII)
}
