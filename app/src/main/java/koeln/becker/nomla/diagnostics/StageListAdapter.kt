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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import koeln.becker.nomla.R
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsSnapshot
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.mumble.diagnostics.StageReading

/**
 * The List view of the send chain: one row per [SendStage] in signal order (LED, level bar and the
 * change in dB; tapped, all of the stage's metrics below it), then one row per block below the
 * stages. With [showStages] false it shows the blocks only, as the Console view does under its
 * strips. An inactive stage keeps its row, greyed out and marked off.
 */
class StageListAdapter(
    private val metrics: LiveMetrics,
    private val showStages: Boolean = true,
) : RecyclerView.Adapter<StageListAdapter.Holder>() {
    private var snapshot: DiagnosticsSnapshot? = null
    private var blocks: List<Block> = emptyList()
    private val expanded = HashSet<SendStage>()

    private val stageCount: Int get() = if (showStages) SendStage.entries.size else 0

    /** Shows [snapshot] and [blocks]; rows are rebound in place. */
    fun submit(snapshot: DiagnosticsSnapshot, blocks: List<Block>) {
        val before = itemCount
        this.snapshot = snapshot
        this.blocks = blocks
        val after = itemCount
        notifyItemRangeChanged(0, minOf(before, after))
        if (after > before) notifyItemRangeInserted(before, after - before)
        if (after < before) notifyItemRangeRemoved(after, before - after)
    }

    /** Expands the row of [stage] and answers its adapter position. */
    fun expand(stage: SendStage): Int {
        val position = stage.ordinal
        if (expanded.add(stage)) notifyItemChanged(position)
        return position
    }

    override fun getItemCount(): Int = stageCount + blocks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_diagnostics_stage, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position < stageCount) {
            holder.bindStage(SendStage.entries[position])
        } else {
            holder.bindBlock(blocks[position - stageCount])
        }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val header: View = view.findViewById(R.id.stage_header)
        private val led: View = view.findViewById(R.id.stage_led)
        private val name: TextView = view.findViewById(R.id.stage_name)
        private val bar: ProgressBar = view.findViewById(R.id.stage_bar)
        private val change: TextView = view.findViewById(R.id.stage_change)
        private val details: LinearLayout = view.findViewById(R.id.stage_metrics)

        init {
            led.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }

        fun bindStage(stage: SendStage) {
            val reading = snapshot?.stages?.firstOrNull { it.stage == stage }
            val active = reading?.active == true
            val context = itemView.context
            header.alpha = if (active) 1f else INACTIVE_ALPHA
            led.isVisible = true
            bar.isVisible = true
            change.isVisible = true
            val ledColor = if (active) R.color.level_meter_voice_level else R.color.talk_state_idle
            (led.background as GradientDrawable).setColor(ContextCompat.getColor(context, ledColor))
            name.setText(LiveFormat.stageName(stage))
            name.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
            bar.progress = (StageStripsView.fraction(reading?.rmsDbfs) * bar.max).toInt()
            change.text = changeText(reading, active)
            val open = stage in expanded
            details.isVisible = open
            if (open) bindMetrics(reading?.let { snapshot?.let { s -> metrics.stage(it, s) } }.orEmpty())
            itemView.setOnClickListener { toggle(stage) }
        }

        fun bindBlock(block: Block) {
            header.alpha = 1f
            led.isVisible = false
            bar.isVisible = false
            change.isVisible = false
            name.setText(block.title)
            name.setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleSmall)
            details.isVisible = true
            bindMetrics(block.metrics.ifEmpty { listOf(Metric("", LiveFormat.NONE)) })
            itemView.setOnClickListener(null)
            itemView.isClickable = false
        }

        private fun changeText(reading: StageReading?, active: Boolean): String {
            val context = itemView.context
            val change = LiveFormat.signed(reading?.changeDb)
            return when {
                !active -> context.getString(R.string.debug_stage_off)
                change == LiveFormat.NONE -> LiveFormat.NONE
                else -> context.getString(R.string.debug_change_db, change)
            }
        }

        /** One label/value line per metric, reusing the lines already there. */
        private fun bindMetrics(lines: List<Metric>) {
            while (details.childCount > lines.size) details.removeViewAt(details.childCount - 1)
            while (details.childCount < lines.size) details.addView(newLine())
            lines.forEachIndexed { i, metric ->
                val line = details.getChildAt(i) as LinearLayout
                (line.getChildAt(0) as TextView).text = metric.label
                (line.getChildAt(1) as TextView).text = metric.value
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

        private fun toggle(stage: SendStage) {
            if (!expanded.remove(stage)) expanded += stage
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::notifyItemChanged)
        }
    }

    private companion object {
        const val INACTIVE_ALPHA = 0.38f
    }
}
