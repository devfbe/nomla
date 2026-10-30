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

package koeln.becker.nomla.mumble.audio

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioTrack
import koeln.becker.nomla.mumble.audio.capture.CaptureState
import koeln.becker.nomla.mumble.audio.capture.EchoCancellationMode
import koeln.becker.nomla.mumble.audio.capture.NoiseSuppressionMode
import koeln.becker.nomla.mumble.audio.inputmode.ContinuousInputMode
import koeln.becker.nomla.mumble.diagnostics.CaptureTaps
import koeln.becker.nomla.mumble.diagnostics.DiagnosticsHub
import koeln.becker.nomla.mumble.diagnostics.SendStage
import koeln.becker.nomla.mumble.model.UserState
import koeln.becker.nomla.mumble.testutil.NoopEncodeListener
import koeln.becker.nomla.mumble.testutil.NoopOutputListener
import koeln.becker.nomla.mumble.testutil.SilentLogger
import koeln.becker.nomla.mumble.testutil.TestCaptureSource
import koeln.becker.nomla.mumble.testutil.awaitUntil
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a session's capture reports reaches the pipeline's owner. The microphone is a
 * [TestCaptureSource]; no codec (so no native Opus encoder) and no noise suppression or echo
 * cancellation (so no native preprocessor), which leaves a pipeline the JVM can run.
 */
@RunWith(RobolectricTestRunner::class)
class AudioHandlerCaptureStateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val states = CopyOnWriteArrayList<CaptureState>()
    private var handler: AudioHandler? = null

    private val params = AudioSessionParams(UserState(1, "me", 0), -1, null, 0, ContinuousInputMode())
    private val config = AudioConfig(
        PipelineSettings(noiseSuppression = NoiseSuppressionMode.NONE),
        echoCancellation = EchoCancellationMode.NONE,
    )

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioTrack.setMinBufferSize(MIN_TRACK_BUFFER_BYTES)
    }

    @After
    fun tearDown() {
        handler?.shutdown()
    }

    private fun startedHandler(source: TestCaptureSource, diagnostics: DiagnosticsHub? = null): AudioHandler =
        AudioHandler(
            AudioHost(app, SilentLogger, NoopEncodeListener, NoopOutputListener, diagnostics),
            config,
            params,
            onCaptureState = { states += it },
            captureFactory = { source },
        ).also {
            handler = it
            it.start()
        }

    @Test
    fun thePlatformSilencingTheMicrophoneAndReleasingItReachesTheOwner() {
        val source = TestCaptureSource()
        startedHandler(source)
        val platform = checkNotNull(source.silenceListener) { "capture registered no silence listener" }

        platform(true)
        platform(false)

        assertThat(states).containsExactly(CaptureState.Silenced, CaptureState.Active).inOrder()
    }

    @Test
    fun aCaptureThatCannotStartReachesTheOwnerAsAnError() {
        startedHandler(TestCaptureSource(failStart = true))

        awaitUntil(description = "the capture error") { states.isNotEmpty() }
        assertThat(states.single()).isInstanceOf(CaptureState.Error::class.java)
    }

    /** With diagnostics the owner still hears every state, and the recording gets the pipeline and the silencing. */
    @Test
    fun aPipelineWithDiagnosticsDescribesItselfAndLogsTheSilencing() {
        val hub = DiagnosticsHub()
        hub.arm(5)
        val source = TestCaptureSource()
        startedHandler(source, hub)
        val platform = checkNotNull(source.silenceListener) { "capture registered no silence listener" }

        platform(true)
        platform(false)
        val recording = checkNotNull(hub.disarm())

        assertThat(states).containsExactly(CaptureState.Silenced, CaptureState.Active).inOrder()
        val pipeline = recording.pipelines.single()
        assertThat(pipeline.stagesPresent).containsExactly(SendStage.MIC_RAW, SendStage.GATED, SendStage.SENT)
        assertThat(pipeline.capture.sampleRate).isEqualTo(source.sampleRate)
        assertThat(pipeline.settings).containsEntry("opus_fec", "1")
        assertThat(pipeline.settings).containsEntry("frames_per_packet", "2")
        assertThat(pipeline.settings).containsEntry("noise_suppression", "none")
        val events = recording.events.freeze(recording.t0Nanos).map { it.name to it.value }
        assertThat(events).containsExactly(
            "pipeline_rebuilt" to "",
            CaptureTaps.CAPTURE_SILENCED to "1",
            CaptureTaps.CAPTURE_SILENCED to "0",
        ).inOrder()
    }

    private companion object {
        const val MIN_TRACK_BUFFER_BYTES = 3840
    }
}
