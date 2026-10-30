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
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

class LevelMeterTest {
    @Test
    fun `full-scale square reads 0 dBFS and counts clipped samples`() {
        val meter = LevelMeter()
        meter.measure(ShortArray(FRAME) { 32767 }, FRAME)

        assertThat(meter.rmsDbfs).isWithin(0.01f).of(0f)
        assertThat(meter.peakDbfs).isWithin(0.01f).of(0f)
        assertThat(meter.clippedSamples).isEqualTo(FRAME.toLong())
    }

    @Test
    fun `silence reads -100`() {
        val meter = LevelMeter()
        meter.measure(ShortArray(FRAME), FRAME)

        assertThat(meter.rmsDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
        assertThat(meter.peakDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
        assertThat(meter.peakHoldDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
        assertThat(meter.clippedSamples).isEqualTo(0L)
    }

    @Test
    fun `-20 dBFS sine reads -23 dB rms and -20 dB peak`() {
        val meter = LevelMeter()
        // 1 kHz at 48 kHz: 10 whole periods, and a sample lands on every crest.
        val sine = ShortArray(FRAME) { (3276.8 * sin(2 * PI * 1000 * it / 48_000)).toInt().toShort() }
        meter.measure(sine, FRAME)

        assertThat(meter.rmsDbfs).isWithin(0.1f).of(-23.01f)
        assertThat(meter.peakDbfs).isWithin(0.1f).of(-20f)
        assertThat(meter.clippedSamples).isEqualTo(0L)
    }

    @Test
    fun `peak hold keeps the peak for 150 frames then follows`() {
        val meter = LevelMeter()
        val loud = ShortArray(FRAME) { 16384 }
        val quiet = ShortArray(FRAME) { 1638 }

        meter.measure(loud, FRAME)
        val held = meter.peakHoldDbfs
        assertThat(held).isWithin(0.1f).of(-6.02f)
        repeat(DiagnosticsLimits.PEAK_HOLD_FRAMES) {
            meter.measure(quiet, FRAME)
            assertThat(meter.peakHoldDbfs).isEqualTo(held)
        }
        assertThat(meter.peakDbfs).isWithin(0.1f).of(-26.02f)

        meter.measure(quiet, FRAME)
        assertThat(meter.peakHoldDbfs).isWithin(0.1f).of(-26.02f)
    }

    @Test
    fun `float input at 0_1 reads -20 dBFS`() {
        val meter = LevelMeter()
        meter.measure(FloatArray(FRAME) { if (it % 2 == 0) 0.1f else -0.1f }, FRAME)

        assertThat(meter.rmsDbfs).isWithin(0.01f).of(-20f)
        assertThat(meter.peakDbfs).isWithin(0.01f).of(-20f)
        assertThat(meter.clippedSamples).isEqualTo(0L)
    }

    @Test
    fun `float clipping counts samples at full scale`() {
        val meter = LevelMeter()
        meter.measure(floatArrayOf(1f, -1.2f, 0.99f, 0f), 4)

        assertThat(meter.clippedSamples).isEqualTo(2L)
    }

    @Test
    fun `only the first length samples count`() {
        val meter = LevelMeter()
        val frame = ShortArray(FRAME) { 32767 }
        meter.measure(frame, 0)

        assertThat(meter.rmsDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
        assertThat(meter.clippedSamples).isEqualTo(0L)
    }

    @Test
    fun `reset returns to silence`() {
        val meter = LevelMeter()
        meter.measure(ShortArray(FRAME) { 32767 }, FRAME)
        meter.reset()

        assertThat(meter.rmsDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
        assertThat(meter.peakDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
        assertThat(meter.peakHoldDbfs).isEqualTo(DiagnosticsLimits.SILENCE_DBFS)
        assertThat(meter.clippedSamples).isEqualTo(0L)
    }

    private companion object {
        const val FRAME = 480
    }
}
