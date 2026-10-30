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
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import koeln.becker.nomla.R
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.mumble.diagnostics.StageReading

/**
 * The Console view of the send chain, like a mixing desk: one vertical strip per [SendStage], each
 * with an LED (green active, grey inactive, amber when it clipped within the last second), its RMS
 * as a bar and its held peak as a line. An inactive stage stays in place, greyed out.
 */
class StageStripsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val strips: Map<SendStage, Strip> = SendStage.entries.associateWith { Strip(context, it) }
    private val clips = ClipWatch()
    private var onStageClick: ((SendStage) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        for ((stage, strip) in strips) {
            addView(strip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            strip.setOnClickListener { onStageClick?.invoke(stage) }
        }
    }

    /** Called with the stage of a tapped strip. */
    fun setOnStageClick(listener: ((SendStage) -> Unit)?) {
        onStageClick = listener
    }

    /** Shows [stages]; a stage without a reading shows as inactive. */
    fun show(stages: List<StageReading>) {
        val byStage = stages.associateBy { it.stage }
        val clipping = clips.update(stages, SystemClock.uptimeMillis())
        for ((stage, strip) in strips) strip.show(byStage[stage], stage in clipping)
    }

    /** The strip of [stage]. */
    internal fun strip(stage: SendStage): View = strips.getValue(stage)

    /** One stage's strip: LED, meter and name. */
    private class Strip(context: Context, stage: SendStage) : LinearLayout(context) {
        private val led = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        private val meter = MeterView(context)
        private val name = context.getString(LiveFormat.stageName(stage))

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            val ledSize = dp(LED_DP)
            addView(led, LayoutParams(ledSize, ledSize).apply { bottomMargin = dp(GAP_DP) })
            addView(meter, LayoutParams(dp(METER_WIDTH_DP), dp(METER_HEIGHT_DP)))
            addView(
                TextView(context).apply {
                    text = name
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelSmall)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(GAP_DP) },
            )
            show(null, clipping = false)
        }

        fun show(reading: StageReading?, clipping: Boolean) {
            val active = reading?.active == true
            alpha = if (active) 1f else INACTIVE_ALPHA
            val color = when {
                !active -> R.color.talk_state_idle
                clipping -> R.color.level_meter_hold_level
                else -> R.color.level_meter_voice_level
            }
            (led.background as GradientDrawable).setColor(ContextCompat.getColor(context, color))
            meter.show(reading?.rmsDbfs, reading?.peakHoldDbfs)
            val rms = context.getString(R.string.debug_metric_rms)
            val unit = context.getString(R.string.debug_metric_dbfs)
            val level = LiveFormat.withUnit(LiveFormat.number(reading?.rmsDbfs), unit)
            val off = context.getString(R.string.debug_stage_off)
            contentDescription = if (active) "$name, $rms $level" else "$name, $off"
        }
    }

    /** A vertical level bar: the RMS filled from the bottom, the held peak as a line. */
    private class MeterView(context: Context) : View(context) {
        private val track = Paint().apply { color = ContextCompat.getColor(context, R.color.level_meter_silent_zone) }
        private val bar = Paint().apply { color = ContextCompat.getColor(context, R.color.level_meter_idle_level) }
        private val hold = Paint().apply {
            color = ContextCompat.getColor(context, R.color.level_meter_hold_level)
            strokeWidth = context.dp(HOLD_LINE_DP).toFloat()
        }
        private var rmsFraction = 0f
        private var holdFraction = 0f

        fun show(rmsDbfs: Float?, holdDbfs: Float?) {
            rmsFraction = fraction(rmsDbfs)
            holdFraction = fraction(holdDbfs)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, track)
            canvas.drawRect(0f, h * (1 - rmsFraction), w, h, bar)
            if (holdFraction > 0f) {
                val y = h * (1 - holdFraction)
                canvas.drawLine(0f, y, w, y, hold)
            }
        }
    }

    companion object {
        /** The bottom of the meters; quieter shows as empty. */
        const val FLOOR_DBFS = -60f

        private const val INACTIVE_ALPHA = 0.38f
        private const val LED_DP = 10f
        private const val GAP_DP = 4f
        private const val METER_WIDTH_DP = 14f
        private const val METER_HEIGHT_DP = 120f
        private const val HOLD_LINE_DP = 2f

        /** A level in dBFS as the filled part of a meter, 0 at [FLOOR_DBFS] and below, 1 at 0 dBFS. */
        fun fraction(dbfs: Float?): Float =
            if (dbfs == null || dbfs.isNaN()) 0f else ((dbfs - FLOOR_DBFS) / -FLOOR_DBFS).coerceIn(0f, 1f)

        private fun View.dp(value: Float): Int = context.dp(value)

        private fun Context.dp(value: Float): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
    }
}

/**
 * Which stages clipped within the last [WINDOW_MS]: a stage clips when its clipped-sample count
 * grows between two readings. Main thread.
 */
internal class ClipWatch {
    private val counts = HashMap<SendStage, Long>()
    private val lastClipAt = HashMap<SendStage, Long>()

    /** Takes [stages] read at [nowMs] and answers the stages that clipped within the window. */
    fun update(stages: List<StageReading>, nowMs: Long): Set<SendStage> {
        for (reading in stages) {
            val previous = counts.put(reading.stage, reading.clippedSamples)
            if (previous != null && reading.clippedSamples > previous) lastClipAt[reading.stage] = nowMs
        }
        return lastClipAt.filterValues { nowMs - it < WINDOW_MS }.keys
    }

    private companion object {
        const val WINDOW_MS = 1000L
    }
}
