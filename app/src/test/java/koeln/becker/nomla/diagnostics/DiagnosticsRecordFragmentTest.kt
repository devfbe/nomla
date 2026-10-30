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
import android.content.DialogInterface
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import koeln.becker.nomla.R
import koeln.becker.nomla.Settings
import koeln.becker.nomla.mumble.diagnostics.AbortReason
import koeln.becker.nomla.mumble.diagnostics.CapturedTrack
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.mumble.diagnostics.RecordingRefusal
import koeln.becker.nomla.mumble.diagnostics.RecordingStart
import koeln.becker.nomla.mumble.diagnostics.RecordingState
import koeln.becker.nomla.mumble.session.SessionState
import koeln.becker.nomla.mumble.testutil.FakeSession
import koeln.becker.nomla.mumble.testutil.TestPlaybackSink
import koeln.becker.nomla.mumble.testutil.idleMainLooper
import koeln.becker.nomla.mumble.testutil.idleMainLooperFor
import koeln.becker.nomla.testing.FileProviderCache
import koeln.becker.nomla.testing.ServiceHostActivity
import koeln.becker.nomla.testing.addNow
import koeln.becker.nomla.testing.drainMainUntil
import koeln.becker.nomla.testing.hostWith
import koeln.becker.nomla.testing.replaceParts
import java.time.Duration

