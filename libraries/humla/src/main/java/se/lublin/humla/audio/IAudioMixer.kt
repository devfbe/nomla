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

/** Mixes [IAudioMixerSource]s with buffers of type [T] into a destination buffer of type [U]. */
internal fun interface IAudioMixer<T, U> {
    fun mix(sources: List<IAudioMixerSource<T>>, buffer: U, bufferOffset: Int, bufferLength: Int)
}
