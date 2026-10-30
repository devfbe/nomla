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
import android.view.View
import androidx.core.content.ContextCompat
import koeln.becker.nomla.R

/**
 * The gate over the last seconds as a lane, oldest frame on the left: green where it was open,
 * a faint track where it was closed.
 */
class GateHistoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val open = Paint().apply { color = ContextCompat.getColor(context, R.color.level_meter_voice_level) }
    private val closed = Paint().apply { color = ContextCompat.getColor(context, R.color.level_meter_silent_zone) }
    private var history = BooleanArray(0)

    init {
        contentDescription = context.getString(R.string.debug_lane_gate)
    }

    /** Shows [history], one entry per capture frame, oldest first. */
    fun show(history: BooleanArray) {
        if (history.contentEquals(this.history)) return
        this.history = history.copyOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, closed)
        val frames = history.size
        if (frames == 0) return
        val step = w / frames
        var start = -1
        // One rectangle per run of open frames.
        for (i in 0..frames) {
            val isOpen = i < frames && history[i]
            if (isOpen && start < 0) start = i
            if (!isOpen && start >= 0) {
                canvas.drawRect(start * step, 0f, i * step, h, open)
                start = -1
            }
        }
    }
}
