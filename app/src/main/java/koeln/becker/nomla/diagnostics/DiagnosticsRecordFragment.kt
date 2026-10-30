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

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.app.AppContainer
import koeln.becker.nomla.mumble.diagnostics.AbortReason
import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.mumble.diagnostics.RecordingRefusal
import koeln.becker.nomla.mumble.diagnostics.RecordingStart
import koeln.becker.nomla.mumble.diagnostics.RecordingState
import koeln.becker.nomla.mumble.diagnostics.TrackPlayer
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.session.SessionManager
import koeln.becker.nomla.util.appViewModels
import koeln.becker.nomla.util.whileViewStarted
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * The debug Recording page: pick a duration, confirm the consent notice (unless the user turned it
 * off), watch the progress or cancel, then review the finished recording's tracks under one time
 * axis: play one, rate it, write a note for the whole recording, share it as a ZIP or discard it.
 *
 * A finished recording stays on the page whatever the connection does (B12); only "Record" needs a
 * connected session. Playback uses its own sink, never the session: on the session's playback
 * stream while connected (B11), on the music stream otherwise, so it is not stuck on the earpiece.
 * The export lives in [RecordExportViewModel], so a rotation or leaving the page loses nothing.
 */
@Suppress("TooManyFunctions") // One page: its lifecycle, what it shows, and one action per button.
class DiagnosticsRecordFragment : Fragment() {
    private val review by viewModels<RecordReviewViewModel>()
    private val exports by appViewModels { RecordExportViewModel(it, AppContainer.of(it).parts.ioDispatcher) }
    private val settings get() = Settings.getInstance(requireContext())
    private val parts get() = AppContainer.of(requireContext()).parts
    private val sessions get() = SessionManager.get(requireContext())

    private var views: Views? = null
    private var playback: Playback? = null

    /** What the page last showed. */
    private var page = PageState(connected = false, recording = RecordingState.Idle())

    /** Why the last tap on Record started nothing; cleared when the recording state changes. */
    @StringRes
    private var refusal: Int? = null

    /** The capture whose rows the track view shows, or is computing. */
    private var shown: DiagnosticCapture? = null
    private var rowsJob: Job? = null

    /** The page's views, valid between onViewCreated and onDestroyView. */
    private class Views(root: View, val adapter: TrackListAdapter) {
        val duration: MaterialButtonToggleGroup = root.findViewById(R.id.record_duration)
        val start: MaterialButton = root.findViewById(R.id.record_start)
        val progressGroup: View = root.findViewById(R.id.record_progress_group)
        val progressText: TextView = root.findViewById(R.id.record_progress_text)
        val progress: LinearProgressIndicator = root.findViewById(R.id.record_progress)
        val status: TextView = root.findViewById(R.id.record_status)
        val empty: View = root.findViewById(R.id.record_empty)
        val tracks: RecyclerView = root.findViewById(R.id.record_tracks)
        val note: TextView = root.findViewById(R.id.record_note)
        val share: View = root.findViewById(R.id.record_share)

        /** What a finished recording shows. */
        val review: List<View> = listOf<View>(
            root.findViewById<View>(R.id.record_playback_hint),
            tracks,
            root.findViewById<View>(R.id.record_note_layout),
            root.findViewById<View>(R.id.record_actions),
        )
    }

