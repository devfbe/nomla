/*
 * Copyright (C) 2015 Andrew Comminos
 * Copyright (C) 2020 Daniel Lublin
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

package se.lublin.humla.audio

/** Sums float PCM sources and converts to 16-bit PCM, clipping naively to [-1, 1]. */
internal class BasicClippingShortMixer : IAudioMixer<FloatArray, ShortArray> {
    override fun mix(
        sources: List<IAudioMixerSource<FloatArray>>,
        buffer: ShortArray,
        bufferOffset: Int,
        bufferLength: Int,
    ) {
        // Indexed, not iterated: an iterator per sample would allocate on the playback thread.
        for (i in 0 until bufferLength) {
            var mix = 0f
            for (s in sources.indices) mix += sources[s].samples[i]
            buffer[i + bufferOffset] = (mix.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
