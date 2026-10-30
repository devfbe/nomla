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

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import koeln.becker.nomla.R
import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.mumble.diagnostics.TelemetryTable

/** One row of the Recording page's track view. */
sealed interface TrackRow {
    /** An audio track and its waveform, min and max per column (see [TrackRows.peaks]). */
    class Audio(val track: CapturedTrack, val peaks: FloatArray) : TrackRow

    /** A telemetry lane under the audio: start and end in ms, pairwise; a moment has start == end. */
    class Lane(@field:StringRes val label: Int, val spans: LongArray) : TrackRow
}

/**
 * The rows of a finished recording, computed off the main thread: the send tracks in signal order,
 * then the receive tracks (talkers, then the mix), then the telemetry lanes. Lanes read the
 * capture's tables by their CSV names, as the ZIP's README documents them.
 */
object TrackRows {
    /** A waveform's resolution; the view stretches it to its width. */
    const val WAVEFORM_COLUMNS = 512

    /** A received talker's packets this far apart, without a terminator between, are a gap. */
    const val PACKET_GAP_MS = 60.0

    const val MIX_TRACK_ID = "7_mix"
    const val SPEAKER_PREFIX = "speaker_"
    private const val FRAME_MS = 10.0
    private const val RECEIVED = 1f
    private const val PLAYBACK_LATER = 2

    /** Every row of [capture]. Blocking: run it on a worker. */
    fun build(capture: DiagnosticCapture): List<TrackRow> =
        ordered(capture.tracks).map { TrackRow.Audio(it, peaks(it.samples, WAVEFORM_COLUMNS)) } + lanes(capture)

    /** Send tracks by id (signal order), then talkers, then the mix. */
    fun ordered(tracks: List<CapturedTrack>): List<CapturedTrack> = tracks.sortedWith(
        compareBy<CapturedTrack> { if (it.sendSide) 0 else if (it.id == MIX_TRACK_ID) PLAYBACK_LATER else 1 }
            .thenBy { it.id },
    )

    /** Min and max of each of [columns] equal slices of [samples], pairwise, from -1 to 1. */
    fun peaks(samples: ShortArray, columns: Int): FloatArray {
        val result = FloatArray(2 * columns)
        if (samples.isEmpty()) return result
        for (column in 0 until columns) {
            val from = (samples.size.toLong() * column / columns).toInt()
            val end = (samples.size.toLong() * (column + 1) / columns).toInt()
            val to = maxOf(end, from + 1).coerceAtMost(samples.size)
            var min = Short.MAX_VALUE.toInt()
            var max = Short.MIN_VALUE.toInt()
            for (i in from until to) {
                min = minOf(min, samples[i].toInt())
                max = maxOf(max, samples[i].toInt())
            }
            result[2 * column] = min / FULL_SCALE
            result[2 * column + 1] = max / FULL_SCALE
        }
        return result
    }

    /** The gate, underruns, packet gaps and transport changes, each empty without its table. */
    fun lanes(capture: DiagnosticCapture): List<TrackRow.Lane> {
        val tables = capture.tables.associateBy { it.name }
        return listOf(
            TrackRow.Lane(R.string.debug_lane_gate, tables["frames"]?.let(::gateSpans) ?: LongArray(0)),
            TrackRow.Lane(R.string.debug_lane_underruns, tables["playback"]?.let(::underruns) ?: LongArray(0)),
            TrackRow.Lane(R.string.debug_lane_packet_gaps, tables["packets"]?.let(::packetGaps) ?: LongArray(0)),
            TrackRow.Lane(R.string.debug_lane_transport, tables["network"]?.let(::transportChanges) ?: LongArray(0)),
        )
    }

    /** The runs of capture frames with the gate open. */
    private fun gateSpans(table: TelemetryTable): LongArray {
        val open = table.column("vad_open") ?: return LongArray(0)
        val spans = ArrayList<Long>()
        var start = -1.0
        for (row in 0..table.rows) {
            val isOpen = row < table.rows && open(row) > HALF
            if (isOpen && start < 0) start = table.time(row)
            if (!isOpen && start >= 0) {
                spans += start.toLong()
                spans += (table.time(row - 1) + FRAME_MS).toLong()
                start = -1.0
            }
        }
        return spans.toLongArray()
    }

    /** The moments the playback track's underrun count grew. */
    private fun underruns(table: TelemetryTable): LongArray =
        table.column("underruns")?.let { count -> table.momentsWhere(count) { before, now -> now > before } }
            ?: LongArray(0)

