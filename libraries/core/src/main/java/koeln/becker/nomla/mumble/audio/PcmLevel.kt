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

package koeln.becker.nomla.mumble.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * The level of 16-bit PCM, the one place the app turns samples into dB. 0 dBFS is an RMS of
 * [FULL_SCALE] (32768, the magnitude of `Short.MIN_VALUE`): a full-scale square wave, as in Mumble's
 * and WebRTC's meters. Every level is floored at [SILENCE_DBFS], so digital silence stays finite.
 * Allocation-free.
 */
internal object PcmLevel {
    /** The RMS of 0 dBFS. */
    const val FULL_SCALE = 32768.0

    /** What digital silence reads, and the lowest level and power ratio there is. */
    const val SILENCE_DBFS = -120f

    private const val POWER_DB_PER_DECADE = 10.0
    private const val FULL_SCALE_SQUARED = FULL_SCALE * FULL_SCALE

    /** [SILENCE_DBFS] as a power ratio, added to every power before the logarithm. */
    private val SILENCE_POWER = 10.0.pow(SILENCE_DBFS / POWER_DB_PER_DECADE)

    /** The mean square of [length] samples of [pcm] from [offset], in 16-bit units; 0 for none. */
    fun meanSquare(pcm: ShortArray, length: Int = pcm.size, offset: Int = 0): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in offset until offset + length) sum += pcm[i].toDouble() * pcm[i]
        return sum / length
    }

    /** The level of a frame whose [meanSquare] is [meanSquare], in dBFS; at least [SILENCE_DBFS]. */
    fun dbfs(meanSquare: Double): Float = powerDb(meanSquare / FULL_SCALE_SQUARED)

    /** The level of the first [length] samples of [pcm], in dBFS; at least [SILENCE_DBFS]. */
    fun dbfs(pcm: ShortArray, length: Int = pcm.size): Float = dbfs(meanSquare(pcm, length))

    /** The [meanSquare] of a frame at [dbfs]. */
    fun meanSquareAt(dbfs: Float): Double = FULL_SCALE_SQUARED * 10.0.pow(dbfs / POWER_DB_PER_DECADE)

    /** A power ratio in dB; at least [SILENCE_DBFS], so a ratio of 0 stays finite. */
    fun powerDb(ratio: Double): Float = (POWER_DB_PER_DECADE * log10(max(ratio, 0.0) + SILENCE_POWER)).toFloat()
}