    /** The connection and the recording, as the page shows them. */
    private data class PageState(val connected: Boolean, val recording: RecordingState)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_diagnostics_record, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = TrackListAdapter(
            onPlay = { track, from -> playback?.toggle(track, from) },
            onRate = { id, rating ->
                review.rate(id, rating)
                views?.adapter?.setRatings(review.review.value.ratings)
            },
        )
        val views = Views(view, adapter).also { this.views = it }
        views.tracks.layoutManager = LinearLayoutManager(requireContext())
        views.tracks.adapter = adapter
        views.tracks.itemAnimator = null
        for ((seconds, id) in DURATIONS) {
            view.findViewById<MaterialButton>(id).text = getString(R.string.debug_record_seconds, seconds)
        }
        if (savedInstanceState == null) views.duration.check(DURATIONS.getValue(DEFAULT_SECONDS))
        views.start.setOnClickListener { onRecordTapped() }
        view.findViewById<View>(R.id.record_cancel).setOnClickListener {
            sessions.session.value?.diagnostics?.cancelRecording()
        }
        views.share.setOnClickListener { share() }
        view.findViewById<View>(R.id.record_discard).setOnClickListener { discard() }
        views.note.doAfterTextChanged { if (shown != null) review.setNote(it?.toString().orEmpty()) }
        playback = Playback(adapter)
        val consent = RecordingConsentDialog.REQUEST_KEY
        childFragmentManager.setFragmentResultListener(consent, viewLifecycleOwner) { _, result ->
            if (RecordingConsentDialog.start(result)) startRecording()
        }
        whileViewStarted {
            launch { exports.state.collect(::showExport) }
            pageStates().collectLatest { state ->
                show(state)
                val recording = state.recording
                if (recording is RecordingState.Recording) {
                    while (true) {
                        delay(PROGRESS_TICK)
                        showProgress(recording)
                    }
                }
            }
        }
    }

    override fun onStop() {
        // Playback is for the page in view: it ends when the page is left.
        playback?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        playback?.stop()
        playback = null
        rowsJob?.cancel()
        shown = null
        views = null
        super.onDestroyView()
    }

    /** The current session's connection and recording; with no session, disconnected and idle. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun pageStates(): Flow<PageState> = sessions.session.flatMapLatest { session ->
        session?.let {
            combine(it.state, it.diagnostics.recording) { state, recording ->
                PageState(state == SessionState.Connected, recording)
            }
        } ?: flowOf(PageState(connected = false, recording = RecordingState.Idle()))
    }

    private fun show(state: PageState) {
        if (state.recording != page.recording) refusal = null
        page = state
        val views = views ?: return
        val recording = state.recording
        val finished = (recording as? RecordingState.Finished)?.capture
        val idle = recording !is RecordingState.Recording
        views.start.isEnabled = state.connected && idle
        views.duration.isEnabled = idle
        views.progressGroup.isVisible = !idle
        if (recording is RecordingState.Recording) showProgress(recording)
        val status = refusal ?: (recording as? RecordingState.Idle)?.aborted?.let(::messageOf)
            ?: R.string.debug_record_failed.takeIf { exports.state.value == ExportState.Failed && finished != null }
        views.status.isVisible = status != null
        status?.let(views.status::setText)
        views.empty.isVisible = !state.connected && finished == null
        views.review.forEach { it.isVisible = finished != null }
        showCapture(views, finished)
    }

    private fun showProgress(recording: RecordingState.Recording) {
        val views = views ?: return
        val totalMs = recording.seconds * MS_PER_SECOND
        val elapsedMs = (SystemClock.elapsedRealtime() - recording.startedElapsedMs).coerceIn(0, totalMs)
        views.progress.max = totalMs.toInt()
        views.progress.progress = elapsedMs.toInt()
        views.progressText.text =
            getString(R.string.debug_record_progress, (elapsedMs / MS_PER_SECOND).toInt(), recording.seconds)
        // TalkBack reads the progress in 5 s steps, not every tick; nothing announces it by itself.
        val step = (elapsedMs / MS_PER_SECOND).toInt() / DESCRIPTION_STEP_S * DESCRIPTION_STEP_S
        val description = getString(R.string.debug_record_progress, step, recording.seconds)
        if (views.progressText.contentDescription?.toString() != description) {
            views.progressText.contentDescription = description
        }
    }

    /** Shows [capture]'s rows, computed on the work dispatcher, and its review; none for null. */
    private fun showCapture(views: Views, capture: DiagnosticCapture?) {
        if (capture === shown) return
        shown = capture
        playback?.stop()
        rowsJob?.cancel()
        if (capture == null) {
            views.adapter.submit(emptyList(), 1, emptyMap())
            return
        }
        // Another capture: its review and any export of the one before start afresh.
        if (review.reviewing(capture.startedAtEpochMs)) exports.reset()
        val note = review.review.value.note
        if (views.note.text.toString() != note) views.note.text = note
        rowsJob = viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(parts.workDispatcher) { TrackRows.build(capture) }
            views.adapter.submit(rows, capture.durationMs, review.review.value.ratings)
        }
    }

    private fun onRecordTapped() {
        if (settings.isRecordingNoticeHidden) {
            startRecording()
        } else if (childFragmentManager.findFragmentByTag(RecordingConsentDialog.TAG) == null) {
            RecordingConsentDialog().show(childFragmentManager, RecordingConsentDialog.TAG)
        }
    }

    /**
     * Starts a recording of the picked duration, unless the debug features were switched off
     * meanwhile (the notice can outlive the switch); says why when the session refuses.
     */
    private fun startRecording() {
        if (!settings.isDebugFeaturesEnabled) return
        val seconds = DURATIONS.entries.firstOrNull { it.value == views?.duration?.checkedButtonId }?.key
            ?: DEFAULT_SECONDS
        val answer = sessions.connected?.diagnostics?.startRecording(seconds)
            ?: RecordingStart.Refused(RecordingRefusal.NOT_CONNECTED)
        refusal = (answer as? RecordingStart.Refused)?.reason?.let(::messageOf)
        show(page)
    }

    /** Writes the ZIP off the main thread, one at a time; [showExport] offers it when written. */
    private fun share() {
        val capture = shown ?: return
        exports.export(capture, review.review.value)
    }

    /** Share waits while an export runs; a written ZIP goes to the share sheet once; a failure is said. */
    private fun showExport(state: ExportState) {
        views?.share?.isEnabled = state != ExportState.Running
        if (state is ExportState.Ready) {
            val send = DiagnosticShare.shareIntent(state.uri, getString(R.string.debug_record_share_subject))
            startActivity(Intent.createChooser(send, null))
            exports.shared()
        }
        show(page)
    }

    /** Drops the capture in the session and every shared ZIP of it (B13). */
    private fun discard() {
        exports.reset()
        playback?.stop()
        sessions.session.value?.diagnostics?.discardRecording()
        val context = requireContext().applicationContext
        val container = AppContainer.of(context)
        container.scope.launch(container.parts.ioDispatcher) { DiagnosticShare.clear(context) }
    }

    /**
     * One track playing at a time through a [TrackPlayer] on the playback stream (B11). The player's
     * callbacks come from its thread; they are handed to the main thread, positions at most one in
     * flight, and dropped once the playback they belong to was stopped.
     */
    private inner class Playback(private val adapter: TrackListAdapter) {
        private val main = Handler(Looper.getMainLooper())
        private var player: TrackPlayer? = null
        private var playerStream = 0
        private var playingId: String? = null
        private var durationMs = 1L

        /** Counts playbacks; a callback of an earlier one is stale. */
        private var generation = 0
        private val positionMs = AtomicLong()
        private val positionPosted = AtomicBoolean()

        /** Plays [track] from [fromFraction]; with null, toggles it from its start. */
        fun toggle(track: CapturedTrack, fromFraction: Float?) {
            if (fromFraction == null && track.id == playingId) {
                stop()
                return
            }
            val capture = shown ?: return
            val player = playerFor(if (page.connected) Settings.PLAYBACK_STREAM else AudioManager.STREAM_MUSIC)
            val mine = ++generation
            durationMs = capture.durationMs.coerceAtLeast(1)
            playingId = track.id
            adapter.setPlaying(track.id)
            val from = fromFraction ?: 0f
            adapter.setPlayhead(from)
            player.play(
                track,
                fromMs = (from * durationMs).toLong(),
                onPosition = { ms -> post(mine, ms) },
                onEnd = { main.post { if (mine == generation) stop() } },
            )
        }

        /** The player on [stream], made anew when the stream changed with the connection. */
        private fun playerFor(stream: Int): TrackPlayer {
            val existing = player?.takeIf { playerStream == stream }
            if (existing != null) return existing
            player?.stop()
            playerStream = stream
            return TrackPlayer(parts.micPlayback, stream).also { player = it }
        }

        private fun post(mine: Int, ms: Long) {
            positionMs.set(ms)
            if (!positionPosted.compareAndSet(false, true)) return
            main.post {
                positionPosted.set(false)
                if (mine == generation) adapter.setPlayhead(positionMs.get().toFloat() / durationMs)
            }
        }

        fun stop() {
            generation++
            player?.stop()
            playingId = null
            adapter.setPlaying(null)
            adapter.setPlayhead(null)
        }
    }

    private companion object {
        /** The offered lengths and their buttons (B3); the session accepts exactly these. */
        val DURATIONS = mapOf(
            5 to R.id.record_duration_5,
            10 to R.id.record_duration_10,
            20 to R.id.record_duration_20,
            30 to R.id.record_duration_30,
        )
        const val DEFAULT_SECONDS = 10
        const val MS_PER_SECOND = 1000L
        const val DESCRIPTION_STEP_S = 5
        val PROGRESS_TICK = 250.milliseconds

        @StringRes
        fun messageOf(refusal: RecordingRefusal): Int? = when (refusal) {
            RecordingRefusal.NOT_CONNECTED -> R.string.debug_record_refused_offline
            RecordingRefusal.NOT_READY -> R.string.debug_record_refused_not_ready
            RecordingRefusal.SERVER_FORBIDS -> R.string.debug_record_refused_server
            RecordingRefusal.OUT_OF_MEMORY -> R.string.debug_record_refused_memory
            // The page offers only valid lengths and disables Record while one runs.
            RecordingRefusal.ALREADY_RECORDING, RecordingRefusal.INVALID_DURATION -> null
        }

        @StringRes
        fun messageOf(aborted: AbortReason): Int = when (aborted) {
            AbortReason.CANCELLED -> R.string.debug_record_cancelled
            AbortReason.DISCONNECTED -> R.string.debug_record_aborted_disconnect
            AbortReason.FAILED -> R.string.debug_record_failed
        }
    }
}