    /** The moments voice went from UDP to TCP or back. */
    private fun transportChanges(table: TelemetryTable): LongArray =
        table.column("using_udp")?.let { udp -> table.momentsWhere(udp) { before, now -> now != before } }
            ?: LongArray(0)

    /**
     * The gaps of more than [PACKET_GAP_MS] between two received packets of one talker, unless the
     * first ended a talk spurt. Only talkers with a slot (`speaker_1..4`) count, so packets without a
     * known talker never merge into one.
     */
    private fun packetGaps(table: TelemetryTable): LongArray {
        val direction = table.column("direction")
        val speaker = table.column("speaker")
        val terminator = table.column("terminator")
        if (direction == null || speaker == null || terminator == null) return LongArray(0)
        val last = HashMap<Float, Pair<Double, Boolean>>()
        val spans = ArrayList<Long>()
        for (row in 0 until table.rows) {
            val talker = speaker(row)
            if (direction(row) != RECEIVED || !isSlot(talker)) continue
            val time = table.time(row)
            val before = last.put(talker, time to (terminator(row) > HALF))
            if (before != null && !before.second && time - before.first > PACKET_GAP_MS) {
                spans += before.first.toLong()
                spans += time.toLong()
            }
        }
        return spans.toLongArray()
    }

    /** Whether [talker] is one of the slots 1 to [SPEAKER_SLOTS]; NaN and 0 are not. */
    private fun isSlot(talker: Float): Boolean =
        talker.toInt() in 1..SPEAKER_SLOTS && talker == talker.toInt().toFloat()

    private const val HALF = 0.5f
    private const val FULL_SCALE = 32768f

    /** The talkers a recording gives a track (B4). */
    private const val SPEAKER_SLOTS = 4
}

private fun TelemetryTable.time(row: Int): Double = timesMs[row]

/** The column [name] as a function of the row, or null when the table has no such column. */
private fun TelemetryTable.column(name: String): ((Int) -> Float)? {
    val index = columns.indexOf(name)
    if (index < 0) return null
    val width = columns.size
    return { row -> values[row * width + index] }
}

/**
 * The rows where [changed] holds against the last value before (NaN skipped), as spans of one
 * moment: start and end both the row's time.
 */
private fun TelemetryTable.momentsWhere(value: (Int) -> Float, changed: (Float, Float) -> Boolean): LongArray {
    val moments = ArrayList<Long>()
    var before = Float.NaN
    for (row in 0 until rows) {
        val now = value(row)
        if (now.isNaN()) continue
        if (!before.isNaN() && changed(before, now)) {
            moments += time(row).toLong()
            moments += time(row).toLong()
        }
        before = now
    }
    return moments.toLongArray()
}

/**
 * The track view of a finished recording: per audio track its name, play/stop, three optional
 * ratings and its waveform; per lane its label and its marks. Every row shares the time axis and
 * the playhead. Tapping a waveform plays the track from there; play/stop plays it from the start.
 */
