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

package koeln.becker.nomla.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import koeln.becker.nomla.R

/**
 * One row of the Recording page's time axis, the whole recording across the view's width: a
 * track's waveform (min and max per column), or a telemetry lane's spans and marks. Both draw the
 * shared playhead. Every row spans the same width, so all of them line up.
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val track = Paint().apply { color = ContextCompat.getColor(context, R.color.level_meter_silent_zone) }
    private val wave = Paint().apply { color = ContextCompat.getColor(context, R.color.level_meter_voice_level) }
    private val head = Paint().apply {
        val fallback = ContextCompat.getColor(context, R.color.talk_state_idle)
        color = MaterialColors.getColor(context, android.R.attr.textColorPrimary, fallback)
    }
    private val minMarkWidth = resources.displayMetrics.density * MIN_MARK_DP

    /** Min and max per column, from -1 to 1, as [TrackRows.peaks] makes them; null for a lane. */
    private var peaks: FloatArray? = null

    /** Start and end in ms, pairwise, as [TrackRows] makes a lane's; null for a waveform. */
    private var spans: LongArray? = null
    private var durationMs = 1L

    /** Where the last touch went down, for a tap that plays from there; -1 for none. */
    private var downX = -1f

    /** The playhead as a fraction of the recording, or null for none. */
    var playhead: Float? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    fun showWaveform(peaks: FloatArray) {
        this.peaks = peaks
        spans = null
        invalidate()
    }

    fun showSpans(spans: LongArray, durationMs: Long) {
        this.spans = spans
        this.durationMs = durationMs.coerceAtLeast(1)
        peaks = null
        invalidate()
    }

    /** Where the last tap landed as a fraction of the width; 0 for a click without a touch (TalkBack). */
    fun tapFraction(): Float {
        val x = downX
        downX = -1f
        return if (x < 0 || width == 0) 0f else (x / width).coerceIn(0f, 1f)
    }

    // Only notes where a touch went down; the click itself is the View's own, through performClick.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) downX = event.x
        return super.dispatchTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, track)
        peaks?.let { drawWaveform(canvas, it, w, h) }
        spans?.let { drawSpans(canvas, it, w, h) }
        playhead?.let { canvas.drawRect(it * w - minMarkWidth / 2, 0f, it * w + minMarkWidth / 2, h, head) }
    }

    private fun drawWaveform(canvas: Canvas, peaks: FloatArray, w: Float, h: Float) {
        val columns = peaks.size / 2
        if (columns == 0) return
        val step = w / columns
        val middle = h / 2
        for (i in 0 until columns) {
            val top = middle - peaks[2 * i + 1] * middle
            val bottom = middle - peaks[2 * i] * middle
            // At least one pixel high, so silence shows as a line.
            canvas.drawRect(i * step, top, (i + 1) * step, maxOf(bottom, top + 1f), wave)
        }
    }

    private fun drawSpans(canvas: Canvas, spans: LongArray, w: Float, h: Float) {
        for (i in 0 until spans.size / 2) {
            val start = spans[2 * i] * w / durationMs
            val end = maxOf(spans[2 * i + 1] * w / durationMs, start + minMarkWidth)
            canvas.drawRect(start, 0f, end, h, wave)
        }
    }

    private companion object {
        /** A mark of one moment, and the playhead, are this wide. */
        const val MIN_MARK_DP = 2f
    }
}