/**
 * The user's review of the finished recording on the page: a [Rating] per track and one note,
 * kept across recreation and process death in [state], never in the database. Reset when another
 * capture comes up for review.
 */
class RecordReviewViewModel(private val state: SavedStateHandle) : ViewModel() {
    private val current = MutableStateFlow(restore())
    val review: StateFlow<CaptureReview> = current.asStateFlow()

    private fun restore(): CaptureReview {
        val ratings = state.get<HashMap<String, String>>(KEY_RATINGS).orEmpty()
            .mapNotNull { (id, name) -> Rating.entries.firstOrNull { it.name == name }?.let { id to it } }
            .toMap()
        return CaptureReview(ratings, state.get<String>(KEY_NOTE).orEmpty())
    }

    /**
     * Makes the capture that started at [startedAtEpochMs] the reviewed one; a new one starts
     * unrated. Returns whether it is new.
     */
    fun reviewing(startedAtEpochMs: Long): Boolean {
        if (state.get<Long>(KEY_CAPTURE) == startedAtEpochMs) return false
        state[KEY_CAPTURE] = startedAtEpochMs
        update(CaptureReview(emptyMap(), ""))
        return true
    }

    /** Rates [trackId]; null takes the rating back. */
    fun rate(trackId: String, rating: Rating?) {
        val ratings = review.value.ratings
        update(review.value.copy(ratings = if (rating == null) ratings - trackId else ratings + (trackId to rating)))
    }

    fun setNote(text: String) {
        if (text != review.value.note) update(review.value.copy(note = text))
    }

    private fun update(review: CaptureReview) {
        current.value = review
        state[KEY_RATINGS] = HashMap(review.ratings.mapValues { it.value.name })
        state[KEY_NOTE] = review.note
    }

    private companion object {
        const val KEY_CAPTURE = "capture"
        const val KEY_RATINGS = "ratings"
        const val KEY_NOTE = "note"
    }
}