class TrackListAdapter(
    private val onPlay: (track: CapturedTrack, fromFraction: Float?) -> Unit,
    private val onRate: (trackId: String, rating: Rating?) -> Unit,
) : RecyclerView.Adapter<TrackListAdapter.Holder>() {
    private var rows: List<TrackRow> = emptyList()
    private var durationMs = 1L
    private var ratings: Map<String, Rating> = emptyMap()
    private var playingId: String? = null
    private var playhead: Float? = null
    private val attached = HashSet<Holder>()

    /** Shows [rows] of a recording of [durationMs], rated as [ratings]. */
    @SuppressLint("NotifyDataSetChanged") // A new recording replaces every row.
    fun submit(rows: List<TrackRow>, durationMs: Long, ratings: Map<String, Rating>) {
        this.rows = rows
        this.durationMs = durationMs
        this.ratings = ratings
        playingId = null
        playhead = null
        notifyDataSetChanged()
    }

    /** The ratings as the review now holds them; the toggles already show the user's change. */
    fun setRatings(ratings: Map<String, Rating>) {
        this.ratings = ratings
    }

    /** Shows [trackId] as the one playing, or none. */
    fun setPlaying(trackId: String?) {
        if (trackId == playingId) return
        val before = playingId
        playingId = trackId
        rows.forEachIndexed { position, row ->
            val id = (row as? TrackRow.Audio)?.track?.id
            if (id != null && (id == before || id == trackId)) notifyItemChanged(position)
        }
    }

    /** Moves the shared playhead to [fraction] of the recording, or hides it. */
    fun setPlayhead(fraction: Float?) {
        playhead = fraction
        attached.forEach { it.timeline.playhead = fraction }
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_diagnostics_track, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    override fun onViewAttachedToWindow(holder: Holder) {
        attached += holder
        holder.timeline.playhead = playhead
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        attached -= holder
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val play: ImageButton = view.findViewById(R.id.track_play)
        private val name: TextView = view.findViewById(R.id.track_name)
        private val rating: MaterialButtonToggleGroup = view.findViewById(R.id.track_rating)
        val timeline: TimelineView = view.findViewById(R.id.track_timeline)
        private var track: CapturedTrack? = null
        private var binding = false

        init {
            for ((id, face) in FACES) view.findViewById<MaterialButton>(id).text = face
            rating.addOnButtonCheckedListener { group, id, checked ->
                val trackId = track?.id
                if (binding || trackId == null) return@addOnButtonCheckedListener
                if (checked) {
                    onRate(trackId, RATINGS.getValue(id))
                } else if (group.checkedButtonId == View.NO_ID) {
                    onRate(trackId, null)
                }
            }
            play.setOnClickListener { track?.let { onPlay(it, null) } }
            timeline.setOnClickListener { track?.let { onPlay(it, timeline.tapFraction()) } }
        }

        fun bind(row: TrackRow) {
            val audio = row as? TrackRow.Audio
            track = audio?.track
            play.isVisible = audio != null
            rating.isVisible = audio != null
            timeline.isClickable = audio != null
            timeline.layoutParams = timeline.layoutParams.apply {
                height = ((if (audio != null) WAVEFORM_DP else LANE_DP) * timeline.resources.displayMetrics.density)
                    .toInt()
            }
            timeline.playhead = playhead
            // TalkBack: an audio row has one play target, its button (the waveform is a second way
            // to tap for sighted users); a lane is read once, as its label with what it counts.
            timeline.importantForAccessibility =
                if (audio != null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
            name.importantForAccessibility =
                if (audio != null) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            when (row) {
                is TrackRow.Audio -> bindAudio(row)
                is TrackRow.Lane -> {
                    name.setText(row.label)
                    timeline.contentDescription = laneSummary(name.text, row.spans.size / 2)
                    timeline.showSpans(row.spans, durationMs)
                }
            }
        }

        private fun bindAudio(row: TrackRow.Audio) {
            val id = row.track.id
            name.text = trackName(name.context, id)
            timeline.contentDescription = null
            timeline.showWaveform(row.peaks)
            val playing = id == playingId
            play.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
            play.contentDescription =
                play.context.getString(if (playing) R.string.debug_track_stop else R.string.debug_track_play)
            binding = true
            val rated = ratings[id]
            if (rated == null) rating.clearChecked() else rating.check(BUTTONS.getValue(rated))
            binding = false
        }
    }

    companion object {
        private const val WAVEFORM_DP = 40f
        private const val LANE_DP = 12f

        /** A lane for TalkBack: its label and how many runs or moments it marks, "Underruns: 3". */
        fun laneSummary(label: CharSequence, count: Int): String =
            String.format(java.util.Locale.getDefault(), "%s: %d", label, count)

        private val BUTTONS = mapOf(
            Rating.GOOD to R.id.track_rating_good,
            Rating.MEDIUM to R.id.track_rating_medium,
            Rating.BAD to R.id.track_rating_bad,
        )
        private val RATINGS = BUTTONS.entries.associate { (rating, id) -> id to rating }

        /** The smileys, which need no translation; each button's description names its rating. */
        private val FACES = listOf(
            R.id.track_rating_good to "🙂",
            R.id.track_rating_medium to "😐",
            R.id.track_rating_bad to "🙁",
        )

        /** A track's name: its stage, a talker, or the mix; a split part gets its number. */
        fun trackName(context: Context, id: String): String {
            val stage = SendStage.entries.firstOrNull { id == it.trackId || id.startsWith(it.trackId + "_") }
            val speaker = id.removePrefix(TrackRows.SPEAKER_PREFIX).toIntOrNull()
                ?.takeIf { id.startsWith(TrackRows.SPEAKER_PREFIX) }
            return when {
                id == TrackRows.MIX_TRACK_ID -> context.getString(R.string.debug_track_mix)
                speaker != null -> context.getString(R.string.debug_speaker, speaker)
                stage == null -> id
                id == stage.trackId -> context.getString(LiveFormat.stageName(stage))
                else -> context.getString(LiveFormat.stageName(stage)) + " (" + id.removePrefix(stage.trackId + "_") +
                    ")"
            }
        }
    }
}