/**
 * The Recording page: duration, the consent notice, a recording's progress and its end, and a
 * finished recording's tracks with ratings, playback, Share and Discard. Fake sessions and sinks
 * only: nothing is recorded or heard.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsRecordFragmentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = Settings.getInstance(context)
    private val session = FakeSession(SessionState.Connected)
    private val diagnostics get() = session.diagnostics
    private val sink = TestPlaybackSink()
    private val speaker = TestPlaybackSink.Factory(sink)
    private lateinit var controller: ActivityController<ServiceHostActivity>

    @Before
    fun setUp() {
        FileProviderCache.clear()
        setDebug(true)
        // Export and rows run inline, so a test sees their result at once; the speaker is a fake.
        val inline = Dispatchers.Unconfined
        replaceParts { it.copy(micPlayback = speaker, ioDispatcher = inline, workDispatcher = inline) }
    }

    private fun setDebug(on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(Settings.DEBUG_FEATURES.key, on)
            .commit()
        idleMainLooper()
    }

    private fun start() {
        controller = hostWith(session)
        controller.get().addNow(DiagnosticsRecordFragment(), TAG, inContent = true)
        idleMainLooper()
    }

    private fun page() = controller.get().supportFragmentManager.findFragmentByTag(TAG) as DiagnosticsRecordFragment

    private fun <V : View> view(id: Int): V = page().requireView().findViewById(id)

    private fun text(id: Int) = view<TextView>(id).text.toString()

    private fun shown(id: Int) = view<View>(id).isShown

    private fun click(id: Int) {
        view<View>(id).performClick()
        idleMainLooper()
    }

    private fun consent(): AlertDialog? =
        (page().childFragmentManager.findFragmentByTag(RecordingConsentDialog.TAG) as? RecordingConsentDialog)
            ?.dialog as? AlertDialog

    private fun confirm() {
        consent()!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()
    }

    private fun tracks(): RecyclerView = view<RecyclerView>(R.id.record_tracks).also {
        it.measure(
            View.MeasureSpec.makeMeasureSpec(LIST_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(LIST_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        it.layout(0, 0, LIST_WIDTH, LIST_HEIGHT)
    }

    private fun row(position: Int): View = tracks().findViewHolderForAdapterPosition(position)!!.itemView

    private fun seconds(n: Int) = context.getString(R.string.debug_record_seconds, n)

    private fun progress(elapsed: Int, of: Int) = context.getString(R.string.debug_record_progress, elapsed, of)

    @Test
    fun `default duration is 10 s and the choices are 5, 10, 20, 30`() {
        start()

        val group = view<MaterialButtonToggleGroup>(R.id.record_duration)
        val choices = (0 until group.childCount).map { (group.getChildAt(it) as MaterialButton).text.toString() }
        assertThat(choices).containsExactly(seconds(5), seconds(10), seconds(20), seconds(30)).inOrder()
        assertThat(view<MaterialButton>(group.checkedButtonId).text.toString()).isEqualTo(seconds(10))
    }

    @Test
    fun `record shows the dialog, then starts with the chosen duration`() {
        start()
        click(R.id.record_duration_20)

        click(R.id.record_start)
        assertThat(consent()?.isShowing).isTrue()
        assertThat(diagnostics.startCalls).isEmpty()

        confirm()
        assertThat(diagnostics.startCalls).containsExactly(20)
    }

    @Test
    fun `with the notice hidden, record starts directly`() {
        settings.isRecordingNoticeHidden = true
        start()

        click(R.id.record_start)

        assertThat(consent()).isNull()
        assertThat(diagnostics.startCalls).containsExactly(10)
    }

    @Test
    fun `confirming the dialog after debug was switched off starts nothing`() {
        start()
        click(R.id.record_start)

        setDebug(false)
        confirm()

        assertThat(diagnostics.startCalls).isEmpty()
    }

    @Test
    fun `a refusal shows its reason`() {
        settings.isRecordingNoticeHidden = true
        start()
        val reasons = mapOf(
            RecordingRefusal.NOT_CONNECTED to R.string.debug_record_refused_offline,
            RecordingRefusal.NOT_READY to R.string.debug_record_refused_not_ready,
            RecordingRefusal.SERVER_FORBIDS to R.string.debug_record_refused_server,
            RecordingRefusal.OUT_OF_MEMORY to R.string.debug_record_refused_memory,
        )
        for ((refusal, message) in reasons) {
            diagnostics.startResult = RecordingStart.Refused(refusal)
            click(R.id.record_start)

            assertThat(shown(R.id.record_status)).isTrue()
            assertThat(text(R.id.record_status)).isEqualTo(context.getString(message))
        }
    }

    @Test
    fun `recording shows progress and cancel cancels`() {
        start()

        diagnostics.recording.value = RecordingState.Recording(SystemClock.elapsedRealtime(), 10)
        idleMainLooper()
        assertThat(shown(R.id.record_progress_group)).isTrue()
        assertThat(text(R.id.record_progress_text)).isEqualTo(progress(0, 10))
        assertThat(view<View>(R.id.record_start).isEnabled).isFalse()

        // The page ticks a few times a second; just past 3 s it shows 3 s.
        idleMainLooperFor(Duration.ofMillis(THREE_SECONDS_AND_A_TICK_MS))
        assertThat(text(R.id.record_progress_text)).isEqualTo(progress(3, 10))

        click(R.id.record_cancel)
        assertThat(diagnostics.cancelCalls).isEqualTo(1)
    }

    @Test
    fun `recreating the activity during a recording shows its progress`() {
        diagnostics.recording.value = RecordingState.Recording(SystemClock.elapsedRealtime(), 20)
        start()
        idleMainLooperFor(Duration.ofSeconds(4))

        controller.recreate()
        idleMainLooper()

        assertThat(shown(R.id.record_progress_group)).isTrue()
        assertThat(text(R.id.record_progress_text)).isEqualTo(progress(4, 20))
    }

    @Test
    fun `a finished capture lists every track with ratings, and share hands out one zip uri`() {
        diagnostics.recording.value = RecordingState.Finished(capture())
        start()

        val names = (0 until AUDIO_ROWS).map { row(it).findViewById<TextView>(R.id.track_name).text.toString() }
        assertThat(names).containsExactly(
            context.getString(R.string.debug_stage_mic_raw),
            context.getString(R.string.debug_stage_apm),
            context.getString(R.string.debug_stage_sent),
            context.getString(R.string.debug_speaker, 1),
            context.getString(R.string.debug_track_mix),
        ).inOrder()
        for (position in 0 until AUDIO_ROWS) {
            val rating = row(position).findViewById<MaterialButtonToggleGroup>(R.id.track_rating)
            assertThat(rating.isShown).isTrue()
            assertThat(rating.childCount).isEqualTo(3)
        }
        val lanes = (AUDIO_ROWS until tracks().adapter!!.itemCount).map {
            row(it).findViewById<TextView>(R.id.track_name).text.toString()
        }
        assertThat(lanes).containsExactly(
            context.getString(R.string.debug_lane_gate),
            context.getString(R.string.debug_lane_underruns),
            context.getString(R.string.debug_lane_packet_gaps),
            context.getString(R.string.debug_lane_transport),
        ).inOrder()
        assertThat(shown(R.id.record_playback_hint)).isTrue()

        click(R.id.record_share)

        val chooser = shadowOf(controller.get()).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        @Suppress("DEPRECATION") // The typed overload needs API 33; minSdk is 31.
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.clipData!!.itemCount).isEqualTo(1)
        assertThat(send.clipData!!.getItemAt(0).uri.scheme).isEqualTo("content")
        assertThat(view<View>(R.id.record_share).isEnabled).isTrue()
    }

    @Test
    fun `a rating is kept, can be taken back, and a new capture starts unrated`() {
        diagnostics.recording.value = RecordingState.Finished(capture())
        start()

        row(1).findViewById<View>(R.id.track_rating_bad).performClick()
        idleMainLooper()
        assertThat(review().ratings).containsExactly("3_apm", Rating.BAD)
        view<TextView>(R.id.record_note).text = "crackles"
        assertThat(review().note).isEqualTo("crackles")

        row(1).findViewById<View>(R.id.track_rating_bad).performClick()
        idleMainLooper()
        assertThat(review().ratings).isEmpty()

        row(0).findViewById<View>(R.id.track_rating_good).performClick()
        diagnostics.recording.value = RecordingState.Finished(capture(startedAtMs = LATER_START_MS))
        idleMainLooper()
        assertThat(review()).isEqualTo(CaptureReview(emptyMap(), ""))
        assertThat(row(0).findViewById<MaterialButtonToggleGroup>(R.id.track_rating).checkedButtonId)
            .isEqualTo(View.NO_ID)
        assertThat(text(R.id.record_note)).isEmpty()
    }

    @Test
    fun `tapping a track plays only that track on the playback stream`() {
        diagnostics.recording.value = RecordingState.Finished(capture())
        start()

        row(1).findViewById<View>(R.id.track_play).performClick()
        drainMainUntil { sink.written.sumOf { it.size } == SAMPLES }

        assertThat(speaker.openedWith).isEqualTo(Settings.PLAYBACK_STREAM to RATE)
        val played = sink.written.flatMap { it.toList() }
        assertThat(played).containsExactlyElementsIn(apmSamples().toList()).inOrder()
    }

    @Test
    fun `discard drops the capture`() {
        diagnostics.recording.value = RecordingState.Finished(capture())
        start()

        click(R.id.record_discard)

        assertThat(diagnostics.discardCalls).isEqualTo(1)
    }

    @Test
    fun `aborted by disconnect shows the reason`() {
        start()

        diagnostics.recording.value = RecordingState.Idle(AbortReason.DISCONNECTED)
        idleMainLooper()
        assertThat(text(R.id.record_status)).isEqualTo(context.getString(R.string.debug_record_aborted_disconnect))

        diagnostics.recording.value = RecordingState.Idle(AbortReason.FAILED)
        idleMainLooper()
        assertThat(text(R.id.record_status)).isEqualTo(context.getString(R.string.debug_record_failed))
    }

    @Test
    fun `a finished capture stays visible after a disconnect`() {
        diagnostics.recording.value = RecordingState.Finished(capture())
        start()

        session.state.value = SessionState.Disconnected()
        idleMainLooper()

        assertThat(shown(R.id.record_tracks)).isTrue()
        assertThat(tracks().adapter!!.itemCount).isGreaterThan(0)
        assertThat(shown(R.id.record_share)).isTrue()
        assertThat(shown(R.id.record_empty)).isFalse()
        assertThat(view<View>(R.id.record_start).isEnabled).isFalse()
    }

    @Test
    fun `without a connection and a capture the page shows its empty state`() {
        session.state.value = SessionState.Disconnected()
        start()

        assertThat(shown(R.id.record_empty)).isTrue()
        assertThat(view<View>(R.id.record_start).isEnabled).isFalse()
    }

    @Test
    fun `a cancelled recording says so`() {
        start()

        diagnostics.recording.value = RecordingState.Idle(AbortReason.CANCELLED)
        idleMainLooper()

        assertThat(shown(R.id.record_status)).isTrue()
        assertThat(text(R.id.record_status)).isEqualTo(context.getString(R.string.debug_record_cancelled))
    }

    private fun review() = ViewModelProvider(page())[RecordReviewViewModel::class.java].review.value

    private fun apmSamples() = ShortArray(SAMPLES) { (it % SAMPLE_RANGE).toShort() }

    private fun capture(startedAtMs: Long = START_MS) = DiagnosticCapture(
        startedAtEpochMs = startedAtMs,
        durationMs = DURATION_MS,
        tracks = listOf(
            CapturedTrack("7_mix", RATE, ShortArray(SAMPLES), sendSide = false),
            CapturedTrack("speaker_1", RATE, ShortArray(SAMPLES), sendSide = false),
            CapturedTrack("1_mic_raw", RATE, ShortArray(SAMPLES), sendSide = true),
            CapturedTrack("6_sent", RATE, ShortArray(SAMPLES), sendSide = true),
            CapturedTrack("3_apm", RATE, apmSamples(), sendSide = true),
        ),
        tables = emptyList(),
        events = emptyList(),
        pipelines = emptyList(),
        serverShowedRecording = true,
        speakersWithoutTrack = 0,
        droppedFrames = 0,
        route = null,
    )

    private companion object {
        const val TAG = "record"
        const val AUDIO_ROWS = 5
        const val RATE = 16_000
        const val SAMPLES = 16_000
        const val SAMPLE_RANGE = 500
        const val DURATION_MS = 1000L
        const val LIST_WIDTH = 480
        const val LIST_HEIGHT = 4000
        const val THREE_SECONDS_AND_A_TICK_MS = 3_300L

        // 2026-10-30T12:34:00Z and a minute later.
        const val START_MS = 1_793_363_640_000L
        const val LATER_START_MS = 1_793_363_700_000L
    }
}
