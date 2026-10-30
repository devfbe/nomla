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

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * RMS, peak, peak hold and clipped samples of one tap, frame by frame. 0 dBFS is an RMS of 32768
 * for 16-bit samples (a full-scale square wave, as `PcmLevel`) and of 1.0 for float samples. Every
 * level is floored at [DiagnosticsLimits.SILENCE_DBFS].
 *
 * One thread measures; any thread reads the `@Volatile` results. Allocation-free.
 */
internal class LevelMeter {
    @Volatile
    var rmsDbfs: Float = DiagnosticsLimits.SILENCE_DBFS
        private set

    @Volatile
    var peakDbfs: Float = DiagnosticsLimits.SILENCE_DBFS
        private set

    /** The highest [peakDbfs] of the last [DiagnosticsLimits.PEAK_HOLD_FRAMES] frames, roughly. */
    @Volatile
    var peakHoldDbfs: Float = DiagnosticsLimits.SILENCE_DBFS
        private set

    /** Samples at or beyond full scale since the last [reset]. */
    @Volatile
    var clippedSamples: Long = 0L
        private set

    /** Frames since [peakHoldDbfs] was last set. */
    private var holdAge = 0

    /** Measures the first [length] samples of [samples]; a sample of magnitude 32767 or more clips. */
    fun measure(samples: ShortArray, length: Int) {
        var sum = 0.0
        var peak = 0
        var clipped = 0
        for (i in 0 until length) {
            val s = samples[i].toInt()
            val a = abs(s)
            sum += s.toDouble() * s
            if (a > peak) peak = a
            if (a >= SHORT_CLIP) clipped++
        }
        publish(
            if (length > 0) sum / length / SHORT_FULL_SCALE_SQUARED else 0.0,
            peak / SHORT_FULL_SCALE,
            clipped,
        )
    }

    /** Measures the first [length] samples of [samples] at full scale 1.0; a magnitude of 1 or more clips. */
    fun measure(samples: FloatArray, length: Int) {
        var sum = 0.0
        var peak = 0f
        var clipped = 0
        for (i in 0 until length) {
            val s = samples[i]
            val a = abs(s)
            sum += s.toDouble() * s
            if (a > peak) peak = a
            if (a >= 1f) clipped++
        }
        publish(if (length > 0) sum / length else 0.0, peak.toDouble(), clipped)
    }

    /** Back to silence, with no clipped samples. Not concurrently with [measure]. */
    fun reset() {
        rmsDbfs = DiagnosticsLimits.SILENCE_DBFS
        peakDbfs = DiagnosticsLimits.SILENCE_DBFS
        peakHoldDbfs = DiagnosticsLimits.SILENCE_DBFS
        clippedSamples = 0L
        holdAge = 0
    }

    /**
     * The hold follows a new peak at once; otherwise it keeps the old one for
     * [DiagnosticsLimits.PEAK_HOLD_FRAMES] more frames and then follows the signal.
     */
    private fun publish(meanSquare: Double, peakRatio: Double, clipped: Int) {
        val peak = dbfs(peakRatio * peakRatio)
        rmsDbfs = dbfs(meanSquare)
        peakDbfs = peak
        holdAge++
        if (peak >= peakHoldDbfs || holdAge > DiagnosticsLimits.PEAK_HOLD_FRAMES) {
            peakHoldDbfs = peak
            holdAge = 0
        }
        if (clipped > 0) clippedSamples += clipped
    }

    private companion object {
        const val SHORT_CLIP = 32767
        const val SHORT_FULL_SCALE = 32768.0
        const val SHORT_FULL_SCALE_SQUARED = SHORT_FULL_SCALE * SHORT_FULL_SCALE
        const val POWER_DB_PER_DECADE = 10.0

        /** A power ratio in dB, floored at [DiagnosticsLimits.SILENCE_DBFS]. */
        fun dbfs(power: Double): Float {
            if (power <= 0.0) return DiagnosticsLimits.SILENCE_DBFS
            return max((POWER_DB_PER_DECADE * log10(power)).toFloat(), DiagnosticsLimits.SILENCE_DBFS)
        }
    }
}
