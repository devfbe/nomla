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

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import koeln.becker.nomla.R
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsSnapshot
import koeln.becker.nomla.mumble.diagnostics.SendStage

/**
 * The List view of the send chain: one row per [SendStage] in signal order (LED, level bar and the
 * change in dB; tapped, all of the stage's metrics below it), then one row per block below the
 * stages. With [showStages] false it shows the blocks only, as the Console view does under its
 * strips. An inactive stage keeps its row, greyed out and marked off.
 *
 * A new reading rebinds the rows in place with [LEVELS]: only levels and values change, and a
 * text is set only when it differs, so a reading costs no layout pass unless a line appears.
 */
class StageListAdapter(
    private val metrics: LiveMetrics,
    private val showStages: Boolean = true,
) : RecyclerView.Adapter<StageListAdapter.Holder>() {
    private var snapshot: DiagnosticsSnapshot? = null
    private var blocks: List<Block> = emptyList()
    private val expanded = BooleanArray(SendStage.entries.size)

    private val stageCount: Int get() = if (showStages) SendStage.entries.size else 0

    /** Shows [snapshot] and [blocks], rebinding the rows in place. */
    fun submit(snapshot: DiagnosticsSnapshot, blocks: List<Block>) {
        val before = itemCount
        this.snapshot = snapshot
        this.blocks = blocks
        val after = itemCount
        notifyItemRangeChanged(0, minOf(before, after), LEVELS)
        if (after > before) notifyItemRangeInserted(before, after - before)
        if (after < before) notifyItemRangeRemoved(after, before - after)
    }

    /** Expands the row of [stage] and answers its adapter position. */
    fun expand(stage: SendStage): Int {
        val position = stage.ordinal
        if (!expanded[position]) {
            expanded[position] = true
            notifyItemChanged(position)
        }
        return position
    }

    override fun getItemCount(): Int = stageCount + blocks.size

    override fun getItemViewType(position: Int): Int = if (position < stageCount) TYPE_STAGE else TYPE_BLOCK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_diagnostics_stage, parent, false)
        return if (viewType == TYPE_STAGE) StageHolder(view) else BlockHolder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position, full = true)

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) =
        holder.bind(position, full = payloads.isEmpty() || payloads.any { it != LEVELS })

    /** A row; `bind` sets everything when `full`, else only what a new reading changes. */
    abstract class Holder(view: View) : RecyclerView.ViewHolder(view) {
        protected val header: View = view.findViewById(R.id.stage_header)
        protected val led: View = view.findViewById(R.id.stage_led)
        protected val name: TextView = view.findViewById(R.id.stage_name)
        protected val bar: ProgressBar = view.findViewById(R.id.stage_bar)
        protected val change: TextView = view.findViewById(R.id.stage_change)
        protected val details: LinearLayout = view.findViewById(R.id.stage_metrics)

        abstract fun bind(position: Int, full: Boolean)

        /** One label/value line per metric, reusing the lines already there. */
        protected fun bindMetrics(lines: List<Metric>) {
            while (details.childCount > lines.size) details.removeViewAt(details.childCount - 1)
            while (details.childCount < lines.size) details.addView(newLine())
            lines.forEachIndexed { i, metric ->
                val line = details.getChildAt(i) as LinearLayout
                (line.getChildAt(0) as TextView).setIfChanged(metric.label)
                (line.getChildAt(1) as TextView).setIfChanged(metric.value)
            }
        }

        private fun newLine(): LinearLayout = LinearLayout(itemView.context).apply {
            orientation = LinearLayout.HORIZONTAL
            val small = MaterialR.style.TextAppearance_Material3_BodySmall
            addView(
                TextView(context).apply { setTextAppearance(small) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(TextView(context).apply { setTextAppearance(small) })
        }
    }

    private inner class StageHolder(view: View) : Holder(view) {
        private val ledShape = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        private val context = view.context
        private val off = context.getString(R.string.debug_stage_off)
        private var stage = SendStage.MIC_RAW

        init {
            led.background = ledShape
            name.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
            itemView.setOnClickListener { toggle() }
        }

        override fun bind(position: Int, full: Boolean) {
            stage = SendStage.entries[position]
            val reading = snapshot?.stages?.firstOrNull { it.stage == stage }
            val active = reading?.active == true
            val open = expanded[stage.ordinal]
            if (full) {
                name.setText(LiveFormat.stageName(stage))
                details.isVisible = open
                describeExpansion(open)
            }
            header.alpha = if (active) 1f else INACTIVE_ALPHA
            val ledColor = if (active) R.color.level_meter_voice_level else R.color.talk_state_idle
            ledShape.setColor(ContextCompat.getColor(context, ledColor))
            bar.progress = (StageStripsView.fraction(reading?.rmsDbfs) * bar.max).toInt()
            val delta = LiveFormat.signed(reading?.changeDb)
            change.setIfChanged(
                when {
                    !active -> off
                    delta == LiveFormat.NONE -> LiveFormat.NONE
                    else -> context.getString(R.string.debug_change_db, delta)
                },
            )
            val current = snapshot
            if (open && reading != null && current != null) bindMetrics(metrics.stage(reading, current))
        }

        /** Offers TalkBack the action that flips the row, which it reads as expanded or collapsed. */
        private fun describeExpansion(open: Boolean) {
            val (offered, other) = if (open) {
                AccessibilityActionCompat.ACTION_COLLAPSE to AccessibilityActionCompat.ACTION_EXPAND
            } else {
                AccessibilityActionCompat.ACTION_EXPAND to AccessibilityActionCompat.ACTION_COLLAPSE
            }
            ViewCompat.removeAccessibilityAction(itemView, other.id)
            ViewCompat.replaceAccessibilityAction(itemView, offered, null) { _, _ ->
                toggle()
                true
            }
        }

        private fun toggle() {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            expanded[position] = !expanded[position]
            notifyItemChanged(position)
        }
    }

    private inner class BlockHolder(view: View) : Holder(view) {
        init {
            led.isVisible = false
            bar.isVisible = false
            change.isVisible = false
            details.isVisible = true
            name.setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
        }

        override fun bind(position: Int, full: Boolean) {
            val block = blocks[position - stageCount]
            if (full) name.setText(block.title)
            bindMetrics(block.metrics.ifEmpty { EMPTY_BLOCK })
        }
    }

    companion object {
        /** The payload of a new reading: levels and values only. */
        const val LEVELS = "levels"

        private const val TYPE_STAGE = 0
        private const val TYPE_BLOCK = 1
        private const val INACTIVE_ALPHA = 0.38f
        private val EMPTY_BLOCK = listOf(Metric("", LiveFormat.NONE))

        private fun TextView.setIfChanged(value: CharSequence) {
            if (!TextUtils.equals(text, value)) text = value
        }
    }
}
